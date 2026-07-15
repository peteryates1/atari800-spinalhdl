package atari800

import spinal.core._

// ECP5 720p HDMI serializer integration test: clkgen (PLL + ECLKSYNCB/CLKDIVF) ->
// pixel/SCLK/ECLK; a pattern generator (pixel domain) -> Ecp5DvidOutX2 (ODDRX2F
// gearbox) -> ODDRX2F primitives -> gpdi P/N. Proves the full 720p serializer fits
// and closes timing at 371 MHz (see boards/i5-7v0/oddrx2f_720/). The pattern gen is
// the existing 640x480 text (video timing is irrelevant to the serializer test).
class ClkGen720 extends BlackBox {
  setDefinitionName("ecp5_clkgen720")
  val clk25  = in  Bool()
  val pixel  = out Bool()
  val sclk   = out Bool()
  val eclk   = out Bool()
  val locked = out Bool()
  noIoPrefix()
}

class Oddrx2x4 extends BlackBox {
  setDefinitionName("ecp5_oddrx2x4")
  val nib  = in  Bits(16 bits)
  val eclk = in  Bool()
  val sclk = in  Bool()
  val q    = out Bits(4 bits)
  noIoPrefix()
}

class Atari800Ecp5Hdmi720TestTop extends Component {
  val io = new Bundle {
    val clk_25mhz = in  Bool()
    val gpdi_dp   = out Bits(4 bits)
    val gpdi_dn   = out Bits(4 bits)
  }
  noIoPrefix()

  val cg = new ClkGen720
  cg.clk25 := io.clk_25mhz

  val pixCd = ClockDomain(cg.pixel, config = ClockDomainConfig(resetKind = BOOT))
  val pixArea = new ClockingArea(pixCd) { val gen = new Hdmi720Bars }

  val ser = new Ecp5DvidOutX2
  ser.io.clkPixel := cg.pixel
  ser.io.clkSclk  := cg.sclk
  ser.io.red   := pixArea.gen.io.r
  ser.io.green := pixArea.gen.io.g
  ser.io.blue  := pixArea.gen.io.b
  ser.io.hsync := pixArea.gen.io.hs
  ser.io.vsync := pixArea.gen.io.vs
  ser.io.de    := pixArea.gen.io.de

  val oP = new Oddrx2x4          // P: {clk,red,grn,blu}
  oP.nib := ser.io.nibbles; oP.eclk := cg.eclk; oP.sclk := cg.sclk
  val oN = new Oddrx2x4          // N: complement (pseudo-diff)
  oN.nib := ~ser.io.nibbles; oN.eclk := cg.eclk; oN.sclk := cg.sclk

  io.gpdi_dp := oP.q
  io.gpdi_dn := oN.q
}

object Atari800Ecp5Hdmi720TestSv extends App {
  SpinalConfig(mode = SystemVerilog, targetDirectory = "generated")
    .generate(new Atari800Ecp5Hdmi720TestTop)
}
