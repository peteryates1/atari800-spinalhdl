package retro.boards
import retro.common.util._
import retro.common.video._
import retro.common.scaler._
import retro.common.sdram._
import retro.link._
import retro.machines.atari._

import spinal.core._

// ECP5 HDMI bring-up / jitter test top (Colorlight i5 v7.0, LFE5U-25F). Fully
// self-contained: 25 MHz osc -> PLL (125 MHz TMDS + 25 MHz pixel) -> a fixed
// 640x480 "supervisor-like" text screen -> ECP5 TMDS output. No Atari core, no
// RP2040, no SDRAM — just validates the HDMI output path and lets us eyeball
// jitter/shimmer on sharp text before wiring anything else.
class PllHdmiEcp5 extends BlackBox {
  setDefinitionName("pll_hdmi_ecp5")
  val clkin   = in  Bool()
  val clkout0 = out Bool()   // 125 MHz (TMDS, 5x pixel)
  val clkout1 = out Bool()   // 25 MHz (pixel)
  val locked  = out Bool()
  noIoPrefix()
}

class Atari800Ecp5HdmiTestTop extends Component {
  // Clean port names (no io_ prefix) so the .lpf matches the wuxx i5 convention:
  //   clk_25mhz=P3; gpdi_dp/dn[0]=blue G19/H20 [1]=grn E20/F19 [2]=red C20/D19 [3]=clk J19/K19
  val io = new Bundle {
    val clk_25mhz = in  Bool()
    val gpdi_dp   = out Bits(4 bits)
    val gpdi_dn   = out Bits(4 bits)
  }
  noIoPrefix()

  val pll = new PllHdmiEcp5
  pll.clkin := io.clk_25mhz
  val clkPixel = pll.clkout1
  val clkTmds  = pll.clkout0

  val pixCd = ClockDomain(clkPixel, config = ClockDomainConfig(resetKind = BOOT))
  val pixArea = new ClockingArea(pixCd) {
    val gen = new Hdmi480pText
  }

  val dvid = new Ecp5DvidOut
  dvid.io.clkPixel := clkPixel
  dvid.io.clkTmds  := clkTmds
  dvid.io.red   := pixArea.gen.io.r
  dvid.io.green := pixArea.gen.io.g
  dvid.io.blue  := pixArea.gen.io.b
  dvid.io.hsync := pixArea.gen.io.hs
  dvid.io.vsync := pixArea.gen.io.vs
  dvid.io.de    := pixArea.gen.io.de
  io.gpdi_dp := dvid.io.gpdiDp
  io.gpdi_dn := dvid.io.gpdiDn
}

object Atari800Ecp5HdmiTestSv extends App {
  SpinalConfig(mode = SystemVerilog, targetDirectory = "generated")
    .generate(new Atari800Ecp5HdmiTestTop)
}
