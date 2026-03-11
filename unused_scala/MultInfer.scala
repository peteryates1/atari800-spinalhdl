package atari800

import spinal.core._

class MultInfer extends Component {
  val io = new Bundle {
    val a      = in  SInt(16 bits)
    val b      = in  SInt(16 bits)
    val result = out SInt(32 bits)
  }

  io.result := io.a * io.b
}
