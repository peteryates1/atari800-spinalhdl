package atari800

import spinal.core._

// Self-contained 640x480@60 text generator for ECP5 HDMI bring-up / jitter test.
// No Atari core, no RP2040, no SDRAM — just a fixed "supervisor-like" screen of
// sharp white text on black (the worst case for monitor-scaling shimmer), so the
// ECP5 TMDS output can be validated on its own. 8x16 font, x2 scale => 40x15
// cells of 16x32 px. Pixel clock 25 MHz (VGA 640x480 timing).
class Hdmi480pText extends Component {
  val cols = 40; val rows = 15
  val fontW = 8; val fontH = 16
  val scaleX = 2; val scaleY = 2
  val cellW = fontW * scaleX      // 16
  val cellH = fontH * scaleY      // 32
  // VGA 640x480@60 (25.175 MHz nominal; 25 MHz -> 59.5 Hz, fine)
  val hActive = 640; val hFront = 16; val hSync = 96; val hBack = 48
  val vActive = 480; val vFront = 10; val vSync = 2;  val vBack = 33
  val hTotal = hActive + hFront + hSync + hBack   // 800
  val vTotal = vActive + vFront + vSync + vBack    // 525

  val io = new Bundle {
    val r = out Bits(8 bits); val g = out Bits(8 bits); val b = out Bits(8 bits)
    val de = out Bool(); val hs = out Bool(); val vs = out Bool()
  }

  // Fixed screen content (40 cols x 15 rows), padded.
  val text: Seq[String] = Seq(
    "ATARI 800   ECP5 HDMI 640x480 TEST",
    "",
    "jitter/shimmer check:",
    "0123456789 0123456789 0123456789 0123",
    "ABCDEFGHIJKLMNOPQRSTUVWXYZ abcdefghij",
    "iiiiiiii llll |||| .... thin strokes",
    "the quick brown fox jumps over a dog",
    "",
    "[c] cart   [1-4] disk   supervisor UI",
    "[b] boot   [q] resume   -- sample menu",
    "",
    "RGB check below (red/green/blue/white)",
    "", "", ""
  ).map(s => (s + " " * cols).take(cols))
  val chars: Seq[Int] = text.padTo(rows, " " * cols).flatMap(_.map(c => c.toInt & 0x7f))

  val charRom = Mem(Bits(8 bits), cols * rows) init chars.map(c => B(c & 0xFF, 8 bits))
  charRom.addAttribute("ramstyle", "logic")
  val fontRom = Mem(Bits(8 bits), Font8x16.rows) init Font8x16.initBits
  fontRom.addAttribute("ramstyle", "logic")

  val hc = Reg(UInt(log2Up(hTotal) bits)) init 0
  val vc = Reg(UInt(log2Up(vTotal) bits)) init 0
  val lineEnd = hc === (hTotal - 1)
  hc := Mux(lineEnd, U(0), hc + 1)
  when(lineEnd) { vc := Mux(vc === (vTotal - 1), U(0), vc + 1) }

  val deC = (hc < hActive) && (vc < vActive)
  val hsC = hc >= (hActive + hFront) && hc < (hActive + hFront + hSync)
  val vsC = vc >= (vActive + vFront) && vc < (vActive + vFront + vSync)

  // text region = rows*cellH = 480 (full height); cellW=16, cellH=32 (pow2 -> shifts)
  val textH = rows * cellH        // 480
  val charCol = (hc >> log2Up(cellW)).resize(log2Up(cols))
  val fontX   = (hc(log2Up(cellW) - 1 downto 0) >> log2Up(scaleX)).resize(log2Up(fontW))
  val inText  = vc < textH
  val charRow = (vc >> log2Up(cellH)).resize(log2Up(rows))
  val fontY   = (vc(log2Up(cellH) - 1 downto 0) >> log2Up(scaleY)).resize(log2Up(fontH))

  // 2-cycle read pipeline
  val charAddr = (charRow * U(cols) + charCol).resize(log2Up(cols * rows))
  val ch       = charRom.readSync(charAddr)
  val fontY1   = RegNext(fontY)
  val fontAddr = (ch(6 downto 0) ## fontY1.asBits).asUInt.resize(log2Up(Font8x16.rows))
  val fbits    = fontRom.readSync(fontAddr)

  val fontX1 = RegNext(fontX);   val fontX2 = RegNext(fontX1)
  val inTxt1 = RegNext(inText);  val inTxt2 = RegNext(inTxt1)
  val ccol1  = RegNext(charCol); val ccol2 = RegNext(ccol1)
  val crow1  = RegNext(charRow); val crow2 = RegNext(crow1)
  val de1 = RegNext(deC) init False; val de2 = RegNext(de1) init False
  val hs1 = RegNext(hsC) init False; val hs2 = RegNext(hs1) init False
  val vs1 = RegNext(vsC) init False; val vs2 = RegNext(vs1) init False

  val onBit = fbits((U(fontW - 1) - fontX2).resize(log2Up(fontW)))

  // Row 11 = RGB check strip (red/green/blue/white quarters) so all lanes verify.
  val rgbStrip = crow2 === 11 && inTxt2
  val quarter  = ccol2(5 downto 3)   // 0..4 across 40 cols

  val rOut = Bits(8 bits); val gOut = Bits(8 bits); val bOut = Bits(8 bits)
  when(de2) {
    when(rgbStrip) {
      rOut := Mux(quarter === 0 || quarter === 3 || quarter === 4, B(0xFF, 8 bits), B(0, 8 bits))
      gOut := Mux(quarter === 1 || quarter === 3 || quarter === 4, B(0xFF, 8 bits), B(0, 8 bits))
      bOut := Mux(quarter === 2 || quarter === 3 || quarter === 4, B(0xFF, 8 bits), B(0, 8 bits))
    } elsewhen(inTxt2 && onBit) {
      rOut := B(0xFF, 8 bits); gOut := B(0xFF, 8 bits); bOut := B(0xFF, 8 bits)   // white text
    } otherwise {
      rOut := 0; gOut := 0; bOut := B(0x10, 8 bits)   // very dark blue background
    }
  } otherwise { rOut := 0; gOut := 0; bOut := 0 }

  io.r := RegNext(rOut) init 0
  io.g := RegNext(gOut) init 0
  io.b := RegNext(bOut) init 0
  io.de := RegNext(de2) init False
  io.hs := RegNext(!hs2) init False   // active-low
  io.vs := RegNext(!vs2) init False
}
