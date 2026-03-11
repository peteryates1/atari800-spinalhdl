package atari800

import spinal.core._

class DelayLine(COUNT: Int = 1) extends Component {
  val io = new Bundle {
    val syncReset = in  Bool()
    val dataIn    = in  Bool()
    val enable    = in  Bool()
    val dataOut   = out Bool()
  }

  val shiftReg  = Reg(Bits(COUNT bits)) init B(0, COUNT bits)
  val shiftNext = Bits(COUNT bits)

  shiftReg := shiftNext

  shiftNext := shiftReg

  when(io.enable) {
    shiftNext := io.dataIn ## shiftReg(COUNT - 1 downto 1)
  }

  when(io.syncReset) {
    shiftNext := B(0, COUNT bits)
  }

  io.dataOut := shiftReg(0) & io.enable
}
