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

    // ----- SDRAM (QMTech 10CL025 on-module, 16-bit) -----
    val sdram_clk  = out Bool()
    val sdram_cke  = out Bool()
    val sdram_csn  = out Bool()
    val sdram_rasn = out Bool()
    val sdram_casn = out Bool()
    val sdram_wen  = out Bool()
    val sdram_ba   = out Bits(2 bits)
    val sdram_addr = out Bits(13 bits)
    val sdram_dqm  = out Bits(2 bits)
    val sdram_dq   = inout(Analog(Bits(16 bits)))
  }

  // =========================================================================
  // 3-output ALTPLL: 50 MHz → 56.67 / 28.33 / 141.67 MHz
  // =========================================================================
  val pll = new AtariPll
  pll.io.areset := False
  pll.io.inclk0 := io.clk_in

  val clkSys   = pll.io.c0      // 56.67 MHz Atari system

  // HDMI PLL (pll_hdmi.v): 50 MHz -> 74.25 MHz pixel + 371.25 MHz TMDS (720p).
  val hdmiPll = new PllHdmi
  hdmiPll.inclk0 := io.clk_in
  val clkPixel = hdmiPll.c0     // 74.25 MHz 720p pixel clock
  val clkTmds  = hdmiPll.c1     // 371.25 MHz TMDS (5x pixel)

  // Second PLL (QMTech-proven pll.v): 50 MHz -> 100 MHz for the SDRAM domain.
  val sdramPll = new SdramPll
  sdramPll.areset := False
  sdramPll.inclk0 := io.clk_in
  val clkSdram = sdramPll.c0     // 100 MHz SDRAM controller clock
  io.sdram_clk := sdramPll.c1    // dedicated 100 MHz clock to the SDRAM chip
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
      internal_ram   = 0,          // all Atari RAM in SDRAM (frees BRAM)
      basic_in_sdram = false,
      cartridge_rom  = "roms/Star Raiders.rom"
    )

    atari.io.PAL                       := True
    atari.io.RAM_SELECT                := B"011"
    // atari.io.HALT is driven below (held until SDRAM init completes)
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

    // -----------------------------------------------------------------
    // SDRAM controller — Atari RAM lives in SDRAM (internal_ram=0).
    // Sole master (no JOP): the MiST/AC608 pattern. SdramStatemachine
    // handles the CLK_SYSTEM(57.69) <-> CLK_SDRAM(100) crossing internally.
    // -----------------------------------------------------------------
    // Hold controller reset low for SDRAM power-up (~568 us @57.69 MHz).
    val sdramPor = Reg(UInt(16 bits)) init 0
    when(sdramPor =/= sdramPor.maxValue) { sdramPor := sdramPor + 1 }

    val sdramCtrl = new SdramStatemachine(
      ADDRESS_WIDTH = 24, ROW_WIDTH = 13, COLUMN_WIDTH = 9, AP_BIT = 10
    )
    sdramCtrl.io.CLK_SYSTEM      := clkSys
    sdramCtrl.io.CLK_SDRAM       := clkSdram
    sdramCtrl.io.RESET_N         := sysResetN & sdramPor.msb
    // 3-port SDRAM arbiter: A = Atari RAM (priority), B = framebuffer write,
    // C = framebuffer read (720p scaler). B/C are latency-tolerant.
    val arb = new SdramArbiter3

    // Port A — Atari core
    arb.io.a.request        := atari.io.SDRAM_REQUEST
    arb.io.a.readEnable     := atari.io.SDRAM_READ_ENABLE
    arb.io.a.writeEnable    := atari.io.SDRAM_WRITE_ENABLE
    arb.io.a.addr           := B"0" ## atari.io.SDRAM_ADDR
    arb.io.a.dataIn         := atari.io.SDRAM_DI
    arb.io.a.byteAccess     := atari.io.SDRAM_8BIT_WRITE_ENABLE
    arb.io.a.wordAccess     := atari.io.SDRAM_16BIT_WRITE_ENABLE
    arb.io.a.longwordAccess := atari.io.SDRAM_32BIT_WRITE_ENABLE
    arb.io.a.refresh        := atari.io.SDRAM_REFRESH
    atari.io.SDRAM_REQUEST_COMPLETE := arb.io.a.complete
    atari.io.SDRAM_DO               := arb.io.a.dataOut

    // Port B — framebuffer write: capture raw Atari video (8-bit GTIA index)
    // fbBase must be ABOVE the Atari's RAM in SDRAM (internal_ram=0 -> RAM at
    // low addresses). 0x100000 = 1 MB, well clear of the Atari's 64 KB.
    val fbWrite = new VideoFbWrite(fbBase = 0x100000, width = 384, strideLog2 = 9, height = 288, addrWidth = 24, debugFill = true)
    fbWrite.io.pixStrobe := colourEnable
    fbWrite.io.colour    := atari.io.VIDEO_B
    fbWrite.io.hsync     := atari.io.VIDEO_HS
    fbWrite.io.vsync     := atari.io.VIDEO_VS
    fbWrite.io.blank     := atari.io.VIDEO_BLANK
    arb.io.b.request        := fbWrite.io.wrReq
    fbWrite.io.wrComplete   := arb.io.b.complete
    arb.io.b.readEnable     := False
    arb.io.b.writeEnable    := True
    arb.io.b.addr           := fbWrite.io.wrAddr
    arb.io.b.dataIn         := fbWrite.io.wrData
    arb.io.b.byteAccess     := fbWrite.io.wrByte
    arb.io.b.wordAccess     := False
    arb.io.b.longwordAccess := False

    // Port C — framebuffer read/scaler (dual-clock: fetch in sys, output at pixel)
    val fbRead = new VideoFbRead2(
      srcW = 384, srcH = 288, strideLog2 = 9, fbBase = 0x100000,
      hActive = 1280, hFront = 110, hSync = 40, hBack = 220,
      vActive = 720,  vFront = 5,   vSync = 5,  vBack = 20, addrWidth = 24)
    fbRead.io.clkFetch := clkSys
    fbRead.io.clkPixel := clkPixel
    // Same ready condition as the SDRAM controller's reset: no fetch requests
    // until the arbiter + SDRAM are live (BufferCC inside fbRead syncs it).
    fbRead.io.enable   := sysResetN & sdramPor.msb
    arb.io.c.request        := fbRead.io.rdReq
    fbRead.io.rdComplete    := arb.io.c.complete
    arb.io.c.readEnable     := True
    arb.io.c.writeEnable    := False
    arb.io.c.addr           := fbRead.io.rdAddr
    arb.io.c.dataIn         := B(0, 32 bits)
    arb.io.c.byteAccess     := False
    arb.io.c.wordAccess     := False
    arb.io.c.longwordAccess := True
    fbRead.io.rdData        := arb.io.c.dataOut

    // Arbiter -> SdramStatemachine
    sdramCtrl.io.READ_EN         := arb.io.sdram.readEnable
    sdramCtrl.io.WRITE_EN        := arb.io.sdram.writeEnable
    sdramCtrl.io.REQUEST         := arb.io.sdram.request
    sdramCtrl.io.BYTE_ACCESS     := arb.io.sdram.byteAccess
    sdramCtrl.io.WORD_ACCESS     := arb.io.sdram.wordAccess
    sdramCtrl.io.LONGWORD_ACCESS := arb.io.sdram.longwordAccess
    sdramCtrl.io.REFRESH         := arb.io.sdram.refresh
    sdramCtrl.io.ADDRESS_IN      := arb.io.sdram.addr
    sdramCtrl.io.DATA_IN         := arb.io.sdram.dataIn
    arb.io.sdram.complete := sdramCtrl.io.COMPLETE
    arb.io.sdram.dataOut  := sdramCtrl.io.DATA_OUT

    // Hold the Atari CPU halted until SDRAM init completes so its first RAM
    // accesses (page zero / stack / OS RAM clear) don't hit uninitialised SDRAM.
    val sdramReady = BufferCC(sdramCtrl.io.reset_client_n, False)
    atari.io.HALT := ~sdramReady

    // DEBUG: heartbeat that only ticks once SDRAM init completes.
    //   blink  => reset_client_n asserted (SDRAM init OK, Atari released)
    //   steady => init never completes (SDRAM/clocking/handshake problem)
    val dbgHb = Reg(UInt(24 bits)) init 0
    when(sdramReady) { dbgHb := dbgHb + 1 }

    // SDRAM pin wiring
    io.sdram_addr := sdramCtrl.io.SDRAM_ADDR
    io.sdram_ba   := sdramCtrl.io.SDRAM_BA1 ## sdramCtrl.io.SDRAM_BA0
    io.sdram_cke  := sdramCtrl.io.SDRAM_CKE
    io.sdram_csn  := sdramCtrl.io.SDRAM_CS_N
    io.sdram_rasn := sdramCtrl.io.SDRAM_RAS_N
    io.sdram_casn := sdramCtrl.io.SDRAM_CAS_N
    io.sdram_wen  := sdramCtrl.io.SDRAM_WE_N
    io.sdram_dqm  := sdramCtrl.io.SDRAM_udqm ## sdramCtrl.io.SDRAM_ldqm
    sdramCtrl.io.SDRAM_DQ_IN := io.sdram_dq
    when(sdramCtrl.io.SDRAM_DQ_OE) { io.sdram_dq := sdramCtrl.io.SDRAM_DQ_OUT }

    // Video now goes Atari -> VideoFbWrite -> SDRAM framebuffer -> VideoFbRead2
    // (720p scaler) -> GtiaPalette -> DvidOut. No scandoubler in the HDMI path.

    // -----------------------------------------------------------------
    // Audio: 16-bit signed → 1-bit sigma-delta
    // -----------------------------------------------------------------
    val sigmaDeltaL = Reg(UInt(17 bits)) init 0
    val sigmaDeltaR = Reg(UInt(17 bits)) init 0
    val audioUnsignedL = (atari.io.AUDIO_L.asUInt ^ U(0x8000, 16 bits)).resize(17)
    val audioUnsignedR = (atari.io.AUDIO_R.asUInt ^ U(0x8000, 16 bits)).resize(17)
    sigmaDeltaL := sigmaDeltaL(15 downto 0).resize(17) + audioUnsignedL
    sigmaDeltaR := sigmaDeltaR(15 downto 0).resize(17) + audioUnsignedR

    // Framebuffer read/scaler pixel-domain outputs (crossed to DvidOut below).
    val vidPix = fbRead.io.pix
    val vidDe  = fbRead.io.de
    val vidHs  = fbRead.io.hs
    val vidVs  = fbRead.io.vs
    vidPix.addTag(crossClockDomain)
    vidDe.addTag(crossClockDomain)
    vidHs.addTag(crossClockDomain)
    vidVs.addTag(crossClockDomain)
  }

  // =========================================================================
  // HDMI 720p output: the framebuffer scaler's 8-bit GTIA colour index goes
  // through GtiaPalette -> DvidOut (own pixel + TMDS clock domains). Video is
  // fully re-clocked through the SDRAM framebuffer, so no async shimmer.
  // =========================================================================
  // Palette + a pixel-domain pipeline register so DvidOut's TMDS encoder gets
  // REGISTERED RGB (as the reference does) — splits the cache-read + palette LUT
  // path from the 8b/10b encoder so both meet the 74.25 MHz pixel clock.
  val pixelArea = new ClockingArea(ClockDomain(clkPixel, config = ClockDomainConfig(resetKind = BOOT))) {
    // Real framebuffer path: palette the scaled Atari pixel; sync/DE come from
    // VideoFbRead2 (now active-low). One pixel-domain register so the encoder
    // gets registered RGB, matched by de/hs/vs delays.
    val palette = new GtiaPalette
    palette.io.atariColour := sysArea.vidPix
    palette.io.pal         := True
    val r  = RegNext(palette.io.rNext)
    val g  = RegNext(palette.io.gNext)
    val b  = RegNext(palette.io.bNext)
    val de = RegNext(sysArea.vidDe) init False
    val hs = RegNext(sysArea.vidHs) init False
    val vs = RegNext(sysArea.vidVs) init False
    // Power-on reset for the DVI encoder (held low ~256 pixel clocks).
    val por = Reg(UInt(9 bits)) init 0
    when(por =/= por.maxValue) { por := por + 1 }
    val rstN = por.msb
  }

  // Proven corecourse 720p encoder (replaces our DvidOut, which fails at 371 MHz).
  val dvi = new DviEncoder
  dvi.pixelclk   := clkPixel
  dvi.pixelclk5x := clkTmds
  dvi.rst_n      := pixelArea.rstN
  dvi.red_din    := pixelArea.r
  dvi.green_din  := pixelArea.g
  dvi.blue_din   := pixelArea.b
  dvi.hsync      := pixelArea.hs
  dvi.vsync      := pixelArea.vs
  dvi.de         := pixelArea.de

  io.hdmi_clk_p := dvi.tmds_clk_p
  io.hdmi_clk_n := dvi.tmds_clk_n
  io.hdmi_d0_p  := dvi.tmds_data_p(0)   // blue
  io.hdmi_d0_n  := dvi.tmds_data_n(0)
  io.hdmi_d1_p  := dvi.tmds_data_p(1)   // green
  io.hdmi_d1_n  := dvi.tmds_data_n(1)
  io.hdmi_d2_p  := dvi.tmds_data_p(2)   // red
  io.hdmi_d2_n  := dvi.tmds_data_n(2)

  // =========================================================================
  // RP2040 ↔ peripheral pass-throughs (direct combinational wires)
  // =========================================================================
  io.sd_clk          := io.rp_gpio10_in
  io.sd_cmd          := io.rp_gpio11_in
  io.sd_dat3         := io.rp_gpio13_in
  io.rm2_sck         := io.rp_gpio20_in
  io.rm2_mosi        := io.rp_gpio21_in
  io.rm2_cs          := io.rp_gpio23_in
  io.rm2_bt_on       := io.rp_gpio4_in
  io.rm2_wifi_on     := io.rp_gpio5_in

  // DEBUG: route the fb read/write SDRAM handshake onto the RP2040 GPIO for the
  // logic analyzer. rp_gpioN -> RP2040 GPION -> LA channel (N-2, INPUT_PIN_BASE=2).
  // NOTE: LA channels 22/23 (RP2040 GPIO24/25) read garbage — GPIO25 is the
  // LA firmware's LED, GPIO24 dead (VBUS-sense on a stock Pico). Only use
  // ch10/12/13/20; ch10 carries wrReq (a known toggler) to validate itself.
  io.rp_gpio12_out := sysArea.fbWrite.io.wrReq             // GPIO12 = LA ch10  wrReq (trigger/pin check)
  io.rp_gpio14_out := sysArea.fbRead.io.dbgLateTgl         // GPIO14 = LA ch12  toggles per wrong-row line (artifact meter)
  io.rp_gpio15_out := sysArea.fbRead.io.dbgFrameTgl        // GPIO15 = LA ch13  toggles per frame (rate reference)
  io.rp_gpio22_out := sysArea.fbRead.io.dbgBusy            // GPIO22 = LA ch20  fetch busy (live)
  io.rp_gpio24_out := sysArea.fbRead.io.dbgBusy            // GPIO24 = LA ch22  (dead channel)
  io.rp_gpio25_out := sysArea.fbRead.io.dbgBeat            // GPIO25 = LA ch23  (dead channel)

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
  // DEBUG: heartbeat off the 371.25 MHz TMDS clock — blink => clkTmds alive.
  val tmdsArea = new ClockingArea(ClockDomain(clkTmds, config = ClockDomainConfig(resetKind = BOOT))) {
    val hb = Reg(UInt(30 bits)) init 0
    hb := hb + 1
  }
  // DEBUG: LED = fb-write FIFO overflow (sticky). In debugFill mode writes are
  // continuous: LED ON => port-B writes never complete (arbiter/SDRAM stuck);
  // LED OFF => writes ARE draining, so the fault is the read/fetch side.
  io.led_core(0) := sysArea.fbWrite.io.overflow
}

object Atari800Rp2040HdmiLgSv extends App {
  SpinalConfig(
    mode            = SystemVerilog,
    targetDirectory = "generated"
  ).generate(new Atari800Rp2040HdmiLgTop)
}
