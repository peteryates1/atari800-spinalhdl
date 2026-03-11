package atari800

import spinal.core._

class PokeyPoly4 extends Component {
  val io = new Bundle {
    val enable = in  Bool()
    val init   = in  Bool()
    val bitOut = out Bool()
  }

  val shiftReg  = Reg(Bits(4 bits)) init B"1010"
  val shiftNext = Bits(4 bits)

  shiftReg := shiftNext

  shiftNext := shiftReg
  when(io.enable) {
    shiftNext := ((shiftReg(1) ^ shiftReg(0) ^ True) & ~io.init) ## shiftReg(3 downto 1)
  }

  io.bitOut := shiftReg(0)
}
