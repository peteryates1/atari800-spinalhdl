package retro.common.util

import spinal.core._

// PLL blackboxes still used by active/kept tops (Atari800Rp2040HdmiLgTop,
// SdramTestTop, Atari800Ecp5Hdmi720DiagTop). Extracted here when the old board
// tops that originally defined them were removed.

// Altera/Intel PLL: 56.67 MHz system + phase-shifted SDRAM clock.
class AtariPll extends BlackBox {
  setDefinitionName("atari_pll")
  val io = new Bundle {
    val areset = in  Bool()
    val inclk0 = in  Bool()
    val c0     = out Bool()   // 56.67 MHz system clock
    val c1     = out Bool()   // 56.67 MHz SDRAM clock (-3 ns phase shift)
    val c2     = out Bool()   // unused
    val c3     = out Bool()   // unused
    val locked = out Bool()
  }
  noIoPrefix()
}

// ECP5 PLL (verilog module "PllAtari800").
class PllEcp5 extends BlackBox {
  setDefinitionName("PllAtari800")
  val io = new Bundle {
    val inclk0 = in  Bool()
    val c0     = out Bool()
    val c1     = out Bool()
    val c2     = out Bool()
    val locked = out Bool()
  }
  noIoPrefix()
}
