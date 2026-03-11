package atari800

import spinal.core._

class PokeyCountdownTimer(UNDERFLOW_DELAY: Int = 3) extends Component {
  val io = new Bundle {
    val enable          = in  Bool()
    val enableUnderflow = in  Bool()
    val wrEn            = in  Bool()
    val dataIn          = in  Bits(8 bits)
    val dataOut         = out Bool()
  }

  // Instantiate delay line (provides output)
  val underflow0Delay = new DelayLine(UNDERFLOW_DELAY)
  underflow0Delay.io.syncReset := io.wrEn
  underflow0Delay.io.enable    := io.enableUnderflow

  val countReg  = Reg(Bits(8 bits)) init B(0, 8 bits)
  val countNext = Bits(8 bits)

  countReg := countNext

  // Count down on enable
  val countCommand = io.enable ## io.wrEn

  switch(countCommand) {
    is(B"10") {
      countNext := B(countReg.asUInt - 1)
    }
    is(B"01", B"11") {
      countNext := io.dataIn
    }
    default {
      countNext := countReg
    }
  }

  // Underflow
  val underflow = Bool()
  val underflowCommand = io.enable ## (countReg === B(0, 8 bits)).asBits

  switch(underflowCommand) {
    is(B"11") {
      underflow := True
    }
    default {
      underflow := False
    }
  }

  underflow0Delay.io.dataIn := underflow
  io.dataOut := underflow0Delay.io.dataOut
}
