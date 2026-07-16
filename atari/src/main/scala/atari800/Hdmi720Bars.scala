package atari800

import spinal.core._

// 1280x720p60 timing + a diagnostic test pattern for the ECP5 ODDRX2F HDMI path.
// CEA-861 720p: pixel 74.25 MHz (we run ~74), HSync/VSync BOTH POSITIVE.
//   H: 1280 active + 110 front + 40 sync + 220 back = 1650
//   V:  720 active +   5 front +  5 sync +  20 back =  750
// Pattern: 1px white border (confirms edges/timing), 8 vertical colour bars
// (exercises all 3 TMDS lanes), and a white box that moves 1px/frame (confirms the
// output is live/refreshing). Same io as Hdmi480pText so the top can swap it in.
class Hdmi720Bars extends Component {
  val hActive = 1280; val hFront = 110; val hSync = 40; val hBack = 220
  val vActive = 720;  val vFront = 5;   val vSync = 5;  val vBack = 20
  val hTotal = hActive + hFront + hSync + hBack   // 1650
  val vTotal = vActive + vFront + vSync + vBack   // 750

  val io = new Bundle {
    val r = out Bits(8 bits); val g = out Bits(8 bits); val b = out Bits(8 bits)
    val de = out Bool(); val hs = out Bool(); val vs = out Bool()
  }

  val hc = Reg(UInt(log2Up(hTotal) bits)) init 0
  val vc = Reg(UInt(log2Up(vTotal) bits)) init 0
  val lineEnd  = hc === (hTotal - 1)
  val frameEnd = lineEnd && (vc === (vTotal - 1))
  hc := Mux(lineEnd, U(0), hc + 1)
  when(lineEnd) { vc := Mux(vc === (vTotal - 1), U(0), vc + 1) }

  val de = (hc < hActive) && (vc < vActive)
  val hs = hc >= (hActive + hFront) && hc < (hActive + hFront + hSync)   // positive
  val vs = vc >= (vActive + vFront) && vc < (vActive + vFront + vSync)   // positive

  // moving box: advance 1px/frame
  val frame = Reg(UInt(11 bits)) init 0
  when(frameEnd) { frame := frame + 1 }
  val boxX = frame                                       // 0..2047 wraps within active
  val inBox = (vc >= 320) && (vc < 400) &&
              (hc >= boxX.resized) && (hc < (boxX + 80).resized)

  val border = (hc < 1) || (hc === (hActive - 1)) || (vc < 1) || (vc === (vActive - 1))

  // 8 colour bars of 160px via a registered counter — a divider (hc/160) in the pixel
  // path is a long combinational chain that drops pixel Fmax to ~79 MHz (marginal ->
  // glitches); this accumulator keeps it fast (~135 MHz).
  val barPix = Reg(UInt(8 bits)) init 0
  val bar    = Reg(UInt(3 bits)) init 0
  when(lineEnd) {
    barPix := 0; bar := 0
  } otherwise {
    when(barPix === 159) { barPix := 0; bar := bar + 1 }
      .otherwise         { barPix := barPix + 1 }
  }

  val rgb = Bits(24 bits)
  when(border || inBox) {
    rgb := B(0xFFFFFF, 24 bits)                          // white
  } otherwise {
    // 8 colour bars: white,yellow,cyan,green,magenta,red,blue,black
    val rr = Mux(bar === 0 || bar === 1 || bar === 4 || bar === 5, B(0xFF, 8 bits), B(0, 8 bits))
    val gg = Mux(bar === 0 || bar === 1 || bar === 2 || bar === 3, B(0xFF, 8 bits), B(0, 8 bits))
    val bb = Mux(bar === 0 || bar === 2 || bar === 4 || bar === 6, B(0xFF, 8 bits), B(0, 8 bits))
    rgb := rr ## gg ## bb
  }

  // register outputs (1-cycle) for clean timing; blank RGB outside active
  io.r  := RegNext(Mux(de, rgb(23 downto 16), B(0, 8 bits))) init 0
  io.g  := RegNext(Mux(de, rgb(15 downto 8),  B(0, 8 bits))) init 0
  io.b  := RegNext(Mux(de, rgb(7 downto 0),   B(0, 8 bits))) init 0
  io.de := RegNext(de) init False
  io.hs := RegNext(hs) init False
  io.vs := RegNext(vs) init False
}
