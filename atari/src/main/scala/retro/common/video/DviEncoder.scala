package retro.common.video
import retro.common.util._
import retro.common.video._
import retro.common.scaler._
import retro.common.sdram._

import spinal.core._

// Corecourse dvi_encoder.v (from the c23_hdmi_color demo): proven 720p TMDS
// encoder + 10:1 serializer on this Cyclone 10 LP / HDMI front-end. Used in
// place of our SpinalHDL DvidOut, which is only reliable to ~125 MHz TMDS and
// does not work at 720p's 371.25 MHz. Verilog blackbox (like the PLLs).
//   pixelclk   = 74.25 MHz pixel
//   pixelclk5x = 371.25 MHz (5x pixel, DDR gives 10:1)
//   tmds_data[0]=blue, [1]=green, [2]=red
class DviEncoder extends BlackBox {
  setDefinitionName("dvi_encoder")
  val pixelclk    = in  Bool()
  val pixelclk5x  = in  Bool()
  val rst_n       = in  Bool()
  val blue_din    = in  Bits(8 bits)
  val green_din   = in  Bits(8 bits)
  val red_din     = in  Bits(8 bits)
  val hsync       = in  Bool()
  val vsync       = in  Bool()
  val de          = in  Bool()
  val tmds_clk_p  = out Bool()
  val tmds_clk_n  = out Bool()
  val tmds_data_p = out Bits(3 bits)
  val tmds_data_n = out Bits(3 bits)
}
