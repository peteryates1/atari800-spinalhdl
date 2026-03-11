package atari800

import spinal.core._

class PokeyPoly5 extends Component {
  val io = new Bundle {
    val enable = in  Bool()
    val init   = in  Bool()
    val bitOut = out Bool()
  }

  val shiftReg  = Reg(Bits(5 bits)) init B"01010"
  val shiftNext = Bits(5 bits)

  shiftReg := shiftNext

  shiftNext := shiftReg
  when(io.enable) {
    shiftNext := ((shiftReg(2) ^ shiftReg(0) ^ True) & ~io.init) ## shiftReg(4 downto 1)
  }

  io.bitOut := shiftReg(0)
}
