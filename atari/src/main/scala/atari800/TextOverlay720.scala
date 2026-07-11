package atari800

import spinal.core._
import spinal.lib._

// FPGA-native 720p text overlay for the supervisor. Generates text pixels
// DIRECTLY at 1280x720 from an on-chip character grid + the 8x16 font, integer-
// scaled x4 horizontally / x3 vertically in logic. No SDRAM, no upscaler.
//
// NOTE ON CRISPNESS: on a 1920x1080 monitor this 720p output is upscaled 1.5x
// (non-integer) by the MONITOR, which shimmers razor-sharp text (the Atari's own
// text survives because it is soft/nearest-neighbour-upscaled from the 384-wide
// framebuffer). That shimmer is a monitor-scaling artifact and is NOT fixable on
// this Cyclone 10 LP board: at 60Hz the soft TMDS serializer caps output at
// 720p60, and native 1080p (60 impossible here, 30 rejected by the monitor) is
// off the table. Crisp text needs an ECP5/Artix (native 1080p60). This 720p
// overlay is the working-but-shimmery supervisor we keep until then. See the
// project memory (project_supervisor_menu / reference_m9k_budget_10cl025).
//
// The character grid is written by the RP2040 over SPI in the component's
// default (system) clock; pixels are produced in the pixel clock domain and
// muxed into the DVI output when supDisplay is set. de active-high, hs/vs
// active-low, timing identical to VideoFbRead2 so the monitor stays locked.
class TextOverlay720 extends Component {
  val cols = 40; val rows = 15
  val fontW = 8; val fontH = 16
  val scaleX = 4; val scaleY = 3
  val charW = fontW * scaleX      // 32
  val charH = fontH * scaleY      // 48
  val hActive = 1280; val hFront = 110; val hSync = 40; val hBack = 220
  val vActive = 720;  val vFront = 5;   val vSync = 5;  val vBack = 20
  val hTotal = hActive + hFront + hSync + hBack   // 1650
  val vTotal = vActive + vFront + vSync + vBack    // 750
  require(cols * charW == hActive)
  require(rows * charH == vActive)

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

  // Font ROM in logic (LUTs) so it doesn't spend the scarce M9K budget.
  val fontRom = Mem(Bits(8 bits), Font8x16.rows) init Font8x16.initBits
  fontRom.addAttribute("ramstyle", "logic")

  val pixCd = ClockDomain(io.clkPixel, config = ClockDomainConfig(resetKind = BOOT))
  val px = new ClockingArea(pixCd) {
    val hc = Reg(UInt(log2Up(hTotal) bits)) init 0
    val vc = Reg(UInt(log2Up(vTotal) bits)) init 0
    val lineEnd = hc === (hTotal - 1)
    hc := Mux(lineEnd, U(0), hc + 1)
    when(lineEnd) { vc := Mux(vc === (vTotal - 1), U(0), vc + 1) }

    // Vertical char row / font row via counters (scaleY=3 and fontH=16 aren't a
    // clean divide of vActive by a power of two).
    val ySub     = Reg(UInt(log2Up(scaleY) bits)) init 0
    val fontYr   = Reg(UInt(log2Up(fontH) bits)) init 0
    val charRowR = Reg(UInt(log2Up(rows) bits)) init 0
    when(lineEnd) {
      when(vc === (vTotal - 1)) { ySub := 0; fontYr := 0; charRowR := 0 }
        .elsewhen((vc + 1) < vActive) {
          when(ySub === (scaleY - 1)) {
            ySub := 0
            when(fontYr === (fontH - 1)) { fontYr := 0; charRowR := charRowR + 1 }
              .otherwise { fontYr := fontYr + 1 }
          } otherwise { ySub := ySub + 1 }
        }
    }

    // Horizontal: charW=32=2^5, scaleX=4=2^2 -> pure shifts.
    val charCol = (hc >> log2Up(charW)).resize(log2Up(cols))
    val fontX   = (hc(log2Up(charW) - 1 downto 0) >> log2Up(scaleX)).resize(log2Up(fontW))

    val deC = (hc < hActive) && (vc < vActive)
    val hsC = hc >= (hActive + hFront) && hc < (hActive + hFront + hSync)
    val vsC = vc >= (vActive + vFront) && vc < (vActive + vFront + vSync)

    // 2-cycle read pipeline: charAddr -> ch (cycle 1) -> fbits (cycle 2).
    val charAddr = (charRowR * U(cols) + charCol).resize(log2Up(cols * rows))
    val ch       = charRam.readSync(charAddr, clockCrossing = true)
    val fontAddr = (ch(6 downto 0) ## fontYr.asBits).asUInt.resize(log2Up(Font8x16.rows))
    val fbits    = fontRom.readSync(fontAddr)

    val fontX1 = RegNext(fontX)
    val fontX2 = RegNext(fontX1)
    val de1 = RegNext(deC) init False; val de2 = RegNext(de1) init False
    val hs1 = RegNext(hsC) init False; val hs2 = RegNext(hs1) init False
    val vs1 = RegNext(vsC) init False; val vs2 = RegNext(vs1) init False

    // Registered pixel (clean output, like VideoFbRead2.pix), sync matched with a
    // 3rd delay stage so the palette->DVI path gets a full clock.
    val onBit = fbits((U(fontW - 1) - fontX2).resize(log2Up(fontW)))
    val de3 = RegNext(de2) init False
    val hs3 = RegNext(hs2) init False
    val vs3 = RegNext(vs2) init False
    io.pix := RegNext(Mux(de2 && onBit, io.fg, io.bg)) init B(0, 8 bits)
    io.de  := de3
    io.hs  := !hs3
    io.vs  := !vs3
  }
}
