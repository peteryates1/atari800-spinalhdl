package atari800

import spinal.core._

// I2S Transmitter
// Generates I2S audio output for WM8960 codec
// Generates MCLK, BCLK, LRCLK, and DACDAT
class I2sTx(mclkDiv: Int = 2, audioBits: Int = 16) extends Component {
  val io = new Bundle {
    val leftIn   = in  Bits(audioBits bits)
    val rightIn  = in  Bits(audioBits bits)
    val i2sMclk  = out Bool()
    val i2sBclk  = out Bool()
    val i2sLrclk = out Bool()
    val i2sDout  = out Bool()
  }

  val mclkCnt  = Reg(UInt(8 bits)) init 0
  val mclkReg  = Reg(Bool()) init False
  val bclkCnt  = Reg(UInt(2 bits)) init 0
  val bclkReg  = Reg(Bool()) init False
  val bclkPrev = Reg(Bool()) init False
  val bitCnt   = Reg(UInt(6 bits)) init 0
  val lrclkReg = Reg(Bool()) init False
  val shiftReg = Reg(Bits(32 bits)) init 0
  val leftLat  = Reg(Bits(audioBits bits)) init 0
  val rightLat = Reg(Bits(audioBits bits)) init 0

  io.i2sMclk  := mclkReg
  io.i2sBclk  := bclkReg
  io.i2sLrclk := lrclkReg
  io.i2sDout  := shiftReg(31)

  // Generate MCLK
  when(mclkCnt === mclkDiv - 1) {
    mclkCnt := 0
    mclkReg := ~mclkReg

    // Generate BCLK from MCLK (divide by 4)
    when(mclkReg) {  // on MCLK falling edge
      bclkCnt := bclkCnt + 1
      when(bclkCnt === 1) {
        bclkReg := ~bclkReg
      }
    }
  } otherwise {
    mclkCnt := mclkCnt + 1
  }

  // Detect BCLK falling edge for shifting data
  bclkPrev := bclkReg
  when(bclkPrev && ~bclkReg) {
    bitCnt := bitCnt + 1

    when(bitCnt === 63) {
      // Start of left channel
      lrclkReg := False
      leftLat  := io.leftIn
      rightLat := io.rightIn
      shiftReg := leftLat ## B(0, 16 bits)
    } elsewhen (bitCnt === 31) {
      // Start of right channel
      lrclkReg := True
      shiftReg := rightLat ## B(0, 16 bits)
    } otherwise {
      shiftReg := shiftReg(30 downto 0) ## False
    }
  }
}
