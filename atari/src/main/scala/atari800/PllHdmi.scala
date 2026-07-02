package atari800

import spinal.core._

// 720p HDMI PLL (reference pll_hdmi.v): 50 MHz in -> c0 = 74.25 MHz pixel,
// c1 = 371.25 MHz TMDS (5x pixel). Vendor altpll hard-IP, thin blackbox.
class PllHdmi extends BlackBox {
  setDefinitionName("pll_hdmi")
  val inclk0 = in  Bool()
  val c0     = out Bool()
  val c1     = out Bool()
}
