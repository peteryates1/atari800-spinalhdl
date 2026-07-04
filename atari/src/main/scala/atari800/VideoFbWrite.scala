package atari800

import spinal.core._
import spinal.lib._

// Stage 2 (write path): capture the Atari core's video and write each active
// pixel (8-bit GTIA colour index) into a linear framebuffer in SDRAM.
//
// Runs entirely in the Atari sys clock domain (same as the core's video output
// and the SDRAM controller's client interface). A small FIFO decouples the
// bursty pixel capture from the slower per-word SDRAM writes; average active
// pixel rate (~320x240x50 ~ 4 Mpix/s) is well within single-word SDRAM write
// throughput, so the FIFO only has to absorb per-line bursts.
//
// Address layout: byte address = fbBase + y*stride + x (1 byte/pixel).
// stride is a power of two so the multiply is a shift.
case class FbWriteReq(addrWidth: Int) extends Bundle {
  val addr = UInt(addrWidth bits)   // 4-aligned
  val data = Bits(32 bits)          // 4 pixels, little-endian (byte 0 at addr)
}

class VideoFbWrite(
    fbBase:    Int = 0,
    width:     Int = 384,     // max active pixels captured per line
    strideLog2:Int = 9,       // bytes per line = 512
    height:    Int = 288,     // max active lines captured per frame
    addrWidth: Int = 25,
    fifoDepth: Int = 512,
    debugPattern: Boolean = false,  // write a checkerboard instead of io.colour
    debugFill:    Boolean = false,  // free-running sweep, ignore all video inputs
    clearOnReset: Boolean = false   // sweep the fb with black once after reset
) extends Component {
  val io = new Bundle {
    // Hold LOW until the SDRAM controller is out of reset/init. Writes issued
    // during controller reset wedge its init sequence and corrupt ALL SDRAM
    // (including the Atari's RAM -> crash at boot; found on hardware when the
    // clear sweep started the instant reset released).
    val enable    = in  Bool()
    // ---- video input (sys domain) ----
    val pixStrobe = in  Bool()          // pulse: a new pixel sample is valid
    val colour    = in  Bits(8 bits)    // GTIA colour index
    val hsync     = in  Bool()          // active-high; rising edge = new line
    val vsync     = in  Bool()          // active-high; rising edge = new frame
    val blank     = in  Bool()          // 1 = blanking (skip pixel)
    // ---- SDRAM write-request port (client side, sys domain) ----
    val wrAddr     = out Bits(addrWidth bits)
    val wrData     = out Bits(32 bits)
    val wrReq      = out Bool()
    val wrLong     = out Bool()         // 32-bit access (4 packed pixels)
    val wrComplete = in  Bool()
    // ---- status ----
    val frameCount = out UInt(16 bits)
    val overflow   = out Bool()         // sticky: a pixel was dropped (FIFO full)
    val dbgDropTgl = out Bool()         // toggles per dropped quad (rate meter)
    val dbgLines   = out UInt(10 bits)  // hsyncs counted in the previous frame
    val dbgEdgeY   = out UInt(11 bits)  // y of the LAST dark->bright line transition
  }

  // ---- Capture side: track x/y, push {addr,data} on each active pixel ----
  val hsD    = RegNext(io.hsync) init False
  val vsD    = RegNext(io.vsync) init False
  val hsRise = io.hsync && !hsD
  val vsRise = io.vsync && !vsD

  val x = Reg(UInt(11 bits)) init 0
  val y = Reg(UInt(11 bits)) init 0
  val frameCount = Reg(UInt(16 bits)) init 0
  val overflow   = Reg(Bool()) init False
  val dropTgl    = Reg(Bool()) init False
  io.frameCount := frameCount
  io.overflow   := overflow
  io.dbgDropTgl := dropTgl
  // capture-stability probe: lines per frame must be constant (312 PAL)
  val lineCnt       = Reg(UInt(10 bits)) init 0
  val linesPerFrame = Reg(UInt(10 bits)) init 0
  when(hsRise) { lineCnt := lineCnt + 1 }
  when(vsRise) { linesPerFrame := lineCnt; lineCnt := 0 }
  io.dbgLines := linesPerFrame
  // content-edge probe: fb row of the last dark->bright transition per frame
  // (in-game: the readout panel top; starfield above it is mostly black)
  val nzCnt      = Reg(UInt(10 bits)) init 0
  val prevBright = Reg(Bool()) init False
  val edgeYAcc   = Reg(UInt(11 bits)) init 0
  val edgeY      = Reg(UInt(11 bits)) init 0
  io.dbgEdgeY := edgeY

  require(width % 4 == 0, "width must be a multiple of 4 (packed 32-bit writes)")
  val fifo = StreamFifo(FbWriteReq(addrWidth), fifoDepth)

  val inFrame = x < width && y < height
  val armed = Reg(Bool()) init True
  val chk = Mux(x(5) ^ y(5), B(0x1A, 8 bits), B(0x86, 8 bits))
  // Pixels are packed 4-to-a-word (SDRAM byte writes cannot keep up with the
  // pixel rate once reads have priority): bytes 0..2 of each quad accumulate
  // in `quad`, the 4th completes the word and pushes one 32-bit write.
  val quad = Reg(Bits(24 bits)) init 0
  fifo.io.push.payload.addr :=
    (U(fbBase, addrWidth bits) + (y << strideLog2) + (x(10 downto 2) @@ U"2'b00")).resize(addrWidth)

  if (debugFill) {
    // Free-running sweep of the whole framebuffer, ignoring ALL video inputs —
    // proves the SDRAM write -> read -> display path in isolation.
    fifo.io.push.valid        := x(1 downto 0) === 3
    fifo.io.push.payload.data := chk ## quad
    when(x(1 downto 0) =/= 3) {
      quad.subdivideIn(8 bits)(x(1 downto 0)) := chk
      x := x + 1
    } elsewhen(fifo.io.push.ready) {
      when(x === (width - 1)) { x := 0; y := Mux(y === (height - 1), U(0), y + 1) }
        .otherwise { x := x + 1 }
    } otherwise { overflow := True }   // FIFO full => SDRAM writes not draining
  } else {
    // One black sweep of the whole buffer after reset (before capturing) so no
    // stale SDRAM content survives where the capture window doesn't reach.
    val clearing = RegInit(Bool(clearOnReset))
    val pixData = (if (debugPattern) chk else io.colour)
    val push = !clearing && io.pixStrobe && !io.blank && inFrame && x(1 downto 0) === 3
    fifo.io.push.valid        := push
    fifo.io.push.payload.data := pixData ## quad
    when(clearing) {
      fifo.io.push.valid        := x(1 downto 0) === 3
      fifo.io.push.payload.data := 0
      when(x(1 downto 0) =/= 3) {
        quad.subdivideIn(8 bits)(x(1 downto 0)) := B(0, 8 bits)
        x := x + 1
      } elsewhen(fifo.io.push.ready) {
        when(x === (width - 1)) {
          x := 0
          when(y === (height - 1)) { y := 0; clearing := False; armed := True }
            .otherwise { y := y + 1 }
        } otherwise { x := x + 1 }
      }
    } otherwise {
      when(io.pixStrobe && !io.blank && inFrame) {
        when(x(1 downto 0) =/= 3) {
          quad.subdivideIn(8 bits)(x(1 downto 0)) := pixData
          x := x + 1
        } otherwise {
          when(fifo.io.push.ready) { x := x + 1 }
            .otherwise { overflow := True; dropTgl := !dropTgl; x := x + 1 }
        }
        when(pixData =/= 0) { nzCnt := nzCnt + 1 }
      }
      when(hsRise) {
        x := 0
        when(armed) { armed := False }
          .elsewhen(y =/= (height - 1)) { y := y + 1 }
        val bright = nzCnt > 200
        when(bright && !prevBright) { edgeYAcc := y }
        prevBright := bright
        nzCnt := 0
      }
      when(vsRise) {
        x := 0; y := 0; armed := True; frameCount := frameCount + 1
        edgeY := edgeYAcc; prevBright := False
      }
    }
  }

  // Gate everything until the SDRAM is ready: no pushes (holds the clear
  // sweep and capture at their start) and no drain requests.
  when(!io.enable) {
    fifo.io.push.valid := False
    x := 0; y := 0
  }

  // ---- Drain side: pop FIFO -> issue 32-bit SDRAM writes ----
  val inFlight = Reg(Bool()) init False
  val busySeen = Reg(Bool()) init False

  io.wrReq  := fifo.io.pop.valid && !inFlight && io.enable
  io.wrLong := True
  io.wrAddr := fifo.io.pop.payload.addr.asBits
  io.wrData := fifo.io.pop.payload.data

  when(io.wrReq) { inFlight := True; busySeen := False }
  when(inFlight) {
    when(!io.wrComplete) { busySeen := True }
  }
  fifo.io.pop.ready := inFlight && busySeen && io.wrComplete
  when(fifo.io.pop.ready) { inFlight := False }
}
