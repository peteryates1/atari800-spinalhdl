package atari800

import spinal.core._

class EnableDivider(COUNT: Int = 1) extends Component {
  val io = new Bundle {
    val enableIn  = in  Bool()
    val enableOut = out Bool()
  }

  def log2c(n: Int): Int = {
    var m = 0
    var p = 1
    while (p < n) { m += 1; p *= 2 }
    m
  }

  val WIDTH = log2c(COUNT)

  val countReg      = Reg(UInt(WIDTH bits)) init U(COUNT - 1, WIDTH bits)
  val enabledOutReg = Reg(Bool()) init False

  val countNext      = UInt(WIDTH bits)
  val enabledOutNext = Bool()

  countReg      := countNext
  enabledOutReg := enabledOutNext

  countNext      := countReg
  enabledOutNext := enabledOutReg

  when(io.enableIn) {
    countNext      := countReg + 1
    enabledOutNext := False

    when(countReg === U(COUNT - 1, WIDTH bits)) {
      countNext      := U(0, WIDTH bits)
      enabledOutNext := True
    }
  }

  io.enableOut := enabledOutReg & io.enableIn
}
