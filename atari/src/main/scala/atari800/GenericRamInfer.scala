package atari800

import spinal.core._
import spinal.core.sim._

class GenericRamInfer(
  ADDRESS_WIDTH: Int = 9,
  SPACE: Int = 512,
  DATA_WIDTH: Int = 8
) extends Component {
  val io = new Bundle {
    val data    = in  Bits(DATA_WIDTH bits)
    val address = in  Bits(ADDRESS_WIDTH bits)
    val we      = in  Bool()
    val q       = out Bits(DATA_WIDTH bits)
  }
  io.data.simPublic()
  io.we.simPublic()

  val ramBlock = Mem(Bits(DATA_WIDTH bits), SPACE)
  ramBlock.simPublic()

  // Address range check: if SPACE covers the full address range, skip the check
  val fullRange = SPACE >= (1 << ADDRESS_WIDTH)
  val memAddrWidth = log2Up(SPACE)

  val weRam     = Bool()
  weRam.simPublic()
  val memAddr   = UInt(memAddrWidth bits)
  memAddr.simPublic()

  if (fullRange) {
    weRam   := io.we
    memAddr := io.address.asUInt.resize(memAddrWidth)
  } else {
    val inRange = io.address.asUInt < U(SPACE)
    when(inRange) {
      weRam   := io.we
      memAddr := io.address.asUInt.resize(memAddrWidth)
    } otherwise {
      weRam   := False
      memAddr := U(0, memAddrWidth bits)
    }
  }

  // Synchronous read with write-through (matches VHDL: single clocked process)
  // Uses readAsync + Reg for exactly one cycle of latency (not readSync which adds two)
  val qRam = Reg(Bits(DATA_WIDTH bits))
  qRam.simPublic()

  ramBlock.write(
    address = memAddr,
    data    = io.data,
    enable  = weRam
  )

  when(weRam) {
    qRam := io.data
  } otherwise {
    qRam := ramBlock.readAsync(memAddr)
  }

  if (fullRange) {
    io.q := qRam
  } else {
    val inRange2 = io.address.asUInt < U(SPACE)
    when(inRange2) {
      io.q := qRam
    } otherwise {
      io.q := B(DATA_WIDTH bits, default -> True)
    }
  }
}
