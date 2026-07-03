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
  val addr = UInt(addrWidth bits)
  val data = Bits(8 bits)
}

class VideoFbWrite(
    fbBase:    Int = 0,
    width:     Int = 384,     // max active pixels captured per line
    strideLog2:Int = 9,       // bytes per line = 512
    height:    Int = 288,     // max active lines captured per frame
    addrWidth: Int = 25,
    fifoDepth: Int = 512,
    debugPattern: Boolean = false,  // write a checkerboard instead of io.colour
    debugFill:    Boolean = false   // free-running sweep, ignore all video inputs
) extends Component {
  val io = new Bundle {
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
    val wrByte     = out Bool()         // byte (8-bit) access
    val wrComplete = in  Bool()
    // ---- status ----
    val frameCount = out UInt(16 bits)
    val overflow   = out Bool()         // sticky: a pixel was dropped (FIFO full)
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
  io.frameCount := frameCount
  io.overflow   := overflow

  val fifo = StreamFifo(FbWriteReq(addrWidth), fifoDepth)

  val inFrame = x < width && y < height
  val armed = Reg(Bool()) init True
  val chk = Mux(x(5) ^ y(5), B(0x1A, 8 bits), B(0x86, 8 bits))
  fifo.io.push.payload.addr := (U(fbBase, addrWidth bits) + (y << strideLog2) + x).resize(addrWidth)

  if (debugFill) {
    // Free-running sweep of the whole framebuffer, ignoring ALL video inputs —
    // proves the SDRAM write -> read -> display path in isolation.
    fifo.io.push.valid        := True
    fifo.io.push.payload.data := chk
    when(fifo.io.push.ready) {
      when(x === (width - 1)) { x := 0; y := Mux(y === (height - 1), U(0), y + 1) }
        .otherwise { x := x + 1 }
    } otherwise { overflow := True }   // FIFO full => SDRAM writes not draining
  } else {
    val push = io.pixStrobe && !io.blank && inFrame
    fifo.io.push.valid        := push
    fifo.io.push.payload.data := (if (debugPattern) chk else io.colour)
    when(push) {
      when(fifo.io.push.ready) { x := x + 1 } otherwise { overflow := True }
    }
    when(hsRise) {
      x := 0
      when(armed) { armed := False }
        .elsewhen(y =/= (height - 1)) { y := y + 1 }
    }
    when(vsRise) { x := 0; y := 0; armed := True; frameCount := frameCount + 1 }
  }

  // ---- Drain side: pop FIFO -> issue SDRAM byte writes ----
  val inFlight = Reg(Bool()) init False
  val busySeen = Reg(Bool()) init False

  io.wrReq  := fifo.io.pop.valid && !inFlight
  io.wrByte := True
  io.wrAddr := fifo.io.pop.payload.addr.asBits
  io.wrData := fifo.io.pop.payload.data.resize(32)

  when(io.wrReq) { inFlight := True; busySeen := False }
  when(inFlight) {
    when(!io.wrComplete) { busySeen := True }
  }
  fifo.io.pop.ready := inFlight && busySeen && io.wrComplete
  when(fifo.io.pop.ready) { inFlight := False }
}
