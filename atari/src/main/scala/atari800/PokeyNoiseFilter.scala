package atari800

import spinal.core._

class PokeyNoiseFilter extends Component {
  val io = new Bundle {
    val noiseSelect = in  Bits(3 bits)
    val pulseIn     = in  Bool()
    val noise4      = in  Bool()
    val noise5      = in  Bool()
    val noiseLarge  = in  Bool()
    val syncReset   = in  Bool()
    val pulseOut    = out Bool()
  }

  val outReg  = Reg(Bool()) init False
  val outNext = Bool()

  outReg := outNext

  val audclk = Bool()

  audclk  := io.pulseIn
  outNext := outReg

  when(~io.noiseSelect(2)) {
    audclk := io.pulseIn & io.noise5
  }

  when(audclk) {
    when(io.noiseSelect(0)) {
      // toggle
      outNext := ~outReg
    } otherwise {
      // sample
      when(io.noiseSelect(1)) {
        outNext := io.noise4
      } otherwise {
        outNext := io.noiseLarge
      }
    }
  }

  when(io.syncReset) {
    outNext := False
  }

  io.pulseOut := outReg
}
