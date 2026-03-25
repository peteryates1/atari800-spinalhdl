package atari800

import spinal.core._
import spinal.lib._

// Per-bit debounce: 2-FF synchronizer (BufferCC) + counter-based stable detection.
// When input is stable for 2^stableBits cycles, output updates.
// At 56.67 MHz with stableBits=20: 2^20 / 56.67e6 = ~18.4 ms.
// Default output: all-ones (pull-up, nothing pressed for active-low inputs).
class Debounce(width: Int, stableBits: Int = 16) extends Component {
  val io = new Bundle {
    val raw       = in  Bits(width bits)
    val debounced = out Bits(width bits)
  }

  val synced = BufferCC(io.raw)
  val output = Reg(Bits(width bits)) init B((1 << width) - 1, width bits)

  for (i <- 0 until width) {
    val counter = Reg(UInt(stableBits bits)) init 0
    when(synced(i) =/= output(i)) {
      counter := counter + 1
      when(counter.andR) {
        output(i) := synced(i)
        counter := 0
      }
    }.otherwise {
      counter := 0
    }
  }

  io.debounced := output
}
