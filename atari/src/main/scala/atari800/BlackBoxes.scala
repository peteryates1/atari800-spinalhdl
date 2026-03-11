package atari800

import spinal.core._

// ===========================================================================
// Altera PLL (altpll) BlackBox — only vendor IP kept as BlackBox
// ===========================================================================
class PllSys extends BlackBox {
  setDefinitionName("pll_sys")

  val io = new Bundle {
    val inclk0 = in  Bool()
    val c0     = out Bool()
    val c1     = out Bool()
    val locked = out Bool()
  }

  noIoPrefix()
  addRTLPath("pll_sys.vhd")
}

// ===========================================================================
// Altera ALTDDIO_OUT BlackBox — vendor DDR output primitive
// ===========================================================================
class AltddioOut(width: Int = 4) extends BlackBox {
  setDefinitionName("altddio_out")

  addGeneric("intended_device_family", "Cyclone 10 LP")
  addGeneric("invert_output", "OFF")
  addGeneric("lpm_type", "altddio_out")
  addGeneric("width", width)

  val io = new Bundle {
    val datainH   = in  Bits(width bits)
    val datainL   = in  Bits(width bits)
    val outclock  = in  Bool()
    val dataout   = out Bits(width bits)
  }

  noIoPrefix()

  io.datainH.setName("datain_h")
  io.datainL.setName("datain_l")
}
