package atari800

import spinal.core._

// First-order sigma-delta modulator
// Converts 16-bit signed audio to 1-bit PWM output
// Use RC low-pass filter on output (e.g. 1K + 100nF -> 1.6kHz cutoff)
class AudioPwm extends Component {
  val io = new Bundle {
    val audioIn = in  Bits(16 bits)
    val pwmOut  = out Bool()
  }

  // Convert signed to unsigned (flip MSB)
  val audioUnsigned = io.audioIn.asUInt ^ U(0x8000, 16 bits)

  // First-order sigma-delta accumulator
  val accumulator = Reg(UInt(17 bits)) init 0

  accumulator := (U"1'd0" @@ accumulator(15 downto 0)) + (U"1'd0" @@ audioUnsigned)

  io.pwmOut := accumulator(16)
}
