package atari800

import spinal.core._

class CompleteAddressDecoder(width: Int = 1) extends Component {
  val io = new Bundle {
    val addrIn      = in  Bits(width bits)
    val addrDecoded = out Bits((1 << width) bits)
  }

  // Tree decoder matching the VHDL architecture
  val STAGE = width
  // p(stage)(index) - 2D array of signals
  val p = Array.fill(STAGE + 1)(Vec(Bool(), 1 << STAGE))

  val a = io.addrIn

  p(STAGE)(0) := True
  for (i <- 1 until (1 << STAGE)) {
    p(STAGE)(i) := False  // unused entries
  }

  for (s <- STAGE until 0 by -1) {
    for (r <- 0 until (1 << (STAGE - s))) {
      p(s - 1)(2 * r)     := ~a(s - 1) & p(s)(r)
      p(s - 1)(2 * r + 1) := a(s - 1) & p(s)(r)
    }
    // fill unused
    for (r <- (1 << (STAGE - s + 1)) until (1 << STAGE)) {
      p(s - 1)(r) := False
    }
  }

  for (i <- 0 until (1 << STAGE)) {
    io.addrDecoded(i) := p(0)(i)
  }
}
