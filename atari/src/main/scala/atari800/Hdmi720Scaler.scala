package atari800

import spinal.core._
import spinal.lib._

// Genlocked 3x integer upscaler: Atari NATIVE video (~336x240 active) -> 1280x720p60,
// BRAM-only (no framebuffer/SDRAM). 240 active lines x3 = 720 (fills the height exactly);
// 336 px x3 = 1008 centred in 1280 (side borders). A small circular multi-line buffer
// bridges the Atari sys clock to the 720p pixel clock; the read side reads each input
// line 3x (vertical) and each pixel 3x (horizontal), genlocked to the input VSYNC.
//
// The reader's input-line demand (720p line rate / 3 ~= 15 kHz) ~ the Atari line rate, and
// it reads line vc/3 which lags the sequential writer, so the writer stays ahead; a small
// circular buffer (nLines) is overwritten only well after the reader has passed. Frame
// rates must be genlocked (Atari ~60 Hz == 720p 60 Hz) via the shared PLL.
//
// Input: strobed active pixels (inStrobe rising = one pixel), lines delimited by inHsync,
// frames by inVsync. 12-bit RGB444 buffer (Atari content near-lossless at 4 bpc).
class Hdmi720Scaler(
    nLines:   Int = 8,        // circular line-buffer depth (power of 2)
    lineMax:  Int = 512,      // max input pixels captured per line (power of 2)
    hStart:   Int = 0,        // (TUNE) strobes to skip after HSYNC before capturing (H blanking)
    inActive: Int = 336,      // (TUNE) input active pixels per line
    hScale:   Int = 3,
    vScale:   Int = 3,
    hActive: Int = 1280, hFront: Int = 110, hSync: Int = 40, hBack: Int = 220,
    vActive: Int = 720,  vFront: Int = 5,   vSync: Int = 5,  vBack: Int = 20
) extends Component {
  val hTotal  = hActive + hFront + hSync + hBack   // 1650
  val vTotal  = vActive + vFront + vSync + vBack    // 750
  val outW    = inActive * hScale                   // 1008
  val hBorder = (hActive - outW) / 2                // 136

  val io = new Bundle {
    val clkSys   = in  Bool()
    val clkPixel = in  Bool()
    val inStrobe = in  Bool()      // level, edge-detected: one active pixel per rising edge
    val inR      = in  Bits(8 bits)
    val inG      = in  Bits(8 bits)
    val inB      = in  Bits(8 bits)
    val inHsync  = in  Bool()      // active-high
    val inVsync  = in  Bool()      // active-high
    val outR     = out Bits(8 bits)
    val outG     = out Bits(8 bits)
    val outB     = out Bits(8 bits)
    val outHsync = out Bool()
    val outVsync = out Bool()
    val outDe    = out Bool()
  }

  val buf = Mem(Bits(12 bits), nLines * lineMax)   // dual-clock: sys write, pixel read

  val sysCd = ClockDomain(io.clkSys,   config = ClockDomainConfig(resetKind = BOOT))
  val pixCd = ClockDomain(io.clkPixel, config = ClockDomainConfig(resetKind = BOOT))

  // ---- write side (Atari / sys domain) ----
  val wr = new ClockingArea(sysCd) {
    val strobeRise = io.inStrobe && !RegNext(io.inStrobe).init(False)
    val hsRise     = io.inHsync  && !RegNext(io.inHsync ).init(False)
    val vsRise     = io.inVsync  && !RegNext(io.inVsync ).init(False)

    val wline = Reg(UInt(log2Up(nLines) bits)) init 0
    val wx    = Reg(UInt(log2Up(lineMax) bits)) init 0    // raw strobe counter from HSYNC
    when(strobeRise && wx =/= (lineMax - 1)) { wx := wx + 1 }
    when(hsRise) { wline := wline + 1; wx := 0 }
    when(vsRise) { wline := 0 }                     // frame top -> line 0

    // Skip the first hStart strobes (H blanking) so buffer position 0 = first active pixel.
    val inWin = (wx >= U(hStart)) && (wx < U(hStart + inActive))
    val wpos  = (wx - U(hStart)).resize(log2Up(lineMax) bits)
    buf.write(
      address = (wline ## wpos).asUInt,
      data    = io.inR(7 downto 4) ## io.inG(7 downto 4) ## io.inB(7 downto 4),
      enable  = strobeRise && inWin
    )
  }

  // ---- read side (720p pixel domain) ----
  // inLine=vc/vScale and inPix=hx/hScale are produced by INCREMENTAL phase counters, not
  // combinational dividers: a /3 divide on the 74 MHz pixel path dropped Fmax to ~32 MHz.
  val rd = new ClockingArea(pixCd) {
    val vsSync = BufferCC(io.inVsync, False)
    val vsRise = vsSync && !RegNext(vsSync).init(False)

    val hc = Reg(UInt(log2Up(hTotal) bits)) init 0
    val vc = Reg(UInt(log2Up(vTotal) bits)) init 0
    val lineEnd = hc === (hTotal - 1)
    val vWrap   = lineEnd && vc === (vTotal - 1)
    hc := Mux(lineEnd, U(0), hc + 1)
    when(lineEnd) { vc := Mux(vc === (vTotal - 1), U(0), vc + 1) }
    when(vsRise)  { vc := 0 }                       // genlock vertical to the input frame

    val de  = (hc < hActive) && (vc < vActive)
    val hsC = RegNext(hc >= (hActive + hFront) && hc < (hActive + hFront + hSync)) init False
    val vsC = RegNext(vc >= (vActive + vFront) && vc < (vActive + vFront + vSync)) init False

    // vertical: inLine += 1 every vScale output lines; 0 at frame top / genlock
    val vPhase = Reg(UInt((log2Up(vScale) max 1) bits)) init 0
    val inLine = Reg(UInt(log2Up(nLines) bits)) init 0
    when(lineEnd) {
      when(vWrap)                      { vPhase := 0; inLine := 0 }
      .elsewhen(vPhase === (vScale - 1)) { vPhase := 0; inLine := inLine + 1 }
      .otherwise                        { vPhase := vPhase + 1 }
    }
    when(vsRise) { vPhase := 0; inLine := 0 }

    // horizontal: inPix += 1 every hScale pixels inside the centred active window
    val activeH = de && (hc >= hBorder) && (hc < (hBorder + outW))
    val hPhase  = Reg(UInt((log2Up(hScale) max 1) bits)) init 0
    val inPix   = Reg(UInt(log2Up(lineMax) bits)) init 0
    when(!activeH) { hPhase := 0; inPix := 0 }
    .otherwise {
      when(hPhase === (hScale - 1)) { hPhase := 0; inPix := inPix + 1 }
      .otherwise                    { hPhase := hPhase + 1 }
    }

    val rdata = buf.readSync((inLine ## inPix).asUInt, clockCrossing = true)  // 1-cycle latency
    val show  = RegNext(activeH) init False
    val rC = Mux(show, rdata(11 downto 8) ## rdata(11 downto 8), B(0, 8 bits))
    val gC = Mux(show, rdata( 7 downto 4) ## rdata( 7 downto 4), B(0, 8 bits))
    val bC = Mux(show, rdata( 3 downto 0) ## rdata( 3 downto 0), B(0, 8 bits))
    val deC = RegNext(de) init False

    // One uniform pipeline stage on all six outputs: breaks the BRAM-read -> TMDS-encoder
    // combinational path (was the 67 MHz pixel-clock critical path) and keeps RGB aligned
    // with the sync/de signals (all now 2-cycle latency).
    io.outR     := RegNext(rC)  init 0
    io.outG     := RegNext(gC)  init 0
    io.outB     := RegNext(bC)  init 0
    io.outDe    := RegNext(deC) init False
    io.outHsync := RegNext(hsC) init False
    io.outVsync := RegNext(vsC) init False
  }
}
