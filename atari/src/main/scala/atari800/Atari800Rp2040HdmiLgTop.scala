package atari800

import spinal.core._
import spinal.lib._

// Atari 800 top for ATARI-800-QMTechCB-RP2040-STAMP-HDMI-LG base board
// with QMTECH 10CL025 core (Cyclone 10 LP).
//
// vs Atari800LgV1Top:
//  * VGA  → HDMI (DvidOut TMDS encoder + DDR serializer, bank-4 cluster)
//  * CH376T USB keyboard removed; keyboard data will come from the RP2040
//    over an SPI slave port (stubbed here — sanity-check pass).
//  * Adds RP2040 ↔ SD card SPI-mode pass-through (4 lines + card detect).
//  * Adds RP2040 ↔ Raspberry Pi Radio Module 2 SPI pass-through
//    (4 lines + IRQ + WIFI_ON + BT_ON).
//  * 13-line general-purpose RP2040 GPIO bus (most carry the SD/RM2 pass-
//    through; rest are spare).
//  * Single core LED (no base-board LED bus).
//
// Clock plan (3-output ALTPLL atari_pll, 50 MHz in):
//  c0  = 56.67 MHz  Atari system clock (×17/÷15, unchanged from V1.1)
//  c1  = 28.33 MHz  HDMI pixel clock (sys/2)
//  c2  = 141.67 MHz HDMI TMDS clock (5× pixel for DDR 10-bit serialize)
class Atari800Rp2040HdmiLgTop extends Component {
  val io = new Bundle {
    val clk_in = in Bool()

    // ----- HDMI (4× TMDS pairs, pseudo-differential LVCMOS33) -----
    val hdmi_clk_p = out Bool()
    val hdmi_clk_n = out Bool()
    val hdmi_d0_p  = out Bool()
    val hdmi_d0_n  = out Bool()
    val hdmi_d1_p  = out Bool()
    val hdmi_d1_n  = out Bool()
    val hdmi_d2_p  = out Bool()
    val hdmi_d2_n  = out Bool()

    // ----- Audio (sigma-delta 1-bit DAC) -----
    val audio_l = out Bool()
    val audio_r = out Bool()

    // ----- Joystick 1 / 2 (active low DB9) -----
    val joy1Up    = in Bool()
    val joy1Down  = in Bool()
    val joy1Left  = in Bool()
    val joy1Right = in Bool()
    val joy1Fire  = in Bool()
    val joy2Up    = in Bool()
    val joy2Down  = in Bool()
    val joy2Left  = in Bool()
    val joy2Right = in Bool()
    val joy2Fire  = in Bool()

    // ----- Console switches (active low) -----
    val consolOption = in Bool()
    val consolSelect = in Bool()
    val consolStart  = in Bool()
    val consolReset  = in Bool()

    // ----- SD card (J7, SDIO 4-bit wiring; used in SPI mode here) -----
    val sd_clk    = out Bool()
    val sd_cmd    = out Bool()                // MOSI
    val sd_dat0   = in  Bool()                // MISO
    val sd_dat1   = in  Bool()                // unused in SPI mode
    val sd_dat2   = in  Bool()                // unused in SPI mode
    val sd_dat3   = out Bool()                // CS
    val sd_cd     = in  Bool()                // card detect

    // ----- Raspberry Pi Radio Module 2 (U6) -----
    val rm2_sck     = out Bool()
    val rm2_mosi    = out Bool()
    val rm2_miso    = in  Bool()
    val rm2_cs      = out Bool()
    val rm2_irq_n   = in  Bool()
    val rm2_wifi_on = out Bool()
    val rm2_bt_on   = out Bool()

    // ----- RP2040 ↔ FPGA dedicated SPI slave port -----
    val rp_sck  = in  Bool()
    val rp_mosi = in  Bool()
    val rp_csn  = in  Bool()
    val rp_miso = out Bool()

    // ----- RP2040 GPIO bus (named per RP2040 GPIO number) -----
    //   GPIO4/5: RM2 enable lines (in to FPGA, out to RM2)
    //   GPIO10..13: SD SPI lines (CLK/MOSI/MISO/CS)
    //   GPIO14: SD card-detect (FPGA out → RP2040)
    //   GPIO15: spare
    //   GPIO20..24: RM2 SPI lines (CLK/MOSI/MISO/CS/IRQ)
    //   GPIO25: spare
    val rp_gpio4_in  = in  Bool()             // → rm2_bt_on
    val rp_gpio5_in  = in  Bool()             // → rm2_wifi_on
    val rp_gpio10_in = in  Bool()             // → sd_clk
    val rp_gpio11_in = in  Bool()             // → sd_cmd (MOSI)
    val rp_gpio12_out = out Bool()            // ← sd_dat0 (MISO)
    val rp_gpio13_in = in  Bool()             // → sd_dat3 (CS)
    val rp_gpio14_out = out Bool()            // ← sd_cd
    val rp_gpio15_out = out Bool()            // spare; drives 0
    val rp_gpio20_in = in  Bool()             // → rm2_sck
    val rp_gpio21_in = in  Bool()             // → rm2_mosi
    val rp_gpio22_out = out Bool()            // ← rm2_miso
    val rp_gpio23_in = in  Bool()             // → rm2_cs
    val rp_gpio24_out = out Bool()            // ← rm2_irq_n
    val rp_gpio25_out = out Bool()            // spare; drives 0

    // ----- Core-board user LED -----
    val led_core = out Bits(1 bits)
  }

  // =========================================================================
  // 3-output ALTPLL: 50 MHz → 56.67 / 28.33 / 141.67 MHz
  // =========================================================================
  val pll = new AtariPll
  pll.io.areset := False
  pll.io.inclk0 := io.clk_in

  val clkSys   = pll.io.c0      // 56.67 MHz Atari system
  val clkPixel = pll.io.c1      // 28.33 MHz HDMI pixel
  val clkTmds  = pll.io.c2      // 141.67 MHz HDMI TMDS (5× pixel)
  val pllLocked = pll.io.locked

  // System reset: high when PLL locks and the console-reset button is unpressed
  val sysResetN = pllLocked & io.consolReset
  val sysDomain = ClockDomain(
    clock  = clkSys,
    reset  = sysResetN,
    config = ClockDomainConfig(
      clockEdge        = RISING,
      resetKind        = ASYNC,
      resetActiveLevel = LOW
    )
  )

  val sysArea = new ClockingArea(sysDomain) {

    val colourEnable  = Reg(Bool()) init False
    val doubledEnable = Reg(Bool()) init False
    colourEnable := ~colourEnable
    when(colourEnable) { doubledEnable := ~doubledEnable }

    // -----------------------------------------------------------------
    // Atari core — same configuration as V1.1's Star Raiders build.
    // -----------------------------------------------------------------
    val atari = new Atari800CoreSimpleSdram(
      cycle_length   = 32,
      video_bits     = 8,
      palette        = 0,
      internal_rom   = 3,
      internal_ram   = 49152,
      basic_in_sdram = false,
      cartridge_rom  = "roms/Star Raiders.rom"
    )

    atari.io.PAL                       := True
    atari.io.RAM_SELECT                := B"011"
    atari.io.HALT                      := False
    atari.io.TURBO_VBLANK_ONLY         := False
    atari.io.THROTTLE_COUNT_6502       := B(31, 6 bits)
    atari.io.emulated_cartridge_select := B(0, 6 bits)
    atari.io.freezer_enable            := False
    atari.io.freezer_activate          := False
    atari.io.atari800mode              := True
    atari.io.HIRES_ENA                 := False

    atari.io.JOY1_n := io.joy1Fire ## io.joy1Right ## io.joy1Left ## io.joy1Down ## io.joy1Up
    atari.io.JOY2_n := io.joy2Fire ## io.joy2Right ## io.joy2Left ## io.joy2Down ## io.joy2Up
    atari.io.JOY3_n := B"11111"
    atari.io.JOY4_n := B"11111"

    atari.io.PADDLE0 := S(0, 8 bits)
    atari.io.PADDLE1 := S(0, 8 bits)
    atari.io.PADDLE2 := S(0, 8 bits)
    atari.io.PADDLE3 := S(0, 8 bits)
    atari.io.PADDLE4 := S(0, 8 bits)
    atari.io.PADDLE5 := S(0, 8 bits)
    atari.io.PADDLE6 := S(0, 8 bits)
    atari.io.PADDLE7 := S(0, 8 bits)

    // Keyboard: not connected yet — will be driven by the RP2040 supervisor.
    // KEYBOARD_RESPONSE is the 2-bit POKEY scan reply; report "no key pressed"
    // so the Atari core doesn't see phantom inputs.
    atari.io.KEYBOARD_RESPONSE := B"11"

    atari.io.SIO_RXD := True

    atari.io.CONSOL_OPTION := ~io.consolOption
    atari.io.CONSOL_SELECT := ~io.consolSelect
    atari.io.CONSOL_START  := ~io.consolStart

    atari.io.DMA_FETCH              := False
    atari.io.DMA_READ_ENABLE        := False
    atari.io.DMA_32BIT_WRITE_ENABLE := False
    atari.io.DMA_16BIT_WRITE_ENABLE := False
    atari.io.DMA_8BIT_WRITE_ENABLE  := False
    atari.io.DMA_ADDR               := B(0, 24 bits)
    atari.io.DMA_WRITE_DATA         := B(0, 32 bits)

    atari.io.SDRAM_REQUEST_COMPLETE := False
    atari.io.SDRAM_DO               := B(0, 32 bits)

    // -----------------------------------------------------------------
    // Scandoubler → 8-bit-per-channel RGB at ~31 kHz
    // -----------------------------------------------------------------
    val scandoubler = new Scandoubler(video_bits = 8)
    scandoubler.io.VGA                := True
    scandoubler.io.COMPOSITE_ON_HSYNC := False
    scandoubler.io.colour_enable      := colourEnable
    scandoubler.io.doubled_enable     := doubledEnable
    scandoubler.io.scanlines_on       := False
    scandoubler.io.pal                := True
    scandoubler.io.colour_in          := atari.io.VIDEO_B
    scandoubler.io.vsync_in           := atari.io.VIDEO_VS
    scandoubler.io.hsync_in           := atari.io.VIDEO_HS
    scandoubler.io.csync_in           := atari.io.VIDEO_CS

    // -----------------------------------------------------------------
    // Audio: 16-bit signed → 1-bit sigma-delta
    // -----------------------------------------------------------------
    val sigmaDeltaL = Reg(UInt(17 bits)) init 0
    val sigmaDeltaR = Reg(UInt(17 bits)) init 0
    val audioUnsignedL = (atari.io.AUDIO_L.asUInt ^ U(0x8000, 16 bits)).resize(17)
    val audioUnsignedR = (atari.io.AUDIO_R.asUInt ^ U(0x8000, 16 bits)).resize(17)
    sigmaDeltaL := sigmaDeltaL(15 downto 0).resize(17) + audioUnsignedL
    sigmaDeltaR := sigmaDeltaR(15 downto 0).resize(17) + audioUnsignedR

    // Save the scandoubler output for cross-domain consumption by DvidOut.
    // sys → pixel is a synchronous related-clock crossing (pixel = sys/2);
    // tag so SpinalHDL's CDC checker accepts it.
    val rgb_r  = scandoubler.io.R
    val rgb_g  = scandoubler.io.G
    val rgb_b  = scandoubler.io.B
    val rgb_hs = scandoubler.io.HSYNC
    val rgb_vs = scandoubler.io.VSYNC
    val rgb_de = ~atari.io.VIDEO_BLANK
    rgb_r.addTag(crossClockDomain)
    rgb_g.addTag(crossClockDomain)
    rgb_b.addTag(crossClockDomain)
    rgb_hs.addTag(crossClockDomain)
    rgb_vs.addTag(crossClockDomain)
    rgb_de.addTag(crossClockDomain)
  }

  // =========================================================================
  // HDMI / DVI output. The DvidOut module is internally clocked on its own
  // pixel + TMDS clock domains. Scandoubler signals are sourced from sysDomain
  // — synchronous-related-clocks since pixel = sys / 2 — so direct hookup is
  // safe for a sanity build. A proper async FIFO can be inserted later.
  // =========================================================================
  val dvi = new DvidOut
  dvi.io.clkPixel := clkPixel
  dvi.io.clkTmds  := clkTmds
  dvi.io.red      := sysArea.rgb_r
  dvi.io.green    := sysArea.rgb_g
  dvi.io.blue     := sysArea.rgb_b
  dvi.io.hsync    := sysArea.rgb_hs
  dvi.io.vsync    := sysArea.rgb_vs
  dvi.io.de       := sysArea.rgb_de

  io.hdmi_clk_p := dvi.io.tmdsClkP
  io.hdmi_clk_n := dvi.io.tmdsClkN
  io.hdmi_d0_p  := dvi.io.tmdsD0P
  io.hdmi_d0_n  := dvi.io.tmdsD0N
  io.hdmi_d1_p  := dvi.io.tmdsD1P
  io.hdmi_d1_n  := dvi.io.tmdsD1N
  io.hdmi_d2_p  := dvi.io.tmdsD2P
  io.hdmi_d2_n  := dvi.io.tmdsD2N

  // =========================================================================
  // RP2040 ↔ peripheral pass-throughs (direct combinational wires)
  // =========================================================================
  io.sd_clk          := io.rp_gpio10_in
  io.sd_cmd          := io.rp_gpio11_in
  io.rp_gpio12_out   := io.sd_dat0
  io.sd_dat3         := io.rp_gpio13_in
  io.rp_gpio14_out   := io.sd_cd

  io.rm2_sck         := io.rp_gpio20_in
  io.rm2_mosi        := io.rp_gpio21_in
  io.rp_gpio22_out   := io.rm2_miso
  io.rm2_cs          := io.rp_gpio23_in
  io.rp_gpio24_out   := io.rm2_irq_n
  io.rm2_bt_on       := io.rp_gpio4_in
  io.rm2_wifi_on     := io.rp_gpio5_in

  // Spare GPIO outs — drive a heartbeat / 0 so Quartus doesn't optimise them out.
  val heartbeat = Reg(UInt(24 bits)) init 0
  heartbeat := heartbeat + 1
  io.rp_gpio15_out := heartbeat.msb
  io.rp_gpio25_out := pllLocked

  // =========================================================================
  // RP2040 ↔ FPGA SPI slave (placeholder — drive MISO from heartbeat so the
  // pin doesn't get pruned; real SPI bridge will replace this).
  // =========================================================================
  val rpMisoFf = Reg(Bool()) init False
  rpMisoFf := io.rp_mosi & io.rp_sck & ~io.rp_csn
  io.rp_miso := rpMisoFf

  // -----------------------------------------------------------------
  // Audio out
  // -----------------------------------------------------------------
  io.audio_l := sysArea.sigmaDeltaL(16)
  io.audio_r := sysArea.sigmaDeltaR(16)

  // -----------------------------------------------------------------
  // Core LED — PLL lock status
  // -----------------------------------------------------------------
  io.led_core(0) := pllLocked
}

object Atari800Rp2040HdmiLgSv extends App {
  SpinalConfig(
    mode            = SystemVerilog,
    targetDirectory = "generated"
  ).generate(new Atari800Rp2040HdmiLgTop)
}
