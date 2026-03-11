package atari800

import spinal.core._

class WideDelayLine(COUNT: Int = 1, WIDTH: Int = 1) extends Component {
  val io = new Bundle {
    val syncReset = in  Bool()
    val dataIn    = in  Bits(WIDTH bits)
    val enable    = in  Bool()
    val dataOut   = out Bits(WIDTH bits)
  }

  val shiftReg  = Vec(Reg(Bits(WIDTH bits)) init B(0, WIDTH bits), COUNT)
  val shiftNext = Vec(Bits(WIDTH bits), COUNT)

  for (i <- 0 until COUNT) {
    shiftReg(i) := shiftNext(i)
    shiftNext(i) := shiftReg(i)
  }

  when(io.enable) {
    shiftNext(COUNT - 1) := io.dataIn
    for (i <- 0 until COUNT - 1) {
      shiftNext(i) := shiftReg(i + 1)
    }
  }

  when(io.syncReset) {
    for (i <- 0 until COUNT) {
      shiftNext(i) := B(0, WIDTH bits)
    }
  }

  io.dataOut := shiftReg(0)
}
