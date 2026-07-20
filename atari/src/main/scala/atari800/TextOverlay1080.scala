package atari800

import spinal.core._
import spinal.lib._

// FPGA-native 1080p text overlay for the supervisor — the Artix/Wukong sibling of
// TextOverlay720. Generates text pixels DIRECTLY at 1920x1080 from the same on-chip
// 40x15 character grid + 8x16 font the RP2040/Pico supervisor already writes over
// SPI ('T' frames -> kbd.io.txt*), so NO firmware change is needed. No SDRAM, no
// upscaler -> crisp, wobble-free text (this is a native 1080p60 panel, unlike the
// Cyclone 10 LP which could only manage 720p and shimmered under the monitor's 1.5x
// upscale — see TextOverlay720's header).
//
// Sizing: 40 cols x charW(48) = 1920 exactly (scaleX=6); 15 rows x charH(64) = 960
// (scaleY=4), vertically centred in 1080 with 60px black bars top/bottom. charH=64
// is a power of two so the vertical char/font address is a pure bit-slice; charW=48
// is not, so the horizontal position uses a small counter. de active-high, hs/vs
// active-low, timing identical to VideoFbRead2 (CEA-861 1080p60) so the monitor
// stays locked when the DVI mux swaps the Atari scaler for this overlay.
class TextOverlay1080 extends Component {
  val cols = 40; val rows = 15
  val fontW = 8; val fontH = 16
  val scaleX = 6; val scaleY = 4
  val charW = fontW * scaleX      // 48
  val charH = fontH * scaleY      // 64
  val blockW = cols * charW       // 1920
  val blockH = rows * charH       // 960
  val hActive = 1920; val hFront = 88; val hSync = 44; val hBack = 148
  val vActive = 1080; val vFront = 4;  val vSync = 5;  val vBack = 36
  val hTotal = hActive + hFront + hSync + hBack   // 2200
  val vTotal = vActive + vFront + vSync + vBack    // 1125
  val yTop = (vActive - blockH) / 2                // 60 (top black bar)
  val yBot = yTop + blockH                         // 1020
  require(blockW == hActive)                       // text fills the width exactly
  require(blockH <= vActive && (vActive - blockH) % 2 == 0)
  require(isPow2(charH) && isPow2(scaleY))         // vertical address = bit-slice

  val io = new Bundle {
    val clkPixel = in Bool()
    // character-grid write (in this component's default = system clock)
    val wrEn   = in Bool()
    val wrAddr = in UInt(log2Up(cols * rows) bits)
    val wrChar = in Bits(8 bits)
    // colours (GTIA indices) — global for now
    val fg = in Bits(8 bits)
    val bg = in Bits(8 bits)
    // pixel-domain outputs
    val pix = out Bits(8 bits)
    val de  = out Bool()
    val hs  = out Bool()
    val vs  = out Bool()
  }

  // Char grid: written in the default (system) clock, read in the pixel clock.
  val charRam = Mem(Bits(8 bits), cols * rows)
  charRam.write(io.wrAddr, io.wrChar, io.wrEn)

  // Font ROM in logic (LUTs) so it doesn't spend the M9K/BRAM budget.
  val fontRom = Mem(Bits(8 bits), Font8x16.rows) init Font8x16.initBits
  fontRom.addAttribute("ramstyle", "logic")

  val pixCd = ClockDomain(io.clkPixel, config = ClockDomainConfig(resetKind = BOOT))
  val px = new ClockingArea(pixCd) {
    val hc = Reg(UInt(log2Up(hTotal) bits)) init 0
    val vc = Reg(UInt(log2Up(vTotal) bits)) init 0
    val lineEnd = hc === (hTotal - 1)
    hc := Mux(lineEnd, U(0), hc + 1)
    when(lineEnd) { vc := Mux(vc === (vTotal - 1), U(0), vc + 1) }

    // Vertical: charH=64 and scaleY=4 are powers of two, so the char row / font row
    // are bit-slices of the block-relative line (only meaningful inside the block).
    val inBlock = (vc >= U(yTop)) && (vc < U(yBot))
    val yBlk = (vc - U(yTop, vc.getWidth bits)).resize(log2Up(blockH))
    val charRowR = (yBlk >> log2Up(charH)).resize(log2Up(rows))
    val fontYr   = yBlk(log2Up(charH) - 1 downto log2Up(scaleY))   // 0..15

    // Horizontal: charW=48 isn't a power of two -> count columns/font-x/sub-pixel.
    val xSub     = Reg(UInt(log2Up(scaleX) bits)) init 0
    val fontXr   = Reg(UInt(log2Up(fontW) bits)) init 0
    val charColR = Reg(UInt(log2Up(cols) bits)) init 0
    when(lineEnd) { xSub := 0; fontXr := 0; charColR := 0 }
      .elsewhen((hc + 1) < U(hActive)) {
        when(xSub === (scaleX - 1)) {
          xSub := 0
          when(fontXr === (fontW - 1)) { fontXr := 0; charColR := charColR + 1 }
            .otherwise { fontXr := fontXr + 1 }
        } otherwise { xSub := xSub + 1 }
      }

    val deC = (hc < hActive) && (vc < vActive)
    val hsC = hc >= (hActive + hFront) && hc < (hActive + hFront + hSync)
    val vsC = vc >= (vActive + vFront) && vc < (vActive + vFront + vSync)

    // 2-cycle read pipeline: charAddr -> ch (cycle 1) -> fbits (cycle 2).
    val charAddr = (charRowR * U(cols) + charColR).resize(log2Up(cols * rows))
    val ch       = charRam.readSync(charAddr, clockCrossing = true)
    val fontAddr = (ch(6 downto 0) ## fontYr.asBits).asUInt.resize(log2Up(Font8x16.rows))
    val fbits    = fontRom.readSync(fontAddr)

    val fontX1 = RegNext(fontXr)
    val fontX2 = RegNext(fontX1)
    val de1 = RegNext(deC) init False; val de2 = RegNext(de1) init False
    val hs1 = RegNext(hsC) init False; val hs2 = RegNext(hs1) init False
    val vs1 = RegNext(vsC) init False; val vs2 = RegNext(vs1) init False
    val ib1 = RegNext(inBlock) init False; val ib2 = RegNext(ib1) init False

    // Registered pixel (clean output, like VideoFbRead2.pix), sync matched with a
    // 3rd delay stage so the palette->DVI path gets a full clock. Outside the text
    // block (top/bottom bars) the pixel is bg (black).
    val onBit = fbits((U(fontW - 1) - fontX2).resize(log2Up(fontW)))
    val de3 = RegNext(de2) init False
    val hs3 = RegNext(hs2) init False
    val vs3 = RegNext(vs2) init False
    io.pix := RegNext(Mux(de2 && ib2 && onBit, io.fg, io.bg)) init B(0, 8 bits)
    io.de  := de3
    io.hs  := !hs3
    io.vs  := !vs3
  }
}
