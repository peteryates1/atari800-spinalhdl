package atari800

import spinal.core._

class ScandoubleRamInfer extends Component {
  val io = new Bundle {
    val data    = in  Bits(8 bits)
    val address = in  UInt(11 bits)  // 0 to 1824
    val we      = in  Bool()
    val q       = out Bits(8 bits)
  }

  val ramBlock = Mem(Bits(8 bits), 1825)

  when(io.we) {
    ramBlock.write(io.address, io.data)
  }

  io.q := ramBlock.readSync(io.address)
}
