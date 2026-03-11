package atari800

import spinal.core._

class SvideoGtia extends Component {
  val io = new Bundle {
    val brightness = in  Bits(4 bits)
    val hue        = in  Bits(4 bits)
    val burst      = in  Bool()
    val blank      = in  Bool()
    val sof        = in  Bool()
    val csyncN     = in  Bool()
    val vposLsb    = in  Bool()
    val pal        = in  Bool()

    val composite  = in  Bool()

    val chroma     = out Bits(8 bits)
    val luma       = out Bits(8 bits) // or composite
    val lumaSyncN  = out Bool()
  }

  // regs
  val chromaReg     = Reg(Bits(8 bits)) init B(0, 8 bits)
  val lumaReg       = Reg(Bits(8 bits)) init B(0, 8 bits)
  val phaseCountReg = Reg(Bits(8 bits)) init B(0, 8 bits)

  val chromaNext     = Bits(8 bits)
  val lumaNext       = Bits(8 bits)
  val phaseCountNext = Bits(8 bits)

  chromaReg     := chromaNext
  lumaReg       := lumaNext
  phaseCountReg := phaseCountNext

  // next state - phase counter
  phaseCountNext := B(0, 8 bits)
  when(io.sof) {
    phaseCountNext := B(0, 8 bits)
  } otherwise {
    when(io.pal) {
      phaseCountNext := B(phaseCountReg.asUInt + 20)
    } otherwise {
      phaseCountNext := B(phaseCountReg.asUInt + 16)
    }
  }

  // hue adjustment
  val hueAdj   = Bits(4 bits)
  val hueDelay = Bits(4 bits)
  val hueUse   = Bits(4 bits)

  hueAdj := B"0000"
  hueUse := io.hue

  when(io.burst) {
    hueUse := B"0001"
  }

  when(io.pal) {
    when(hueUse.asUInt > 6) {
      hueAdj := B"000" ## io.pal.asBits
    }
    when(hueUse.asUInt > 10) {
      hueAdj := B"00" ## io.pal.asBits ## B"0"
    }
    when(io.vposLsb) {
      hueDelay := B(U(0, 4 bits) - hueUse.asUInt - hueAdj.asUInt)
    } otherwise {
      hueDelay := B(U(2, 4 bits) + hueUse.asUInt + hueAdj.asUInt)
    }
  } otherwise {
    hueDelay := B(U(0, 4 bits) - hueUse.asUInt)
  }

  // colour shift
  val colourShift = Bits(8 bits)
  colourShift := hueDelay ## B"0000"
  when(~io.pal) {
    colourShift := B((hueDelay ## B"0000").asUInt + (B"000" ## hueDelay ## B"0").asUInt)
  }

  // base shift
  val baseShift = Bits(8 bits)
  when(io.pal) {
    baseShift := B(112, 8 bits) // 157.5 degrees
  } otherwise {
    baseShift := B(248, 8 bits) // -12 degrees
  }

  // sin phase calculation
  val sinPhase       = Bits(8 bits)
  val sinMovingPhase = Bits(8 bits)
  val sinOn          = Bool()

  sinPhase := B(baseShift.asUInt + colourShift.asUInt)
  sinMovingPhase := B(phaseCountReg.asUInt + sinPhase.asUInt)

  sinOn := False
  when(io.blank) {
    sinOn := io.burst
  } otherwise {
    sinOn := io.hue.orR
  }

  // sine lookup table - outputs signed -44 to 44
  val sinShifted = SInt(8 bits)
  sinShifted := S(0, 8 bits)

  val sinX = Bits(6 bits)
  when(~sinMovingPhase(6)) {
    sinX := sinMovingPhase(5 downto 0)
  } otherwise {
    sinX := B(U(63, 6 bits) - sinMovingPhase(5 downto 0).asUInt)
  }

  val lookup = Vec(Bits(8 bits), 64)
  lookup( 0) := B"x01"; lookup( 1) := B"x02"; lookup( 2) := B"x03"; lookup( 3) := B"x04"
  lookup( 4) := B"x05"; lookup( 5) := B"x06"; lookup( 6) := B"x08"; lookup( 7) := B"x09"
  lookup( 8) := B"x0a"; lookup( 9) := B"x0b"; lookup(10) := B"x0c"; lookup(11) := B"x0d"
  lookup(12) := B"x0e"; lookup(13) := B"x0f"; lookup(14) := B"x10"; lookup(15) := B"x11"
  lookup(16) := B"x12"; lookup(17) := B"x13"; lookup(18) := B"x14"; lookup(19) := B"x15"
  lookup(20) := B"x16"; lookup(21) := B"x17"; lookup(22) := B"x18"; lookup(23) := B"x18"
  lookup(24) := B"x19"; lookup(25) := B"x1a"; lookup(26) := B"x1b"; lookup(27) := B"x1c"
  lookup(28) := B"x1d"; lookup(29) := B"x1e"; lookup(30) := B"x1e"; lookup(31) := B"x1f"
  lookup(32) := B"x20"; lookup(33) := B"x21"; lookup(34) := B"x21"; lookup(35) := B"x22"
  lookup(36) := B"x23"; lookup(37) := B"x23"; lookup(38) := B"x24"; lookup(39) := B"x25"
  lookup(40) := B"x25"; lookup(41) := B"x26"; lookup(42) := B"x26"; lookup(43) := B"x27"
  lookup(44) := B"x27"; lookup(45) := B"x28"; lookup(46) := B"x28"; lookup(47) := B"x29"
  lookup(48) := B"x29"; lookup(49) := B"x29"; lookup(50) := B"x2a"; lookup(51) := B"x2a"
  lookup(52) := B"x2a"; lookup(53) := B"x2b"; lookup(54) := B"x2b"; lookup(55) := B"x2b"
  lookup(56) := B"x2b"; lookup(57) := B"x2c"; lookup(58) := B"x2c"; lookup(59) := B"x2c"
  lookup(60) := B"x2c"; lookup(61) := B"x2c"; lookup(62) := B"x2c"; lookup(63) := B"x2c"

  val sinY = lookup(sinX.asUInt)
  when(sinOn) {
    when(~sinMovingPhase(7)) {
      sinShifted := sinY.asSInt
    } otherwise {
      sinShifted := -sinY.asSInt
    }
  }

  // brightness scaling
  val brightnessScaled = Bits(8 bits)
  brightnessScaled := B(0, 8 bits)
  when(~io.blank) {
    // multiply by 11: x + 2x + 8x
    brightnessScaled := B(
      (B"0000" ## io.brightness).asUInt +
      (B"000" ## io.brightness ## B"0").asUInt +
      (B"0" ## io.brightness ## B"000").asUInt
    )
  }

  // output mux
  chromaNext := B(sinShifted + S"x2c")
  when(~io.composite) {
    lumaNext := brightnessScaled
  } otherwise {
    lumaNext := B(brightnessScaled.asUInt + chromaNext.asUInt)
  }

  // outputs
  io.lumaSyncN := io.csyncN
  io.luma      := lumaReg
  io.chroma    := chromaReg
}
