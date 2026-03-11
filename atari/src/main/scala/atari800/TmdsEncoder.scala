package atari800

import spinal.core._

// TMDS 8b/10b Encoder
// Implements the DVI 1.0 / HDMI TMDS encoding algorithm
// Converts 8-bit data + 2 control bits to 10-bit TMDS symbol
class TmdsEncoder extends Component {
  val io = new Bundle {
    val data    = in  Bits(8 bits)
    val ctrl    = in  Bits(2 bits)
    val dataEn  = in  Bool()
    val tmdsOut = out Bits(10 bits)
  }

  // Count ones in a bit vector
  def countOnes(v: Bits): UInt = {
    v.asBools.map(_.asUInt(4 bits)).reduce(_ + _)
  }

  // Stage 1: minimize transitions (XOR or XNOR based on ones count)
  val qM = Bits(9 bits)
  val n1 = countOnes(io.data)

  val qCalc = Bits(9 bits)
  val useXnor = (n1 > 4) || (n1 === 4 && !io.data(0))

  // Build transition-minimized word
  val q = Vec(Bool(), 9)
  q(0) := io.data(0)
  for (i <- 1 to 7) {
    q(i) := Mux(useXnor, ~(q(i-1) ^ io.data(i)), q(i-1) ^ io.data(i))
  }
  q(8) := ~useXnor

  qM := q.asBits

  // Stage 2: DC balance (registered)
  val dcBias  = Reg(SInt(4 bits)) init 0
  val outWord = Reg(Bits(10 bits)) init 0

  val n1Qm = countOnes(qM(7 downto 0))
  val n0Qm = U(8) - n1Qm
  val diff  = (n1Qm - n0Qm).asSInt.resize(4 bits)

  when(!io.dataEn) {
    // Control period: send control tokens
    dcBias := 0
    switch(io.ctrl) {
      is(B"00") { outWord := B"1101010100" }
      is(B"01") { outWord := B"0010101011" }
      is(B"10") { outWord := B"0101010100" }
      default   { outWord := B"1010101011" }
    }
  } otherwise {
    when(dcBias === 0 || diff === 0) {
      outWord(9) := ~qM(8)
      outWord(8) := qM(8)
      when(!qM(8)) {
        outWord(7 downto 0) := ~qM(7 downto 0)
        dcBias := dcBias - diff
      } otherwise {
        outWord(7 downto 0) := qM(7 downto 0)
        dcBias := dcBias + diff
      }
    } elsewhen ((dcBias > 0 && diff > 0) || (dcBias < 0 && diff < 0)) {
      outWord(9) := True
      outWord(8) := qM(8)
      outWord(7 downto 0) := ~qM(7 downto 0)
      when(qM(8)) {
        dcBias := dcBias - diff + 2
      } otherwise {
        dcBias := dcBias - diff
      }
    } otherwise {
      outWord(9) := False
      outWord(8) := qM(8)
      outWord(7 downto 0) := qM(7 downto 0)
      when(!qM(8)) {
        dcBias := dcBias + diff - 2
      } otherwise {
        dcBias := dcBias + diff
      }
    }
  }

  io.tmdsOut := outWord
}
