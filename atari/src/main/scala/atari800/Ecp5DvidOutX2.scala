package atari800

import spinal.core._
import spinal.lib._

// ECP5 720p TMDS serializer front-end: pixel-domain TMDS encode + 2-pixel word
// assembly, crossed to the SCLK domain and geared to 4-bit ODDRX2F nibbles.
// Clocks are INPUTS (so the whole datapath is SpinalSim-verifiable); the top wires
// clkPixel and clkSclk (= ECLK/2 = 2.5x pixel) from the ECLKSYNCB/CLKDIVF block, and
// feeds io.nibbles to the ODDRX2F primitives (ecp5_oddrx2_out.v).
//   nibbles = { clk-lane, red, green, blue } x 4 bits, SCLK domain.
class Ecp5DvidOutX2 extends Component {
  val io = new Bundle {
    val clkPixel = in  Bool()
    val clkSclk  = in  Bool()
    val red   = in  Bits(8 bits)
    val green = in  Bits(8 bits)
    val blue  = in  Bits(8 bits)
    val hsync = in  Bool()
    val vsync = in  Bool()
    val de    = in  Bool()
    val nibbles = out Bits(16 bits)   // {clk(15:12), red(11:8), grn(7:4), blu(3:0)}
  }
  val pixCd  = ClockDomain(io.clkPixel, config = ClockDomainConfig(resetKind = BOOT))
  val sclkCd = ClockDomain(io.clkSclk,  config = ClockDomainConfig(resetKind = BOOT))

  // ---- pixel domain: TMDS encode + assemble each lane's 2-pixel 20-bit word ----
  val pix = new ClockingArea(pixCd) {
    val encBlue = new TmdsEncoder
    encBlue.io.data := io.blue; encBlue.io.ctrl := io.vsync ## io.hsync; encBlue.io.dataEn := io.de
    val encGrn = new TmdsEncoder
    encGrn.io.data := io.green; encGrn.io.ctrl := B"00"; encGrn.io.dataEn := io.de
    val encRed = new TmdsEncoder
    encRed.io.data := io.red; encRed.io.ctrl := B"00"; encRed.io.dataEn := io.de
    val symClk = B"0000011111"                     // TMDS clock-lane pattern (LSB first)

    val pp = Reg(Bool()) init False                // pixel parity: 0=even, 1=odd
    pp := !pp

    // per-lane: even symbol -> lo; odd symbol -> word = {odd, even}
    def assemble(sym: Bits): Bits = {
      val lo   = Reg(Bits(10 bits)) init 0
      val word = Reg(Bits(20 bits)) init 0
      when(!pp) { lo := sym } otherwise { word := sym ## lo }
      word
    }
    val wBlu = assemble(encBlue.io.tmdsOut)
    val wGrn = assemble(encGrn.io.tmdsOut)
    val wRed = assemble(encRed.io.tmdsOut)
    val wClk = assemble(symClk)
    val wv = Reg(Bool()) init False                // toggles when a fresh word is latched (odd pixel)
    when(pp) { wv := !wv }
  }

  // ---- SCLK domain: detect fresh word, load the gearboxes ----
  val ser = new ClockingArea(sclkCd) {
    val wvS  = BufferCC(pix.wv, False)
    val wvS1 = RegNext(wvS) init False
    val load = wvS =/= wvS1                         // one SCLK pulse per 2-pixel window

    // words are stable for the whole 2-pixel window, so sampling them (gated by the
    // synchronised `load`) is metastability-safe.
    def gb(word: Bits): Bits = {
      val g = new TmdsGearboxX2
      // word is held stable for the whole 2-pixel window in the pixel domain, and
      // `load` (a synchronised pulse) only fires well after it settled -> sampling
      // it here is a deliberate, metastability-safe multi-bit crossing.
      val wReg = RegNextWhen(word, load) init 0
      wReg.addTag(crossClockDomain)
      g.io.word := wReg
      g.io.load := load
      g.io.nibble
    }
    val nBlu = gb(pix.wBlu)
    val nGrn = gb(pix.wGrn)
    val nRed = gb(pix.wRed)
    val nClk = gb(pix.wClk)
    io.nibbles := nClk ## nRed ## nGrn ## nBlu
  }
}
