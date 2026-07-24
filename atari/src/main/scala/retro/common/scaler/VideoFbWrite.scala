package retro.common.scaler
import retro.common.util._
import retro.common.video._
import retro.common.scaler._
import retro.common.sdram._

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
    // Runtime capture-window offset (centres the picture in the buffer without
    // a rebuild). hStart: fb column where each line's capture begins (shifts
    // content right). vSkip: lines skipped after vsync before capture starts
    // (shifts content up, dropping that many top-border lines).
    val hStart    = in  UInt(9 bits) default U(0, 9 bits)
    val vSkip     = in  UInt(9 bits) default U(0, 9 bits)
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
    val wrWide     = out Bool()         // 256-bit access (8 sequential quads)
    val wrWideData = out Bits(256 bits)
    val wrComplete = in  Bool()
    // ---- status ----
    val frameCount = out UInt(16 bits)
    // Double buffering: the just-completed frame's buffer index, published at
    // vsync. The display reads this buffer while capture fills the other, so
    // the 50 Hz PAL write and 60 Hz 720p read never touch the same buffer -
    // no tearing of moving content (the "photon noise" twinkle).
    val readyBuf   = out UInt(2 bits)
    val overflow   = out Bool()         // sticky: a pixel was dropped (FIFO full)
    val dbgDropTgl = out Bool()         // toggles per dropped quad (rate meter)
    val dbgLines   = out UInt(10 bits)  // hsyncs counted in the previous frame
    val dbgEdgeY   = out UInt(11 bits)  // y of the LAST dark->bright line transition
    // Bounding box of non-black captured content in the previous frame (fb
    // coordinates). Tells us exactly where the live picture sits inside the
    // 384x288 buffer, so the capture window can be centred without guessing.
    val bbMinX     = out UInt(9 bits)
    val bbMaxX     = out UInt(9 bits)
    val bbMinY     = out UInt(9 bits)
    val bbMaxY     = out UInt(9 bits)
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
  // Bounding box accumulators (reset each frame; latched at vsync).
  val bbMinXA = Reg(UInt(9 bits)) init 511
  val bbMaxXA = Reg(UInt(9 bits)) init 0
  val bbMinYA = Reg(UInt(9 bits)) init 511
  val bbMaxYA = Reg(UInt(9 bits)) init 0
  val bbMinX  = Reg(UInt(9 bits)) init 0
  val bbMaxX  = Reg(UInt(9 bits)) init 0
  val bbMinY  = Reg(UInt(9 bits)) init 0
  val bbMaxY  = Reg(UInt(9 bits)) init 0
  io.bbMinX := bbMinX; io.bbMaxX := bbMaxX; io.bbMinY := bbMinY; io.bbMaxY := bbMaxY

  require(width % 4 == 0, "width must be a multiple of 4 (packed 32-bit writes)")
  val fifo = StreamFifo(FbWriteReq(addrWidth), fifoDepth)

  val inFrame = x < width && y < height
  val armed = Reg(Bool()) init True
  val skipCnt = Reg(UInt(9 bits)) init 0          // top-border lines still to skip
  val xStart  = (io.hStart(8 downto 2) @@ U"2'b00").resize(11)  // 4-aligned (quad packing)
  val chk = Mux(x(5) ^ y(5), B(0x1A, 8 bits), B(0x86, 8 bits))
  // Pixels are packed 4-to-a-word (SDRAM byte writes cannot keep up with the
  // pixel rate once reads have priority): bytes 0..2 of each quad accumulate
  // in `quad`, the 4th completes the word and pushes one 32-bit write.
  val quad = Reg(Bits(24 bits)) init 0
  // Triple buffer: capture cycles writeBuf 0->1->2->0, publishing each as
  // readyBuf when its frame completes. Reader (60 Hz) is faster than writer
  // (50 Hz PAL), so two buffers can tear when the writer laps back onto the
  // buffer being displayed; three buffers cannot (the writer needs two full
  // frames to return, longer than one display frame). Buffers 0x40000 apart.
  val writeBuf   = Reg(UInt(2 bits)) init 0
  val readyBufR  = Reg(UInt(2 bits)) init 0
  io.readyBuf := readyBufR
  // The reset clear sweep runs over all three buffers (clearBuf cycles) so no
  // buffer's borders ever show stale SDRAM.
  val clearing = RegInit(Bool(clearOnReset))
  val clearBuf = Reg(UInt(2 bits)) init 0
  val bufSel    = Mux(clearing, clearBuf, writeBuf)
  val bufOffset = (bufSel << 18).resize(addrWidth)
  fifo.io.push.payload.addr :=
    (U(fbBase, addrWidth bits) + bufOffset + (y << strideLog2) + (x(10 downto 2) @@ U"2'b00")).resize(addrWidth)

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
    // One black sweep of BOTH buffers after reset (before capturing) so no
    // stale SDRAM content survives where the capture window doesn't reach.
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
          when(y === (height - 1)) {
            y := 0
            when(clearBuf =/= 2) { clearBuf := clearBuf + 1 }   // sweep next buffer
              .otherwise { clearing := False; armed := True }
          }
            .otherwise { y := y + 1 }
        } otherwise { x := x + 1 }
      }
    } otherwise {
      // capture only after the top-border skip (skipCnt == 0)
      val capActive = skipCnt === 0
      fifo.io.push.valid := push && capActive
      when(io.pixStrobe && !io.blank && inFrame && capActive) {
        when(x(1 downto 0) =/= 3) {
          quad.subdivideIn(8 bits)(x(1 downto 0)) := pixData
          x := x + 1
        } otherwise {
          when(fifo.io.push.ready) { x := x + 1 }
            .otherwise { overflow := True; dropTgl := !dropTgl; x := x + 1 }
        }
        when(pixData =/= 0) {
          nzCnt := nzCnt + 1
          when(x.resize(9) < bbMinXA) { bbMinXA := x.resize(9) }
          when(x.resize(9) > bbMaxXA) { bbMaxXA := x.resize(9) }
          when(y.resize(9) < bbMinYA) { bbMinYA := y.resize(9) }
          when(y.resize(9) > bbMaxYA) { bbMaxYA := y.resize(9) }
        }
      }
      when(hsRise) {
        x := xStart
        when(skipCnt =/= 0) {
          skipCnt := skipCnt - 1
          when(skipCnt === 1) { armed := False }   // last skip line consumes the arm
        }
          .elsewhen(armed) { armed := False }
          .elsewhen(y =/= (height - 1)) { y := y + 1 }
        val bright = nzCnt > 200
        when(bright && !prevBright) { edgeYAcc := y }
        prevBright := bright
        nzCnt := 0
      }
      when(vsRise) {
        x := xStart; y := 0; armed := True; frameCount := frameCount + 1
        skipCnt := io.vSkip
        readyBufR := writeBuf                                  // frame just captured is ready
        writeBuf  := Mux(writeBuf === 2, U(0, 2 bits), writeBuf + 1)  // next of three
        edgeY := edgeYAcc; prevBright := False
        bbMinX := bbMinXA; bbMaxX := bbMaxXA; bbMinY := bbMinYA; bbMaxY := bbMaxYA
        bbMinXA := 511; bbMaxXA := 0; bbMinYA := 511; bbMaxYA := 0
      }
    }
  }

  // Gate everything until the SDRAM is ready: no pushes (holds the clear
  // sweep and capture at their start) and no drain requests.
  when(!io.enable) {
    fifo.io.push.valid := False
    x := 0; y := 0
  }

  // ---- Drain side: batch 8 sequential quads -> one 256-bit SDRAM write ----
  // Quads within a line are strictly sequential and lines are 32-byte
  // aligned (stride 512), so full batches are the norm: 12 wide writes per
  // line instead of 96 singles. Discontinuities (dropped quads, frame edges)
  // or an idle FIFO flush the partial batch as single writes - correct, and
  // rare enough that speed is irrelevant there.
  val inFlight  = Reg(Bool()) init False
  val busySeen  = Reg(Bool()) init False
  val wideFly   = Reg(Bool()) init False        // in-flight txn is wide
  val batch     = Reg(Vec(Bits(32 bits), 8))
  val batchCnt  = Reg(UInt(4 bits)) init 0
  val batchBase = Reg(UInt(addrWidth bits)) init 0
  val flushing  = Reg(Bool()) init False
  val flushIdx  = Reg(UInt(3 bits)) init 0
  val idleCnt   = Reg(UInt(7 bits)) init 0

  val popAddr  = fifo.io.pop.payload.addr
  val expAddr  = batchBase + (batchCnt << 2)
  val aligned  = popAddr(4 downto 0) === 0

  fifo.io.pop.ready := False
  io.wrReq      := False
  io.wrWide     := False
  io.wrLong     := True
  io.wrAddr     := (batchBase + (flushIdx << 2)).asBits
  io.wrData     := batch(flushIdx)
  io.wrWideData := batch.asBits                 // batch(0) = lowest address

  // The arbiter serves port B cycles AFTER the request pulse, and the
  // controller snapshots the access flags at SERVE time - so wrWide (and
  // addr/data, which are held via regs) must stay valid for the whole
  // transaction, not just the request cycle. A pulse-shaped wrWide made the
  // controller run a single-beat write of word 0 only (all other words of
  // every batch silently unwritten; found via fb residue dumps on HW).
  when(inFlight) {
    io.wrWide := wideFly
    when(wideFly) { io.wrAddr := batchBase.asBits }
  }
  when(io.enable && !inFlight) {
    when(flushing) {
      io.wrReq  := True                         // single write from the batch
      io.wrAddr := (batchBase + (flushIdx << 2)).asBits
    } elsewhen(batchCnt === 8) {
      io.wrReq  := True                         // full batch: one wide write
      io.wrWide := True
      io.wrAddr := batchBase.asBits
    } elsewhen(fifo.io.pop.valid) {
      idleCnt := 0
      when(batchCnt === 0) {
        batchBase := popAddr
        batch(0)  := fifo.io.pop.payload.data
        fifo.io.pop.ready := True
        batchCnt := 1
        when(!aligned) { flushing := True; flushIdx := 0 }  // lone unaligned quad
      } elsewhen(popAddr === expAddr) {
        batch(batchCnt(2 downto 0)) := fifo.io.pop.payload.data
        fifo.io.pop.ready := True
        batchCnt := batchCnt + 1
      } otherwise {
        flushing := True; flushIdx := 0         // gap: flush partial as singles
      }
    } otherwise {
      when(batchCnt =/= 0) {
        idleCnt := idleCnt + 1
        when(idleCnt === idleCnt.maxValue) { flushing := True; flushIdx := 0 }
      }
    }
  }

  when(io.wrReq) { inFlight := True; busySeen := False; wideFly := io.wrWide }
  when(inFlight) {
    when(!io.wrComplete) { busySeen := True }
    when(busySeen && io.wrComplete) {
      inFlight := False
      when(wideFly) {
        batchCnt := 0
      } otherwise {                             // flushed single completed
        when(flushIdx === (batchCnt - 1).resize(3)) {
          flushing := False; batchCnt := 0; flushIdx := 0
        } otherwise { flushIdx := flushIdx + 1 }
      }
    }
  }
}
