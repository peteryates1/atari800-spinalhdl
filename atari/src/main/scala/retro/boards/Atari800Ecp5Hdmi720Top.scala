package retro.boards
import retro.common.util._
import retro.common.video._
import retro.common.scaler._
import retro.common.sdram._
import retro.link._
import retro.machines.atari._

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
  // ONE PLL for everything: the Atari sys clock (37 MHz) shares the VCO with pixel(74)/serial(370),
  // so sys:pixel is exactly 1:2 and phase-locked -> the Atari frame and 720p frame hold a fixed
  // phase (no inter-PLL jitter beating the genlock -> kills the frame-to-frame edge flicker).
  val cg = new ClkGen720             // 25 -> pixel(74), sclk, eclk, sys(37)
  cg.clk25 := io.clk_25mhz
  val clkSys = cg.sys

  // Reset: async ASSERT, SYNCHRONISED RELEASE. A raw async release of cg.locked lets the core's
  // registers leave reset on different clock edges (reset-tree skew) -> inconsistent state-machine
  // start -> boot corruption (boots in sim, hangs on hardware). Two-FF sync the release.
  val rstSyncArea = new ClockingArea(ClockDomain(clkSys, config = ClockDomainConfig(resetKind = BOOT))) {
    val r0 = RegNext(cg.locked) init False addTag(crossClockDomain)
    val r1 = RegNext(r0) init False
    val rstN = cg.locked & r1
  }
  val sysDomain = ClockDomain(clkSys, reset = rstSyncArea.rstN,
    config = ClockDomainConfig(clockEdge = RISING, resetKind = ASYNC, resetActiveLevel = LOW))
  val pixCd = ClockDomain(cg.pixel, config = ClockDomainConfig(resetKind = BOOT))

  // ---- Atari core + native video + palette (sys domain) ----
  // Strobe = 1 sample per hi-res pixel. NTSC line = 228 colour clocks = 456 hi-res px between
  // HSYNC edges, playfield centred by ANTIC -> centre the capture window: hStart=(456-inActive)/2.
  // inActive=352 (176 cc) x hScale 3 = 1056 wide, hBorder=112. vScale=3 = 240->720 genlock.
  // The Atari frame = 627228 sys = 1,254,456 pixel clocks. Choose hTotal/vTotal so that equals
  // an EXACT integer number of output lines: 1,254,456 = 1596 x 786. Then the VSYNC genlock
  // reset lands at the identical vc every frame (constant back porch) instead of a fractional
  // 760.3 lines that drifts a line every ~4 frames -> that drift was the vertical wobble.
  //   hTotal 1596 = 1280 + 88 + 40 + 188 ;  vTotal 786 = 720 + 5 + 5 + 56
  // Star Raiders' title uses the Atari WIDE playfield (192 colour clocks = 384 hi-res px,
  // centred in the line -> hires [36,420]); with the ~+6 HSYNC offset -> hStart=42, inActive=384.
  // 384x3 = 1152 wide, hBorder=64, playfield centre -> column 640. (Normal 320 content sits
  // inside this with a little border each side.)
  // vLag=4: since 1 Atari line == 3 output lines exactly (rate-locked), the reader would read a
  // line as it's written (read-during-write glitch = the "split" char); lag it 4 lines so it
  // always reads a completed line. 4 < nLines=16 -> no overwrite.
  // centred when hStart = 254 - inActive/2 (empirical): inActive=384 -> hStart=62.
  val scaler = new Hdmi720Scaler(nLines = 16, lineMax = 512, hStart = 62, inActive = 384, hScale = 3, vScale = 3, vLag = 4,
    hActive = 1280, hFront = 88, hSync = 40, hBack = 188,
    vActive = 720,  vFront = 5,  vSync = 5,  vBack = 56)
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

    io.led(0) := cg.locked
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
