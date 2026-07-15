package atari800

import spinal.core._

// 10:1 TMDS gearbox feeding an ECP5 ODDRX2F (4 bits per SCLK). 720p's 371.25 MHz
// serial rate is beyond the ODDRX1F path (Ecp5DvidOut, ~234 MHz cap); ODDRX2F on the
// ECLK network reaches it (see boards/i5-7v0/oddrx2f_720/).
//
// 2 pixels = 20 bits = 5 ODDRX2F nibbles. Implemented as a 20-bit shift register that
// emits the low 4 bits each SCLK and shifts right by 4 — LSB-first, matching TMDS
// order. A shift-out (fixed wiring) closes timing far better than a dynamic 5:1 mux.
// `load` (once per 2-pixel window) reloads a fresh word and emits its nibble 0.
class TmdsGearboxX2 extends Component {
  val io = new Bundle {
    val word   = in  Bits(20 bits)   // {pixel1_sym(10), pixel0_sym(10)}
    val load   = in  Bool()          // SCLK pulse marking the start of a 2-pixel window
    val nibble = out Bits(4 bits)    // 4 bits this SCLK -> ODDRX2F {D0..D3} = nibble(0..3)
  }
  val buf = Reg(Bits(20 bits)) init 0
  io.nibble := io.load ? io.word(3 downto 0) | buf(3 downto 0)
  buf := (io.load ? io.word | buf) |>> 4
}
