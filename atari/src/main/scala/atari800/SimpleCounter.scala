package atari800

import spinal.core._

class SimpleCounter(COUNT_WIDTH: Int = 1) extends Component {
  val io = new Bundle {
    val increment    = in  Bool()
    val load         = in  Bool()
    val loadValue    = in  Bits(COUNT_WIDTH bits)
    val currentValue = out Bits(COUNT_WIDTH bits)
  }

  val valueReg  = Reg(Bits(COUNT_WIDTH bits)) init B(0, COUNT_WIDTH bits)
  val valueNext = Bits(COUNT_WIDTH bits)

  valueReg := valueNext

  valueNext := valueReg

  when(io.increment) {
    valueNext := B(valueReg.asUInt + 1)
  }

  when(io.load) {
    valueNext := io.loadValue
  }

  io.currentValue := valueReg
}
