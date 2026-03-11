package atari800

import spinal.core._

class Synchronizer extends Component {
  val io = new Bundle {
    val raw  = in  Bool()
    val sync = out Bool()
  }

  val ffReg  = Reg(Bits(3 bits))
  val ffNext = Bits(3 bits)

  ffReg := ffNext

  ffNext := io.raw ## ffReg(2 downto 1)

  io.sync := ffReg(0)
}
