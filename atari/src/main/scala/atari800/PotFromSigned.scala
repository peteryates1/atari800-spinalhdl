package atari800

import spinal.core._

class PotFromSigned(
  cycleLength: Int = 32,
  lineLength: Int = 114,
  minLines: Int = 0,
  maxLines: Int = 227,
  reverse: Int = 0,
  forceTo: Int = 100
) extends Component {
  val io = new Bundle {
    val enabled   = in  Bool()
    val potReset  = in  Bool()
    val pos       = in  SInt(8 bits)
    val forceLow  = in  Bool()
    val forceHigh = in  Bool()
    val potHigh   = out Bool()
  }

  val countCycles = (maxLines - minLines) * lineLength / 256
  val offsetConst = lineLength * minLines / countCycles

  val enable179Div = new EnableDivider(cycleLength)
  enable179Div.io.enableIn := True
  val enable179 = enable179Div.io.enableOut

  val potClockDiv = new EnableDivider(countCycles)
  potClockDiv.io.enableIn := enable179
  val countEnable = potClockDiv.io.enableOut

  val countReg  = Reg(UInt(10 bits)) init U(0, 10 bits)
  val potOutReg = Reg(Bool()) init False

  val countNext  = UInt(10 bits)
  val potOutNext = Bool()

  countReg  := countNext
  potOutReg := potOutNext

  // Position adjustment
  val pos2 = SInt(8 bits)
  pos2 := io.pos
  val absPos = SInt(8 bits)
  absPos := Mux(io.pos < 0, -io.pos, io.pos)
  when(absPos < 16 && io.forceLow) {
    pos2 := S(-forceTo, 8 bits)
  }
  when(absPos < 16 && io.forceHigh) {
    pos2 := S(forceTo, 8 bits)
  }

  countNext := countReg

  // Reset: compute initial count based on position
  // VHDL: to_unsigned(to_integer(pos2)+128+offset, 10)  or  to_unsigned(-to_integer(pos2)+127+offset, 10)
  val posWide = pos2.resize(11)
  val loadValue = if (reverse == 1) {
    (-posWide + (127 + offsetConst))
  } else {
    (posWide + (128 + offsetConst))
  }

  when(io.potReset || ~io.enabled) {
    countNext := loadValue(9 downto 0).asUInt
  }

  when(countEnable) {
    when(~potOutNext) {
      countNext := countReg - 1
    }
  }

  potOutNext := ~countReg.orR

  io.potHigh := potOutReg
}
