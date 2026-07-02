package atari800

import spinal.core._

// 3-port SDRAM arbiter for the framebuffer path. Extends the proven 2-port
// SdramArbiter with a symmetric third port:
//   Port A (Atari core)  — highest priority, zero-latency combinational passthrough
//   Port B (fb-write)    — queued, served when A idle & SDRAM idle
//   Port C (fb-read)     — queued, served when A idle & SDRAM idle & B not pending
//
// B and C are latency-tolerant (VideoFbWrite/Read use a wait-for-COMPLETE
// handshake), so one-transaction-at-a-time queuing is fine. Only one of
// bActive/cActive is ever true. See SdramArbiter for the COMPLETE-protocol notes.
class SdramArbiter3 extends Component {
  val io = new Bundle {
    val a = new Bundle {              // Atari (priority passthrough)
      val request  = in  Bool();  val complete = out Bool()
      val readEnable = in Bool();  val writeEnable = in Bool()
      val addr = in Bits(24 bits); val dataIn = in Bits(32 bits); val dataOut = out Bits(32 bits)
      val byteAccess = in Bool();  val wordAccess = in Bool();  val longwordAccess = in Bool()
      val refresh = in Bool()
    }
    val b = new Bundle {             // fb-write (queued)
      val request  = in  Bool();  val complete = out Bool()
      val readEnable = in Bool();  val writeEnable = in Bool()
      val addr = in Bits(24 bits); val dataIn = in Bits(32 bits); val dataOut = out Bits(32 bits)
      val byteAccess = in Bool();  val wordAccess = in Bool();  val longwordAccess = in Bool()
    }
    val c = new Bundle {             // fb-read (queued)
      val request  = in  Bool();  val complete = out Bool()
      val readEnable = in Bool();  val writeEnable = in Bool()
      val addr = in Bits(24 bits); val dataIn = in Bits(32 bits); val dataOut = out Bits(32 bits)
      val byteAccess = in Bool();  val wordAccess = in Bool();  val longwordAccess = in Bool()
    }
    val sdram = new Bundle {         // to SdramStatemachine
      val request  = out Bool();  val complete = in Bool()
      val readEnable = out Bool(); val writeEnable = out Bool()
      val addr = out Bits(25 bits); val dataIn = out Bits(32 bits); val dataOut = in Bits(32 bits)
      val byteAccess = out Bool(); val wordAccess = out Bool(); val longwordAccess = out Bool()
      val refresh = out Bool()
    }
  }

  val bActive  = RegInit(False); val bPending = RegInit(False)
  val cActive  = RegInit(False); val cPending = RegInit(False)
  val aPending = RegInit(False)

  val sdramCompleteReg = RegNext(io.sdram.complete) init False

  val bReqPrev = RegNext(io.b.request) init False
  when(io.b.request && !bReqPrev) { bPending := True }
  val cReqPrev = RegNext(io.c.request) init False
  when(io.c.request && !cReqPrev) { cPending := True }

  io.a.dataOut := io.sdram.dataOut
  io.b.dataOut := io.sdram.dataOut
  io.c.dataOut := io.sdram.dataOut

  io.sdram.refresh := True

  // default: A on the bus
  io.sdram.request        := False
  io.sdram.addr           := B"0" ## io.a.addr
  io.sdram.dataIn         := io.a.dataIn
  io.sdram.readEnable     := io.a.readEnable
  io.sdram.writeEnable    := io.a.writeEnable
  io.sdram.byteAccess     := io.a.byteAccess
  io.sdram.wordAccess     := io.a.wordAccess
  io.sdram.longwordAccess := io.a.longwordAccess

  io.a.complete := !aPending & !io.a.request
  io.b.complete := False
  io.c.complete := False

  def driveB(): Unit = {
    io.sdram.addr := B"0" ## io.b.addr; io.sdram.dataIn := io.b.dataIn
    io.sdram.readEnable := io.b.readEnable; io.sdram.writeEnable := io.b.writeEnable
    io.sdram.byteAccess := io.b.byteAccess; io.sdram.wordAccess := io.b.wordAccess
    io.sdram.longwordAccess := io.b.longwordAccess
  }
  def driveC(): Unit = {
    io.sdram.addr := B"0" ## io.c.addr; io.sdram.dataIn := io.c.dataIn
    io.sdram.readEnable := io.c.readEnable; io.sdram.writeEnable := io.c.writeEnable
    io.sdram.byteAccess := io.c.byteAccess; io.sdram.wordAccess := io.c.wordAccess
    io.sdram.longwordAccess := io.c.longwordAccess
  }

  when(bActive) {
    driveB()
    when(io.a.request) { aPending := True }
    io.a.complete := !aPending & !io.a.request
    when(sdramCompleteReg) { io.b.complete := True; bActive := False }
  }.elsewhen(cActive) {
    driveC()
    when(io.a.request) { aPending := True }
    io.a.complete := !aPending & !io.a.request
    when(sdramCompleteReg) { io.c.complete := True; cActive := False }
  }.otherwise {
    io.sdram.request := io.a.request | aPending
    when(aPending && !io.a.request) { aPending := False }
    io.a.complete := io.sdram.complete

    val canServe = !io.a.request && !aPending && sdramCompleteReg
    when(bPending && canServe) {
      io.sdram.request := True; driveB()
      bPending := False; bActive := True
      io.a.complete := True
    }.elsewhen(cPending && canServe) {
      io.sdram.request := True; driveC()
      cPending := False; cActive := True
      io.a.complete := True
    }
  }
}
