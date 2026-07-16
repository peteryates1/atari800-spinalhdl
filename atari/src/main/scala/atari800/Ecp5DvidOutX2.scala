package atari800

import spinal.core._
import spinal.lib._

// ECP5 720p TMDS serializer front-end: pixel-domain TMDS encode + 2-pixel word
// assembly, crossed to the SCLK domain and geared to 4-bit ODDRX2F nibbles.
// Clocks are INPUTS (so the whole datapath is SpinalSim-verifiable); the top wires
// clkPixel and clkSclk (= ECLK/2 = 2.5x pixel) from the ECLKSYNCB/CLKDIVF block, and
// feeds io.nibbles to the ODDRX2F primitives (ecp5_oddrx2x4.v).
//   nibbles = { clk-lane, red, green, blue } x 4 bits, SCLK domain.
//
// CDC: 5 SCLK == 2 pixels EXACTLY (both from the same PLL), so a free-running mod-5
// SCLK counter stays phase-locked to the pixel pairs with zero drift (fixed offset =
// a harmless TMDS rotation). The 2-pixel word is handed over via a PING-PONG double
// buffer: the pixel side writes one buffer per window, the SCLK side always reads the
// OTHER (completed, stable) buffer selected by a synchronised parity bit -> no
// metastable edge detection, no jitter. (An earlier synchroniser-edge `load` glitched
// occasionally on hardware.)
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

  // ---- pixel domain: TMDS encode + per-lane ping-pong of the 2-pixel word ----
  val pix = new ClockingArea(pixCd) {
    val encBlue = new TmdsEncoder
    encBlue.io.data := io.blue; encBlue.io.ctrl := io.vsync ## io.hsync; encBlue.io.dataEn := io.de
    val encGrn = new TmdsEncoder
    encGrn.io.data := io.green; encGrn.io.ctrl := B"00"; encGrn.io.dataEn := io.de
    val encRed = new TmdsEncoder
    encRed.io.data := io.red; encRed.io.ctrl := B"00"; encRed.io.dataEn := io.de
    val symClk = B"0000011111"                    // TMDS clock-lane pattern (LSB first)

    val pp   = Reg(Bool()) init False             // pixel parity: 0=even, 1=odd
    pp := !pp
    val wsel = Reg(Bool()) init False             // buffer written THIS 2-pixel window
    when(pp) { wsel := !wsel }                    // flip after the odd-pixel write

    // even symbol -> lo; odd symbol -> {odd,even} into buf0/buf1 (ping-pong)
    def assemble(sym: Bits): (Bits, Bits) = {
      val lo = Reg(Bits(10 bits)) init 0
      when(!pp) { lo := sym }
      val b0 = Reg(Bits(20 bits)) init 0
      val b1 = Reg(Bits(20 bits)) init 0
      when(pp && !wsel) { b0 := sym ## lo }
      when(pp &&  wsel) { b1 := sym ## lo }
      (b0, b1)
    }
    val (bBlu0, bBlu1) = assemble(encBlue.io.tmdsOut)
    val (bGrn0, bGrn1) = assemble(encGrn.io.tmdsOut)
    val (bRed0, bRed1) = assemble(encRed.io.tmdsOut)
    val (bClk0, bClk1) = assemble(symClk)
  }

  // ---- SCLK domain: free-running load + read the completed (stable) buffer ----
  val ser = new ClockingArea(sclkCd) {
    val wselS = BufferCC(pix.wsel, False)         // which buffer the pixel side is writing
    val c = Reg(UInt(3 bits)) init 0              // free-running mod-5 (5 SCLK == 2 pixels)
    c := Mux(c === 4, U(0), c + 1)
    val load = c === 0

    def gb(b0: Bits, b1: Bits): Bits = {
      val g = new TmdsGearboxX2
      // read the buffer NOT being written (guaranteed stable) -> metastability-safe
      // multi-bit crossing; register it in the SCLK domain.
      val readWord = RegNext(Mux(wselS, b0, b1)) init 0
      readWord.addTag(crossClockDomain)
      g.io.word := readWord
      g.io.load := load
      g.io.nibble
    }
    val nBlu = gb(pix.bBlu0, pix.bBlu1)
    val nGrn = gb(pix.bGrn0, pix.bGrn1)
    val nRed = gb(pix.bRed0, pix.bRed1)
    val nClk = gb(pix.bClk0, pix.bClk1)
    io.nibbles := nClk ## nRed ## nGrn ## nBlu
  }
}
