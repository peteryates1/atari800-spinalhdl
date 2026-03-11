package atari800

import spinal.core._
import spinal.lib._
import spinal.lib.bus.bmb._

// Bridge: JOP BMB master → Atari SDRAM request protocol (SdramArbiter port B)
//
// Converts BMB read/write commands into single-cycle REQUEST pulses with
// COMPLETE handshake.  Supports burst reads (multi-word) for JOP method
// cache fills.  All accesses are 32-bit longword (JOP is 32-bit aligned).
//
// Address mapping: BMB byte address is passed through directly.
// The top-level should ensure JOP's address space maps to the correct
// SDRAM region (e.g. 0x800000+).
case class BmbToSdramReq(bmbParameter: BmbParameter) extends Component {
  val io = new Bundle {
    val bmb = slave(Bmb(bmbParameter))

    // To SdramArbiter port B
    val request        = out Bool()
    val complete       = in  Bool()
    val readEnable     = out Bool()
    val writeEnable    = out Bool()
    val addr           = out Bits(24 bits)  // byte address
    val dataIn         = out Bits(32 bits)  // write data to SDRAM
    val dataOut        = in  Bits(32 bits)  // read data from SDRAM
    val byteAccess     = out Bool()
    val wordAccess     = out Bool()
    val longwordAccess = out Bool()
  }

  val IDLE     = B"00"
  val REQUEST  = B"01"
  val WAIT     = B"10"

  val state = RegInit(IDLE)

  // Burst tracking
  val isWrite      = Reg(Bool()) init False
  val burstLeft    = Reg(UInt(bmbParameter.access.lengthWidth bits)) init 0
  val currentAddr  = Reg(UInt(bmbParameter.access.addressWidth bits)) init 0
  val writeData    = Reg(Bits(32 bits)) init 0
  val source       = Reg(UInt(bmbParameter.access.sourceWidth bits)) init 0
  val context      = Reg(Bits(bmbParameter.access.contextWidth bits)) init 0

  // Defaults
  io.request        := False
  io.readEnable     := False
  io.writeEnable    := False
  io.addr           := currentAddr.asBits.resized
  io.dataIn         := writeData
  io.byteAccess     := False
  io.wordAccess     := False
  io.longwordAccess := True  // JOP always does 32-bit access

  io.bmb.cmd.ready  := False
  io.bmb.rsp.valid  := False
  io.bmb.rsp.data   := io.dataOut
  io.bmb.rsp.source := source
  io.bmb.rsp.context := context
  io.bmb.rsp.setSuccess()
  io.bmb.rsp.last   := (burstLeft <= 1)

  switch(state) {
    is(IDLE) {
      when(io.bmb.cmd.valid) {
        // Latch command
        isWrite     := io.bmb.cmd.isWrite
        currentAddr := io.bmb.cmd.address
        writeData   := io.bmb.cmd.data
        source      := io.bmb.cmd.source
        context     := io.bmb.cmd.context

        // Calculate burst word count: (length + 1) / 4
        burstLeft := ((io.bmb.cmd.length +^ 1) >> 2).resized

        // For writes, consume BMB cmd now (one beat at a time)
        // For reads, consume BMB cmd immediately (we know the full burst)
        io.bmb.cmd.ready := True

        state := REQUEST
      }
    }

    is(REQUEST) {
      // Pulse REQUEST for one cycle
      io.request     := True
      io.readEnable  := !isWrite
      io.writeEnable := isWrite
      io.addr        := currentAddr.asBits.resized

      state := WAIT
    }

    is(WAIT) {
      // Hold read/write enables stable while SDRAM processes
      io.readEnable  := !isWrite
      io.writeEnable := isWrite

      when(io.complete) {
        when(!isWrite) {
          // Read complete — send BMB response
          io.bmb.rsp.valid := True
          io.bmb.rsp.last  := (burstLeft <= 1)
          when(io.bmb.rsp.ready) {
            burstLeft   := burstLeft - 1
            currentAddr := currentAddr + 4
            when(burstLeft <= 1) {
              state := IDLE
            }.otherwise {
              state := REQUEST  // next burst word
            }
          }
        }.otherwise {
          // Write complete
          burstLeft   := burstLeft - 1
          currentAddr := currentAddr + 4
          when(burstLeft <= 1) {
            // Last write done — send write response
            io.bmb.rsp.valid := True
            io.bmb.rsp.last  := True
            when(io.bmb.rsp.ready) {
              state := IDLE
            }
          }.otherwise {
            // More write beats — consume next BMB cmd beat
            io.bmb.cmd.ready := True
            writeData := io.bmb.cmd.data
            state := REQUEST
          }
        }
      }
    }
  }
}
