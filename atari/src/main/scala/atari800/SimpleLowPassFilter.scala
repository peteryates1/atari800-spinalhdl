package atari800

import spinal.core._

class SimpleLowPassFilter extends Component {
  val io = new Bundle {
    val audioIn  = in  Bits(16 bits)
    val sampleIn = in  Bool()
    val audioOut = out Bits(16 bits)
  }

  val accumReg  = Reg(SInt(21 bits)) init S(0, 21 bits)
  val accum2Reg = Reg(SInt(21 bits)) init S(0, 21 bits)

  // First stage: diff/16
  val adjust = (io.audioIn.asSInt.resize(17) @@ S"0000").resize(21) - accumReg

  when(io.sampleIn) {
    accumReg := accumReg + (adjust >> 4).resize(21)
  }

  // Second stage: diff/8
  val adjust2 = accumReg - accum2Reg

  when(io.sampleIn) {
    accum2Reg := accum2Reg + (adjust2 >> 3).resize(21)
  }

  io.audioOut := accum2Reg(19 downto 4).asBits
}
