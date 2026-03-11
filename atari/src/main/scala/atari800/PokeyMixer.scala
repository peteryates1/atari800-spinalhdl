package atari800

import spinal.core._

class PokeyMixer extends Component {
  val io = new Bundle {
    val channel0      = in  Bits(4 bits)
    val channel1      = in  Bits(4 bits)
    val channel2      = in  Bits(4 bits)
    val channel3      = in  Bits(4 bits)
    val gtiaSoundBit  = in  Bool()
    val sioAudio      = in  Bits(8 bits)
    val covoxChannel0 = in  Bits(8 bits)
    val covoxChannel1 = in  Bits(8 bits)
    val sidChannel0   = in  Bits(8 bits)
    val volumeOutNext = out Bits(16 bits)
  }

  val volumeSumReg = Reg(Bits(10 bits))
  val yadjReg      = Reg(SInt(32 bits))
  val y1Reg        = Reg(SInt(16 bits))

  // Volume sum computation
  // Each _long is 11 bits wide. VHDL sets specific bit ranges with rest being 0.
  val channel0Long  = (B"000" ## io.channel0 ## B"0000").asUInt.resize(11)
  val channel1Long  = (B"000" ## io.channel1 ## B"0000").asUInt.resize(11)
  val channel2Long  = (B"000" ## io.channel2 ## B"0000").asUInt.resize(11)
  val channel3Long  = (B"000" ## io.channel3 ## B"0000").asUInt.resize(11)
  val gtiaSoundLong = (B"000" ## io.gtiaSoundBit ## io.gtiaSoundBit ## io.gtiaSoundBit ## io.gtiaSoundBit ## B"0000").asUInt.resize(11)
  val sioAudioLong  = (B"000" ## io.sioAudio).asUInt.resize(11)
  val covox0Long    = (B"000" ## io.covoxChannel0).asUInt.resize(11)
  val covox1Long    = (B"000" ## io.covoxChannel1).asUInt.resize(11)
  val sid0Long      = (B"000" ## io.sidChannel0).asUInt.resize(11)

  val volumeIntSumWide = ((channel0Long + channel1Long) + (channel2Long + channel3Long)) +
                        ((gtiaSoundLong + sioAudioLong) + (covox0Long + covox1Long) + (sid0Long + sid0Long))
  val volumeIntSum = volumeIntSumWide.resize(11)

  // Saturation: if bit 10 is set, saturate to all 1s
  val saturate = volumeIntSum(10)
  val volumeSumNext = Mux(saturate, B"1111111111", volumeIntSum(9 downto 0).asBits)

  // Pipeline register
  volumeSumReg := volumeSumNext

  // Lookup table for piecewise linear interpolation
  // These are signed 16-bit values from the VHDL
  val lookupValues = Seq[Int](
    0x86E8 - 0x10000, 0x9E40 - 0x10000, 0xB3E3 - 0x10000, 0xC7E3 - 0x10000,
    0xDA52 - 0x10000, 0xEB42 - 0x10000, 0xFAC5 - 0x10000, 0x08ED,
    0x15CB, 0x2172, 0x2BF4, 0x3562, 0x3DCE, 0x454B, 0x4BEA, 0x51BD,
    0x56D6, 0x5B47, 0x5F22, 0x6278, 0x655C, 0x67E0, 0x6A15, 0x6C0D,
    0x6DDB, 0x6F90, 0x713E, 0x72F7, 0x74CD, 0x76D2, 0x7918, 0x7BB0,
    0x7EAD
  )

  val lookupMem = Vec(lookupValues.map(v => S(v, 16 bits)))

  val lookupIdx = volumeSumReg(9 downto 5).asUInt.resize(6)
  val y1  = lookupMem(lookupIdx)
  val y2  = lookupMem(lookupIdx + 1)
  val ych = y2 - y1

  // Multiplier for linear interpolation (matching entity work.mult_infer)
  val bIn = (B"00000000000" ## volumeSumReg(4 downto 0)).asSInt.resize(16)
  val yadjNext = (ych * bIn).resize(32)

  y1Reg   := y1
  yadjReg := yadjNext

  val volumeNext = (yadjReg(20 downto 5) + y1Reg).asBits

  io.volumeOutNext := volumeNext
}
