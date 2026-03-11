package atari800

import spinal.core._

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

  val ramBlock = Mem(Bits(DATA_WIDTH bits), SPACE)

  // Address range check: if SPACE covers the full address range, skip the check
  val fullRange = SPACE >= (1 << ADDRESS_WIDTH)

  val weRam    = Bool()
  val address2 = UInt(ADDRESS_WIDTH bits)

  if (fullRange) {
    weRam    := io.we
    address2 := io.address.asUInt
  } else {
    val inRange = io.address.asUInt < U(SPACE)
    when(inRange) {
      weRam    := io.we
      address2 := io.address.asUInt
    } otherwise {
      weRam    := False
      address2 := U(0, ADDRESS_WIDTH bits)
    }
  }

  // Synchronous read with write-through
  val qRam = Reg(Bits(DATA_WIDTH bits))

  ramBlock.write(
    address = address2,
    data    = io.data,
    enable  = weRam
  )

  when(weRam) {
    qRam := io.data
  } otherwise {
    qRam := ramBlock.readSync(address2)
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
