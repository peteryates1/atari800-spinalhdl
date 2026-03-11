package atari800

import spinal.core._

class PokeyPoly17_9 extends Component {
  val io = new Bundle {
    val enable     = in  Bool()
    val select9_17 = in  Bool()  // 9 high, 17 low
    val init       = in  Bool()
    val bitOut     = out Bool()
    val randOut    = out Bits(8 bits)
  }

  val shiftReg        = Reg(Bits(17 bits)) init B"01010101010101010"
  val cycleDelayReg   = Reg(Bool()) init False
  val select9_17DelReg = Reg(Bool()) init False

  val shiftNext         = Bits(17 bits)
  val cycleDelayNext    = Bool()
  val select9_17DelNext = Bool()

  shiftReg         := shiftNext
  cycleDelayReg    := cycleDelayNext
  select9_17DelReg := select9_17DelNext

  val feedback = shiftReg(13) ^ shiftReg(8) ^ True  // xnor

  shiftNext         := shiftReg
  cycleDelayNext    := cycleDelayReg
  select9_17DelNext := select9_17DelReg

  when(io.enable) {
    select9_17DelNext := io.select9_17
    shiftNext(15 downto 8) := shiftReg(16 downto 9)
    shiftNext(7)           := feedback
    shiftNext(6 downto 0)  := shiftReg(7 downto 1)

    shiftNext(16) := ((feedback & select9_17DelReg) | (shiftReg(0) & ~io.select9_17)) & ~io.init

    cycleDelayNext := shiftReg(9)
  }

  io.bitOut  := cycleDelayReg
  io.randOut := ~shiftReg(15 downto 8)
}
