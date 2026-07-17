package atari800

import spinal.core._
import spinal.lib._

// Atari 800 -> 720p HDMI on the ECP5 (i5). Atari core (48K BRAM, 800 OS, Star Raiders
// cart) at 37.5 MHz -> native GTIA video -> GtiaPalette -> Hdmi720Scaler (genlocked 3x
// BRAM upscaler) -> Ecp5DvidOutX2 (ODDRX2F) -> LVCMOS33D HDMI. Two PLLs: PllAtari800
// (25->37.5) for the core, ecp5_clkgen720 (25->370/74 + ECLK/SCLK) for HDMI. The scaler
// genlocks the (free-running) 720p raster to the Atari VSYNC.
class Atari800Ecp5Hdmi720Top extends Component {
  val io = new Bundle {
    val clk_25mhz = in  Bool()
    val gpdi      = out Bits(4 bits)   // P pins; LVCMOS33D drives the matched N
    val led       = out Bits(4 bits)
  }
  noIoPrefix()

  // ---- clocks ----
  val pllA = new PllEcp5              // PllAtari800: 25 -> 37.5 (c0)
  pllA.io.inclk0 := io.clk_25mhz
  val clkSys = pllA.io.c0

  val cg = new ClkGen720             // 25 -> pixel(74), sclk, eclk
  cg.clk25 := io.clk_25mhz

  // Reset: async ASSERT, SYNCHRONISED RELEASE. A raw async release of pllA.locked lets the
  // core's registers leave reset on different clock edges (reset-tree skew) -> inconsistent
  // state-machine start -> boot corruption (boots in sim, hangs on hardware). Two-FF sync
  // the release in a BOOT-reset domain.
  val rstSyncArea = new ClockingArea(ClockDomain(clkSys, config = ClockDomainConfig(resetKind = BOOT))) {
    val r0 = RegNext(pllA.io.locked) init False addTag(crossClockDomain)
    val r1 = RegNext(r0) init False
    val rstN = pllA.io.locked & r1
  }
  val sysDomain = ClockDomain(clkSys, reset = rstSyncArea.rstN,
    config = ClockDomainConfig(clockEdge = RISING, resetKind = ASYNC, resetActiveLevel = LOW))
  val pixCd = ClockDomain(cg.pixel, config = ClockDomainConfig(resetKind = BOOT))

  // ---- Atari core + native video + palette (sys domain) ----
  // Strobe = 1 sample per hi-res pixel. NTSC line = 228 colour clocks = 456 hi-res px between
  // HSYNC edges, playfield centred by ANTIC -> centre the capture window: hStart=(456-inActive)/2.
  // inActive=352 (176 cc) x hScale 3 = 1056 wide, hBorder=112. vScale=3 = 240->720 genlock.
  val scaler = new Hdmi720Scaler(nLines = 16, lineMax = 512, hStart = 74, inActive = 360, hScale = 3, vScale = 3)
  scaler.io.clkSys   := clkSys
  scaler.io.clkPixel := cg.pixel

  val sysArea = new ClockingArea(sysDomain) {
    val atari = new Atari800CoreSimpleSdram(
      cycle_length = 21, video_bits = 8, palette = 0, internal_rom = 3,
      internal_ram = 49152, basic_in_sdram = false, cartridge_rom = "roms/Star Raiders.rom")
    atari.io.PAL := False; atari.io.RAM_SELECT := B"011"; atari.io.HALT := False  // NTSC: 60 Hz to match 720p60 genlock
    atari.io.TURBO_VBLANK_ONLY := False; atari.io.THROTTLE_COUNT_6502 := B(20, 6 bits)
    atari.io.emulated_cartridge_select := B(0, 6 bits)
    atari.io.freezer_enable := False; atari.io.freezer_activate := False
    atari.io.atari800mode := True; atari.io.HIRES_ENA := False
    atari.io.JOY1_n := B"11111"; atari.io.JOY2_n := B"11111"
    atari.io.JOY3_n := B"11111"; atari.io.JOY4_n := B"11111"
    for (p <- Seq(atari.io.PADDLE0, atari.io.PADDLE1, atari.io.PADDLE2, atari.io.PADDLE3,
                  atari.io.PADDLE4, atari.io.PADDLE5, atari.io.PADDLE6, atari.io.PADDLE7)) p := S(0, 8 bits)
    atari.io.KEYBOARD_RESPONSE := B"11"; atari.io.SIO_RXD := True
    atari.io.CONSOL_OPTION := False; atari.io.CONSOL_SELECT := False; atari.io.CONSOL_START := False
    atari.io.DMA_FETCH := False; atari.io.DMA_READ_ENABLE := False
    atari.io.DMA_32BIT_WRITE_ENABLE := False; atari.io.DMA_16BIT_WRITE_ENABLE := False
    atari.io.DMA_8BIT_WRITE_ENABLE := False; atari.io.DMA_ADDR := B(0, 24 bits)
    atari.io.DMA_WRITE_DATA := B(0, 32 bits)
    // Sever SDRAM: complete every request immediately so the CPU never stalls (RAM/ROM are
    // all in BRAM; SDRAM data is unused). `:= False` hangs the CPU on the first SDRAM access.
    atari.io.SDRAM_REQUEST_COMPLETE := atari.io.SDRAM_REQUEST; atari.io.SDRAM_DO := B(0, 32 bits)

    // Capture one sample per real Atari HI-RES pixel using the core's own hi-res colour clock:
    // perfectly phase-aligned, so no undersampling/aliasing (the text jitter) and no drift.
    // colourEnable (sys/2) oversampled & drifted; no integer sys/N divider hits the 7.16 MHz
    // pixel rate exactly from 37.5 MHz (a wrong rate aliases and compresses the image).
    val pixStrobe = atari.io.dbgColourClockHighres

    // VIDEO_B is the 8-bit GTIA colour INDEX (VIDEO_R/G/B are ~0 with palette=0); resolve it
    // to RGB via GtiaPalette on the write side (like the working Rp2040 top does on read).
    val pal = new GtiaPalette
    pal.io.atariColour := atari.io.VIDEO_B
    pal.io.pal := True
    scaler.io.inStrobe := pixStrobe
    scaler.io.inR := pal.io.rNext
    scaler.io.inG := pal.io.gNext
    scaler.io.inB := pal.io.bNext
    scaler.io.inHsync := atari.io.VIDEO_HS
    scaler.io.inVsync := atari.io.VIDEO_VS

    io.led(0) := pllA.io.locked
    io.led(1) := ~atari.io.VIDEO_BLANK
    io.led(2) := cg.locked
    io.led(3) := False
  }

  // ---- 720p serializer (pixel domain) ----
  val ser = new Ecp5DvidOutX2
  ser.io.clkPixel := cg.pixel
  ser.io.clkSclk  := cg.sclk
  ser.io.red   := scaler.io.outR
  ser.io.green := scaler.io.outG
  ser.io.blue  := scaler.io.outB
  ser.io.hsync := scaler.io.outHsync
  ser.io.vsync := scaler.io.outVsync
  ser.io.de    := scaler.io.outDe

  val oP = new Oddrx2x4
  oP.nib := ser.io.nibbles; oP.eclk := cg.eclk; oP.sclk := cg.sclk
  io.gpdi := oP.q
}

object Atari800Ecp5Hdmi720Sv extends App {
  SpinalConfig(mode = SystemVerilog, targetDirectory = "generated")
    .generate(new Atari800Ecp5Hdmi720Top)
}
