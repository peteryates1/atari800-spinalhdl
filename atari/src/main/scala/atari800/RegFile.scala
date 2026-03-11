package atari800

import spinal.core._

class RegFile(BYTES: Int = 1, WIDTH: Int = 1) extends Component {
  val io = new Bundle {
    val ADDR     = in  Bits(WIDTH bits)
    val DATA_IN  = in  Bits(8 bits)
    val WR_EN    = in  Bool()
    val DATA_OUT = out Bits(8 bits)
  }

  val decoder = new CompleteAddressDecoder(WIDTH)
  decoder.io.addrIn := io.ADDR
  val addrDecoded = decoder.io.addrDecoded

  val digitReg  = Vec(Reg(Bits(8 bits)), BYTES)
  val digitNext = Vec(Bits(8 bits), BYTES)

  for (i <- 0 until BYTES) {
    digitReg(i) := digitNext(i)
    digitNext(i) := digitReg(i)
  }

  // Next state logic
  when(io.WR_EN) {
    for (i <- 0 until BYTES) {
      when(addrDecoded(i)) {
        digitNext(i) := io.DATA_IN
      }
    }
  }

  // Output
  io.DATA_OUT := B"11111111"
  for (i <- 0 until BYTES) {
    when(addrDecoded(i)) {
      io.DATA_OUT := digitReg(i)
    }
  }
}
