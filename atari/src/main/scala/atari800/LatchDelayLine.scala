package atari800

import spinal.core._

class LatchDelayLine(COUNT: Int = 1) extends Component {
  val io = new Bundle {
    val syncReset = in  Bool()
    val dataIn    = in  Bool()
    val enable    = in  Bool()
    val dataOut   = out Bool()
  }

  val shiftReg   = Reg(Bits(COUNT bits)) init B(0, COUNT bits)
  val dataInReg  = Reg(Bool()) init False

  val shiftNext  = Bits(COUNT bits)
  val dataInNext = Bool()

  shiftReg  := shiftNext
  dataInReg := dataInNext

  shiftNext  := shiftReg
  dataInNext := io.dataIn | dataInReg

  when(io.enable) {
    shiftNext  := (io.dataIn | dataInReg) ## shiftReg(COUNT - 1 downto 1)
    dataInNext := False
  }

  when(io.syncReset) {
    shiftNext  := B(0, COUNT bits)
    dataInNext := False
  }

  io.dataOut := shiftReg(0) & io.enable
}
