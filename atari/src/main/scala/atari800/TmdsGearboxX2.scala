package atari800

import spinal.core._
import spinal.lib._

// 10:1 TMDS gearbox feeding an ECP5 ODDRX2F (4 bits per SCLK, output = 2x ECLK).
// 720p needs a 371.25 MHz serial rate that the ODDRX1F path (Ecp5DvidOut) can't
// reach (~234 MHz cap). ODDRX2F on the ECLK network does — see boards/i5-7v0/
// oddrx2f_720/ for the proven clocking feasibility.
//
// Gearing: pixel clock = P, SCLK = 2.5*P, ECLK = 5*P. ODDRX2F emits 2 bits/ECLK
// (D0,D1,D2,D3 over 2 ECLK = 4 bits per SCLK). 10 bits/pixel / 4 = 2.5, so work
// on a 2-pixel / 20-bit boundary = 5 SCLK cycles:
//   word = P1 ## P0   (20b, LSB = pixel0.bit0)
//   nibble c (c=0..4) = word[c*4 +: 4]  -> ODDRX2F D0=word[c*4], D1, D2, D3
// which serialises LSB-first, matching TMDS bit order.
//
// This component is the SCLK-domain gearbox datapath (the sim-verifiable part).
// The pixel->SCLK word handoff and ODDRX2F/ECLK primitives are wired at the top.
// io.pixToggle flips every pixel in the pixel domain; the gearbox uses it to know
// when a fresh 2-pixel word is ready. Feed sym0/sym1 = {pixel1, pixel0} symbols.
class TmdsGearboxX2 extends Component {
  val io = new Bundle {
    val word   = in  Bits(20 bits)   // {pixel1_sym(10), pixel0_sym(10)}, stable per 2-pixel window
    val load   = in  Bool()          // pulse (SCLK domain) marking start of a 2-pixel window (c:=0)
    val nibble = out Bits(4 bits)    // 4 bits this SCLK -> ODDRX2F {D0,D1,D2,D3} = nibble(0),(1),(2),(3)
  }
  val c   = Reg(UInt(3 bits)) init 0        // mod-5 phase counter (0..4)
  val buf = Reg(Bits(20 bits)) init 0

  // on a load pulse: latch the fresh 2-pixel word and restart at nibble 0.
  val curWord = io.load ? io.word | buf
  val curC    = io.load ? U(0, 3 bits) | c
  io.nibble := curWord.subdivideIn(4 bits)(curC)   // 20b -> 5 nibbles; (0)=bits[3:0]

  when(io.load) {
    buf := io.word
    c   := 1
  } otherwise {
    c := Mux(c === 4, U(0), c + 1)
  }
}
