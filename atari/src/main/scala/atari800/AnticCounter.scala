package atari800

import spinal.core._

class AnticCounter(STORE_WIDTH: Int = 1, COUNT_WIDTH: Int = 1) extends Component {
  val io = new Bundle {
    val increment    = in  Bool()
    val load         = in  Bool()
    val loadValue    = in  Bits(STORE_WIDTH bits)
    val currentValue = out Bits(STORE_WIDTH bits)
  }

  val valueReg  = Reg(Bits(STORE_WIDTH bits)) init B(0, STORE_WIDTH bits)
  val valueNext = Bits(STORE_WIDTH bits)

  valueReg  := valueNext
  valueNext := valueReg

  when(io.increment) {
    valueNext := valueReg(STORE_WIDTH - 1 downto COUNT_WIDTH) ## B(valueReg(COUNT_WIDTH - 1 downto 0).asUInt + 1)
  }

  when(io.load) {
    valueNext := io.loadValue
  }

  io.currentValue := valueReg
}
