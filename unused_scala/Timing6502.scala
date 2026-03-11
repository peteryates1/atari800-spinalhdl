package atari800

import spinal.core._

class Timing6502(CYCLE_LENGTH: Int = 32, CONTROL_BITS: Int = 0) extends Component {
  val io = new Bundle {
    val CLK_ACTIVE        = null // not needed, SpinalHDL uses implicit clock

    val ENABLE_179_EARLY  = in  Bool()
    val REQUEST           = in  Bool()
    val ADDR_IN           = in  Bits(16 bits)
    val DATA_IN           = in  Bits(8 bits)
    val WRITE_IN          = in  Bool()
    val CONTROL_N_IN      = in  Bits(CONTROL_BITS bits)

    val DATA_OUT          = out Bits(8 bits)
    val COMPLETE          = out Bool()

    val BUS_DATA_IN       = in  Bits(8 bits)

    val BUS_PHI1          = out Bool()
    val BUS_PHI2          = out Bool()
    val BUS_SUBCYCLE      = out Bits(4 bits)
    val BUS_ADDR_OUT      = out Bits(16 bits)
    val BUS_ADDR_OE       = out Bool()
    val BUS_DATA_OUT      = out Bits(8 bits)
    val BUS_DATA_OE       = out Bool()
    val BUS_WRITE_N       = out Bool()
    val BUS_CONTROL_N     = out Bits(CONTROL_BITS bits)
    val BUS_CONTROL_OE    = out Bool()
  }

  val stateReg            = Reg(Bits(4 bits)) init B"0000"
  val oddReg              = Reg(Bool()) init False
  val addrReg             = Reg(Bits(16 bits)) init B(0, 16 bits)
  val addrOeReg           = Reg(Bool()) init False
  val dataReg             = Reg(Bits(8 bits)) init B(0, 8 bits)
  val dataOeReg           = Reg(Bool()) init False
  val dataReadReg         = Reg(Bits(8 bits)) init B(0, 8 bits)
  val phi1Reg             = Reg(Bool()) init False
  val phi2Reg             = Reg(Bool()) init False
  val writeNReg           = Reg(Bool()) init True
  val controlNReg         = Reg(Bits(CONTROL_BITS bits)) init B(CONTROL_BITS bits, default -> true)
  val controlOeReg        = Reg(Bool()) init False
  val completeReg         = Reg(Bool()) init False
  val requestPendingReg   = Reg(Bool()) init False
  val requestHandlingReg  = Reg(Bool()) init False

  val stateNext           = Bits(4 bits)
  val oddNext             = Bool()
  val addrNext            = Bits(16 bits)
  val addrOeNext          = Bool()
  val dataNext            = Bits(8 bits)
  val dataOeNext          = Bool()
  val dataReadNext        = Bits(8 bits)
  val phi1Next            = Bool()
  val phi2Next            = Bool()
  val writeNNext          = Bool()
  val controlNNext        = Bits(CONTROL_BITS bits)
  val controlOeNext       = Bool()
  val completeNext        = Bool()
  val requestPendingNext  = Bool()
  val requestHandlingNext = Bool()

  stateReg           := stateNext
  oddReg             := oddNext
  addrReg            := addrNext
  addrOeReg          := addrOeNext
  dataReg            := dataNext
  dataOeReg          := dataOeNext
  dataReadReg        := dataReadNext
  phi1Reg            := phi1Next
  phi2Reg            := phi2Next
  writeNReg          := writeNNext
  controlNReg        := controlNNext
  controlOeReg       := controlOeNext
  completeReg        := completeNext
  requestPendingReg  := requestPendingNext
  requestHandlingReg := requestHandlingNext

  // Next state (combinational defaults)
  stateNext           := stateReg
  oddNext             := ~oddReg
  phi1Next            := phi1Reg
  phi2Next            := phi2Reg
  addrNext            := addrReg
  addrOeNext          := addrOeReg
  dataNext            := dataReg
  dataOeNext          := dataOeReg
  completeNext        := False
  dataReadNext        := dataReadReg
  writeNNext          := writeNReg
  requestPendingNext  := requestPendingReg
  requestHandlingNext := requestHandlingReg
  controlNNext        := controlNReg
  controlOeNext       := controlOeReg

  when(io.ENABLE_179_EARLY) {
    stateNext := B"0000"
    oddNext   := True
  }

  if (CYCLE_LENGTH == 16) {
    stateNext := B(stateReg.asUInt + 1)
  } else {
    when(oddReg) {
      stateNext := B(stateReg.asUInt + 1)
    }
  }

  requestPendingNext := requestPendingReg | io.REQUEST

  switch(stateReg) {
    is(B"0000") {
      when(io.REQUEST | requestPendingReg) {
        addrNext            := io.ADDR_IN
        dataNext            := io.DATA_IN
        writeNNext          := ~io.WRITE_IN
        controlNNext        := io.CONTROL_N_IN
        requestPendingNext  := False
        requestHandlingNext := True
      }
    }
    is(B"0001") {
      addrOeNext    := True
      controlOeNext := True
    }
    is(B"0010", B"0011", B"0100", B"0101") {
      // nothing
    }
    is(B"0110") {
      phi1Next := False
    }
    is(B"0111") {
      phi2Next := True
    }
    is(B"1000") {
      // nothing
    }
    is(B"1001", B"1010") {
      // nothing
    }
    is(B"1011") {
      when(io.WRITE_IN) {
        dataOeNext := True
      }
    }
    is(B"1100") {
      // nothing
    }
    is(B"1101") {
      // nothing
    }
    is(B"1110") {
      completeNext        := requestHandlingReg
      requestHandlingNext := False
      dataReadNext        := io.BUS_DATA_IN
      phi2Next            := False
    }
    is(B"1111") {
      addrNext      := B(0, 16 bits)
      addrOeNext    := False
      controlNNext  := B(CONTROL_BITS bits, default -> true)
      controlOeNext := False
      dataOeNext    := False
      writeNNext    := True
      phi1Next      := True
    }
  }

  // Outputs
  io.BUS_SUBCYCLE  := stateReg
  io.BUS_PHI1      := phi1Reg
  io.BUS_PHI2      := phi2Reg
  io.BUS_ADDR_OUT  := addrReg
  io.BUS_ADDR_OE   := addrOeReg
  io.BUS_DATA_OUT  := dataReg
  io.BUS_DATA_OE   := dataOeReg
  io.BUS_WRITE_N   := writeNReg
  io.BUS_CONTROL_N := controlNReg
  io.BUS_CONTROL_OE := controlOeReg

  io.DATA_OUT      := dataReadReg
  io.COMPLETE      := completeReg
}
