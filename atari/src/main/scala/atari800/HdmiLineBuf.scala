package atari800

import spinal.core._
import spinal.lib._

// Dual-clock 2-line-buffer video re-clocker for 640x480 HDMI output.
//
// Bridges the scandoubler (sys clock, ~28.8 MHz-paced pixels) to the HDMI pixel
// clock (25 MHz) through a 2-line ping-pong buffer with per-line genlock and
// nearest-neighbour horizontal resampling. Input and output share the same
// ~31.5 kHz line / ~50 Hz frame rate, so two lines of buffering suffice — this
// removes the async-sampling shimmer without needing a full framebuffer.
//
// The write side captures each input line's active pixels; the read side, kicked
// off by the (synchronised) input HSYNC, replays them at 25 MHz into a standard
// 640x480 raster, genlocked vertically by the input VSYNC.
//
// PARAMETERS marked (TUNE) are expected to need adjustment on hardware to centre
// and size the picture — they depend on the scandoubler's exact active window.
class HdmiLineBuf(
    lineMax:       Int = 1024,
    inActiveStart: Int = 8,    // (TUNE) input-pixel index after HSYNC where active begins
    inActiveLen:   Int = 704,  // (TUNE) number of input active pixels per line
    hActive:       Int = 640,
    hSyncStart:    Int = 656,
    hSyncEnd:      Int = 752,
    hGuard:        Int = 800,   // output line length guard (25 MHz clocks per input line)
    vActive:       Int = 480,
    vSyncStart:    Int = 490,
    vSyncEnd:      Int = 492,
    vBorderTop:    Int = 0      // (TUNE) output line where active video begins
) extends Component {
  val io = new Bundle {
    val clkSys   = in  Bool()
    val clkPixel = in  Bool()
    // sys-domain input (from the scandoubler)
    val inStrobe = in  Bool()      // output-pixel advance (level; edge-detected)
    val inR      = in  Bits(8 bits)
    val inG      = in  Bits(8 bits)
    val inB      = in  Bits(8 bits)
    val inHsync  = in  Bool()      // active-high
    val inVsync  = in  Bool()      // active-high
    // pixel-domain output (to DvidOut)
    val outR     = out Bits(8 bits)
    val outG     = out Bits(8 bits)
    val outB     = out Bits(8 bits)
    val outHsync = out Bool()
    val outVsync = out Bool()
    val outDe    = out Bool()
  }

  // Dual-clock line buffer: 2 banks x lineMax, 12-bit RGB444 (kept to 12 bits so
  // it fits the 10CL025's scarce M9K blocks — the top 4 bits per channel are
  // replicated back to 8 on read, visually near-lossless for Atari content).
  // Written in the sys domain, read in the pixel domain (dual-clock RAM).
  val buf = Mem(Bits(12 bits), 2 * lineMax)

  val sysCd = ClockDomain(io.clkSys,   config = ClockDomainConfig(resetKind = BOOT))
  val pixCd = ClockDomain(io.clkPixel, config = ClockDomainConfig(resetKind = BOOT))

  // -------------------------------------------------------------------------
  // Write side (sys domain)
  // -------------------------------------------------------------------------
  val wr = new ClockingArea(sysCd) {
    val strobeD    = RegNext(io.inStrobe) init False
    val strobeRise = io.inStrobe && !strobeD
    val hsD        = RegNext(io.inHsync) init False
    val hsRise     = io.inHsync && !hsD

    val wbank = Reg(Bool()) init False
    val wx    = Reg(UInt(11 bits)) init 0

    when(strobeRise && wx =/= (lineMax - 1)) { wx := wx + 1 }
    when(hsRise) {
      wbank := !wbank
      wx    := 0
    }

    val active = wx >= inActiveStart && wx < (inActiveStart + inActiveLen)
    val waddr  = (wbank ## (wx - inActiveStart).resize(10 bits)).asUInt
    buf.write(
      address = waddr,
      data    = io.inR(7 downto 4) ## io.inG(7 downto 4) ## io.inB(7 downto 4),
      enable  = strobeRise && active
    )
  }

  // -------------------------------------------------------------------------
  // Read side (pixel domain, 25 MHz)
  // -------------------------------------------------------------------------
  val rd = new ClockingArea(pixCd) {
    val hsSync = BufferCC(io.inHsync, False)
    val vsSync = BufferCC(io.inVsync, False)
    val hsD    = RegNext(hsSync) init False
    val vsD    = RegNext(vsSync) init False
    val hsRise = hsSync && !hsD
    val vsRise = vsSync && !vsD

    val rbank      = Reg(Bool()) init True   // reads the bank the writer just finished
    val x          = Reg(UInt(11 bits)) init 0
    val y          = Reg(UInt(10 bits)) init 0
    val lineActive = Reg(Bool()) init False

    // Horizontal nearest-neighbour DDA: map hActive outputs -> inActiveLen inputs.
    val acc = Reg(UInt(11 bits)) init 0
    val inX = Reg(UInt(11 bits)) init 0

    when(hsRise) {
      x          := 0
      acc        := 0
      inX        := 0
      rbank      := !rbank
      lineActive := True
      y          := y + 1
    }
    when(vsRise) { y := 0 }

    when(lineActive) {
      when(x =/= (hGuard - 1)) { x := x + 1 } otherwise { lineActive := False }
      when(x < hActive) {
        acc := acc + inActiveLen
        when(acc + inActiveLen >= hActive) {
          acc := acc + inActiveLen - hActive
          inX := inX + 1
        }
      }
    }

    val raddr = (rbank ## inX.resize(10 bits)).asUInt
    // Intentional dual-clock RAM: write in sys domain, read here in pixel domain.
    val rdata = buf.readSync(raddr, clockCrossing = true)   // 1-cycle latency

    val deH = lineActive && x < hActive
    val deV = y >= vBorderTop && y < (vBorderTop + vActive)

    // Register outputs to line up with the readSync data latency.
    io.outR     := rdata(11 downto 8) ## rdata(11 downto 8)   // 4->8 by replication
    io.outG     := rdata( 7 downto 4) ## rdata( 7 downto 4)
    io.outB     := rdata( 3 downto 0) ## rdata( 3 downto 0)
    io.outDe    := RegNext(deH && deV)                       init False
    io.outHsync := RegNext(x >= hSyncStart && x < hSyncEnd)  init False
    io.outVsync := RegNext(y >= vSyncStart && y < vSyncEnd)  init False
  }
}
