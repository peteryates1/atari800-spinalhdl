package atari800

import spinal.core._

// ECP5 DDR-output primitive blackbox (ecp5_ddr_out.v — WIDTH x ODDRX1F).
class Ecp5DdrOut(width: Int = 4) extends BlackBox {
  addGeneric("WIDTH", width)
  val io = new Bundle {
    val datain_h = in  Bits(width bits)
    val datain_l = in  Bits(width bits)
    val outclock = in  Bool()
    val dataout  = out Bits(width bits)
  }
  noIoPrefix()
  setBlackBoxName("ecp5_ddr_out")
}

// ECP5 DVI-D / HDMI output: parallel RGB + sync -> 4 serialised TMDS lanes.
// Same 10:1 DDR serialiser as DvidOut (3 TmdsEncoders + shift-register gearbox),
// but driving ECP5 ODDRX1F instead of Altera ALTDDIO. Output is single-ended;
// declare each gpdi[] pin as LVCMOS33D in the .lpf and the tools drive the
// complement (pseudo-differential HDMI).
//   gpdi(0)=D0 blue+sync, (1)=D1 green, (2)=D2 red, (3)=clock
class Ecp5DvidOut extends Component {
  val io = new Bundle {
    val clkPixel = in  Bool()
    val clkTmds  = in  Bool()   // 5x pixel
    val red      = in  Bits(8 bits)
    val green    = in  Bits(8 bits)
    val blue     = in  Bits(8 bits)
    val hsync    = in  Bool()
    val vsync    = in  Bool()
    val de       = in  Bool()
    // (0)=D0 blue+sync (1)=D1 green (2)=D2 red (3)=clock. The i5 HDMI pins are
    // explicitly-driven pairs (not auto-complement), so drive P and N both.
    val gpdiDp   = out Bits(4 bits)
    val gpdiDn   = out Bits(4 bits)
  }

  val pixelDomain = ClockDomain(io.clkPixel, config = ClockDomainConfig(resetKind = BOOT))
  val tmdsDomain  = ClockDomain(io.clkTmds,  config = ClockDomainConfig(resetKind = BOOT))

  val pixelArea = new ClockingArea(pixelDomain) {
    val encBlue  = new TmdsEncoder
    encBlue.io.data   := io.blue
    encBlue.io.ctrl   := io.vsync ## io.hsync
    encBlue.io.dataEn := io.de
    val encGreen = new TmdsEncoder
    encGreen.io.data   := io.green; encGreen.io.ctrl := B"00"; encGreen.io.dataEn := io.de
    val encRed   = new TmdsEncoder
    encRed.io.data   := io.red; encRed.io.ctrl := B"00"; encRed.io.dataEn := io.de

    // pixel -> tmds is a synchronous related-clock crossing (tmds = 5x pixel).
    val ch0 = RegNext(encBlue.io.tmdsOut)  init 0; ch0.addTag(crossClockDomain)
    val ch1 = RegNext(encGreen.io.tmdsOut) init 0; ch1.addTag(crossClockDomain)
    val ch2 = RegNext(encRed.io.tmdsOut)   init 0; ch2.addTag(crossClockDomain)
  }

  val tmdsArea = new ClockingArea(tmdsDomain) {
    val s0H = Reg(Bits(5 bits)) init 0; val s0L = Reg(Bits(5 bits)) init 0
    val s1H = Reg(Bits(5 bits)) init 0; val s1L = Reg(Bits(5 bits)) init 0
    val s2H = Reg(Bits(5 bits)) init 0; val s2L = Reg(Bits(5 bits)) init 0
    val sCH = Reg(Bits(5 bits)) init 0; val sCL = Reg(Bits(5 bits)) init 0
    val cnt = Reg(UInt(3 bits)) init 0

    def evenBits(v: Bits): Bits = v(8) ## v(6) ## v(4) ## v(2) ## v(0)  // rising (H)
    def oddBits(v: Bits):  Bits = v(9) ## v(7) ## v(5) ## v(3) ## v(1)  // falling (L)

    when(cnt === 4) {
      cnt := 0
      s0H := evenBits(pixelArea.ch0); s0L := oddBits(pixelArea.ch0)
      s1H := evenBits(pixelArea.ch1); s1L := oddBits(pixelArea.ch1)
      s2H := evenBits(pixelArea.ch2); s2L := oddBits(pixelArea.ch2)
      // clock lane = 0000011111 -> serialised 1111100000 (clean half-rate clock)
      sCH := B"00111"; sCL := B"00011"
    } otherwise {
      cnt := cnt + 1
      s0H := B"0" ## s0H(4 downto 1); s0L := B"0" ## s0L(4 downto 1)
      s1H := B"0" ## s1H(4 downto 1); s1L := B"0" ## s1L(4 downto 1)
      s2H := B"0" ## s2H(4 downto 1); s2L := B"0" ## s2L(4 downto 1)
      sCH := B"0" ## sCH(4 downto 1); sCL := B"0" ## sCL(4 downto 1)
    }
    val ddrH = sCH(0) ## s2H(0) ## s1H(0) ## s0H(0)   // (3)=clk (2)=red (1)=grn (0)=blu
    val ddrL = sCL(0) ## s2L(0) ## s1L(0) ## s0L(0)
  }

  val ddrP = new Ecp5DdrOut(4)
  ddrP.io.datain_h := tmdsArea.ddrH
  ddrP.io.datain_l := tmdsArea.ddrL
  ddrP.io.outclock := io.clkTmds
  io.gpdiDp := ddrP.io.dataout

  val ddrN = new Ecp5DdrOut(4)
  ddrN.io.datain_h := ~tmdsArea.ddrH
  ddrN.io.datain_l := ~tmdsArea.ddrL
  ddrN.io.outclock := io.clkTmds
  io.gpdiDn := ddrN.io.dataout
}
