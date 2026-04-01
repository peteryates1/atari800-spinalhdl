package atari800

import spinal.core._
import spinal.lib._
import spinal.lib.bus.bmb._
import jop.system._
import jop.io._

// Atari 800 + JOP top for QMTECH EP4CGX150 + DB_FPGA daughter board.
// Single-PLL design: JOP and Atari both at 56.67 MHz (atari_pll).
//
// JOP: 56.67 MHz, serial boot via UART at 500k baud.
//      Devices: uart, sdSpi (CH376S), vgaTextOverlay, atariCtrl.
// Atari: 56.67 MHz, all RAM/ROM via shared SDRAM.
//
// Shared SDRAM: SdramArbiter — Atari (priority, Port A) + JOP (Port B).
// JOP loads OS ROM + cartridge into Atari SDRAM region before releasing Atari.
// Single clock domain: no CDC needed between JOP and Atari.
// VGA text overlay runs in sys clock using scandoubler timing — no 25 MHz pixel clock.
class Atari800Ep4cgx150DualPllTop extends Component {
  val io = new Bundle {
    // 50 MHz oscillator (PIN_B14)
    val clk_in = in Bool()

    // VGA — DB_FPGA 5-6-5 resistor DAC
    val vga_hs  = out Bool()
    val vga_vs  = out Bool()
    val vga_r   = out Bits(5 bits)
    val vga_g   = out Bits(6 bits)
    val vga_b   = out Bits(5 bits)

    // UART — DB_FPGA CP2102N (JOP serial boot + debug)
    val uartTx  = out Bool()
    val uartRx  = in  Bool()

    // SD Card — DB_FPGA onboard microSD slot (SPI mode)
    val sdClk   = out Bool()   // CLK  (J3:9,  B21)
    val sdCmd   = out Bool()   // CMD  (J3:10, A22) — MOSI in SPI mode
    val sdDat0  = in  Bool()   // DAT0 (J3:8,  A23) — MISO in SPI mode
    val sdDat3  = out Bool()   // DAT3 (J3:11, C19) — CS   in SPI mode
    val sdCd    = in  Bool()   // CD   (J3:6,  B22) — card detect (active low)

    // SDRAM — W9825G6JH6 (32 MB, 16-bit)
    val sdramAddr = out Bits(13 bits)
    val sdramBa   = out Bits(2 bits)
    val sdramDq   = inout(Analog(Bits(16 bits)))
    val sdramDqm  = out Bits(2 bits)
    val sdramCke  = out Bool()
    val sdramCsN  = out Bool()
    val sdramRasN = out Bool()
    val sdramCasN = out Bool()
    val sdramWeN  = out Bool()
    val sdramClk  = out Bool()

    // Joystick 1 — PMOD J10 pins 1-4,7 (active low, DB-9)
    val joy1Up    = in Bool()
    val joy1Down  = in Bool()
    val joy1Left  = in Bool()
    val joy1Right = in Bool()
    val joy1Fire  = in Bool()

    // LEDs on core board (active high)
    val led     = out Bits(2 bits)
  }

  // =========================================================================
  // PLL: AtariPll — 50 MHz -> 56.67 MHz (×17/÷15) for both JOP and Atari
  // c0=56.67 MHz, c1=56.67 MHz (-3ns for SDRAM)
  // (c3=25 MHz available but unused — overlay runs at sys clock)
  // =========================================================================
  val pll = new AtariPll
  pll.io.areset := False
  pll.io.inclk0 := io.clk_in

  val clkSys    = pll.io.c0      // 56.67 MHz — main clock for JOP + Atari
  val pllLocked = pll.io.locked

  io.sdramClk := pll.io.c1        // 56.67 MHz, -3ns phase shift for SDRAM

  // (CH376T removed — using onboard SD card slot instead)

  // =========================================================================
  // Clock Domains
  // =========================================================================

  // Main domain — 56.67 MHz, SYNC reset (shared by JOP and Atari)
  val sysReset = ResetGenerator(pllLocked, clkSys)
  val sysDomain = ClockDomain(
    clock     = clkSys,
    reset     = sysReset,
    frequency = FixedFrequency(56670000 Hz),
    config    = ClockDomainConfig(resetKind = SYNC, resetActiveLevel = HIGH)
  )

  // Atari domain — same clock, active-LOW reset (matches Atari core conventions)
  // Gated by holdReset (JOP holds Atari in reset until SDRAM is loaded) and coldReset (pulse)
  // These are wired after jopArea is created (forward reference resolved by SpinalHDL)
  val atariHoldReset = Bool()
  val atariColdReset = Bool()
  val atariResetN = pllLocked & ~atariHoldReset & ~atariColdReset
  val atariDomain = ClockDomain(
    clock     = clkSys,
    reset     = atariResetN,
    frequency = FixedFrequency(56670000 Hz),
    config    = ClockDomainConfig(resetKind = SYNC, resetActiveLevel = LOW)
  )

  // =========================================================================
  // JOP — 56.67 MHz domain
  // =========================================================================
  val jopArea = new ClockingArea(sysDomain) {

    val jopConfig = JopCoreForAtariDualPll.config
    val memDevice = JopCoreForAtariDualPll.memDevice

    val cluster = JopCluster(
      cpuCnt     = 1,
      baseConfig = jopConfig,
      romInit    = Some(JopCoreForAtariDualPll.hwRomInit),
      ramInit    = Some(JopCoreForAtariDualPll.simRamInit),
      jbcInit    = Some(Seq.fill(2048)(BigInt(0))),
      vgaCd      = None
    )

    // BmbToSdramReq — JOP BMB -> SDRAM request protocol
    val bmbBridge = BmbToSdramReq(jopConfig.memConfig.bmbParameter)
    bmbBridge.io.bmb <> cluster.io.bmb

    // SDRAM Arbiter — Atari (priority, Port A) + JOP (Port B)
    val arbiter = new SdramArbiter

    // Port B: JOP (via BmbToSdramReq bridge)
    arbiter.io.b.request        := bmbBridge.io.request
    arbiter.io.b.readEnable     := bmbBridge.io.readEnable
    arbiter.io.b.writeEnable    := bmbBridge.io.writeEnable
    arbiter.io.b.addr           := bmbBridge.io.addr
    arbiter.io.b.dataIn         := bmbBridge.io.dataIn
    arbiter.io.b.byteAccess     := bmbBridge.io.byteAccess
    arbiter.io.b.wordAccess     := bmbBridge.io.wordAccess
    arbiter.io.b.longwordAccess := bmbBridge.io.longwordAccess
    bmbBridge.io.complete       := arbiter.io.b.complete
    bmbBridge.io.dataOut        := arbiter.io.b.dataOut

    // SDRAM Controller — SdramStatemachine (Atari-native, shared by both masters)
    val sdramCtrl = new SdramStatemachine(
      ADDRESS_WIDTH = 24,
      AP_BIT        = 10,
      COLUMN_WIDTH  = 9,
      ROW_WIDTH     = 13
    )
    sdramCtrl.io.CLK_SYSTEM := clkSys
    sdramCtrl.io.CLK_SDRAM  := pll.io.c1
    sdramCtrl.io.RESET_N    := pllLocked

    // Arbiter -> SDRAM controller
    sdramCtrl.io.REQUEST         := arbiter.io.sdram.request
    sdramCtrl.io.READ_EN         := arbiter.io.sdram.readEnable
    sdramCtrl.io.WRITE_EN        := arbiter.io.sdram.writeEnable
    sdramCtrl.io.BYTE_ACCESS     := arbiter.io.sdram.byteAccess
    sdramCtrl.io.WORD_ACCESS     := arbiter.io.sdram.wordAccess
    sdramCtrl.io.LONGWORD_ACCESS := arbiter.io.sdram.longwordAccess
    sdramCtrl.io.REFRESH         := arbiter.io.sdram.refresh
    sdramCtrl.io.ADDRESS_IN      := arbiter.io.sdram.addr
    sdramCtrl.io.DATA_IN         := arbiter.io.sdram.dataIn

    // SDRAM controller -> Arbiter
    arbiter.io.sdram.complete := sdramCtrl.io.COMPLETE
    arbiter.io.sdram.dataOut  := sdramCtrl.io.DATA_OUT

    // Stall JOP until SDRAM init completes
    bmbBridge.io.sdramReady := sdramCtrl.io.reset_client_n

    // SDRAM physical pins
    io.sdramAddr   := sdramCtrl.io.SDRAM_ADDR
    io.sdramBa(0)  := sdramCtrl.io.SDRAM_BA0
    io.sdramBa(1)  := sdramCtrl.io.SDRAM_BA1
    io.sdramCsN    := sdramCtrl.io.SDRAM_CS_N
    io.sdramRasN   := sdramCtrl.io.SDRAM_RAS_N
    io.sdramCasN   := sdramCtrl.io.SDRAM_CAS_N
    io.sdramWeN    := sdramCtrl.io.SDRAM_WE_N
    io.sdramCke    := sdramCtrl.io.SDRAM_CKE
    io.sdramDqm(0) := sdramCtrl.io.SDRAM_ldqm
    io.sdramDqm(1) := sdramCtrl.io.SDRAM_udqm
    sdramCtrl.io.SDRAM_DQ_IN := io.sdramDq
    when(sdramCtrl.io.SDRAM_DQ_OE) {
      io.sdramDq := sdramCtrl.io.SDRAM_DQ_OUT
    }

    // UART
    io.uartTx := cluster.devicePin[Bool]("uart", "txd")
    cluster.devicePin[Bool]("uart", "rxd") := io.uartRx

    // SD Card SPI (onboard microSD slot on DB_FPGA)
    io.sdClk  := cluster.devicePin[Bool]("sdSpi", "sclk")
    io.sdCmd  := cluster.devicePin[Bool]("sdSpi", "mosi")
    cluster.devicePin[Bool]("sdSpi", "miso") := io.sdDat0
    io.sdDat3 := cluster.devicePin[Bool]("sdSpi", "cs")
    cluster.devicePin[Bool]("sdSpi", "cd")   := io.sdCd

    // AtariCtrl pins — same clock domain, no CDC needed
    val atariPins = cluster.devicePins("atariCtrl")
    def atariPin[T <: Data](name: String): T =
      atariPins.elements.find(_._1 == name).get._2.asInstanceOf[T]

    atariPin[Bool]("pllLocked") := pllLocked

    // Cartridge slot not present — tie off
    atariPin[Bits]("cartSlotData") := B(0, 8 bits)
    atariPin[Bool]("cartSlotRd4")  := False
    atariPin[Bool]("cartSlotRd5")  := False

    // Cold reset: direct pulse, same clock domain
    val coldResetPulse = atariPin[Bool]("coldReset")

    // Heartbeat LED — 1 Hz blink from 56.67 MHz
    val hb = Reg(Bool()) init False
    val hbCnt = Reg(UInt(26 bits)) init 0
    val hbHalf = (56670000 / 2 - 1)
    hbCnt := hbCnt + 1
    when(hbCnt === hbHalf) {
      hbCnt := 0
      hb := ~hb
    }
  }

  // =========================================================================
  // Atari — 56.67 MHz domain (active-LOW reset)
  // =========================================================================
  val atariArea = new ClockingArea(atariDomain) {

    // Direct access to JOP AtariCtrl pins — same clock, no CDC
    def atariPin[T <: Data](name: String): T = jopArea.atariPin[T](name)

    // Scandoubler clock enables (sys_clk/2 and sys_clk/4)
    val colourEnable  = Reg(Bool()) init False
    val doubledEnable = Reg(Bool()) init False
    colourEnable := ~colourEnable
    when(colourEnable) { doubledEnable := ~doubledEnable }

    // =====================================================================
    // Atari 800 Core — OS ROM in BRAM, all RAM via shared SDRAM
    // =====================================================================
    val atariCore = new Atari800CoreSimpleSdram(
      cycle_length   = 32,
      video_bits     = 8,
      palette        = 0,
      internal_rom   = 3,          // Atari 800 OS (atarios2 + atariosb)
      internal_ram   = 16384,      // 16K BRAM + rest via SDRAM (reduces ANTIC contention)
      low_memory     = 0,
      cartridge_rom  = "roms/Star Raiders.rom"
    )

    atariCore.io.PAL                       := atariPin[Bool]("pal")
    atariCore.io.RAM_SELECT                := atariPin[Bits]("ramSelect")
    atariCore.io.HALT                      := False
    atariCore.io.TURBO_VBLANK_ONLY         := atariPin[Bool]("turboVblankOnly")
    atariCore.io.THROTTLE_COUNT_6502       := atariPin[Bits]("throttleCount")
    atariCore.io.emulated_cartridge_select := atariPin[Bits]("cartSelect")
    atariCore.io.freezer_enable            := False
    atariCore.io.freezer_activate          := False
    atariCore.io.atari800mode              := atariPin[Bool]("atari800mode")
    atariCore.io.HIRES_ENA                 := atariPin[Bool]("hiresEna")

    // Joystick 1: debounce hardware pins, AND with JOP software override
    val joy1Debounce = new Debounce(width = 5)
    joy1Debounce.io.raw := io.joy1Fire ## io.joy1Right ## io.joy1Left ## io.joy1Down ## io.joy1Up
    atariCore.io.JOY1_n := joy1Debounce.io.debounced & atariPin[Bits]("joy1_n")

    // Joystick 2-4: software-only via JOP AtariCtrl
    atariCore.io.JOY2_n := atariPin[Bits]("joy2_n")
    atariCore.io.JOY3_n := atariPin[Bits]("joy3_n")
    atariCore.io.JOY4_n := atariPin[Bits]("joy4_n")

    // Paddles 0-3 from JOP; 4-7 not present
    atariCore.io.PADDLE0 := atariPin[SInt]("paddle0")
    atariCore.io.PADDLE1 := atariPin[SInt]("paddle1")
    atariCore.io.PADDLE2 := atariPin[SInt]("paddle2")
    atariCore.io.PADDLE3 := atariPin[SInt]("paddle3")
    atariCore.io.PADDLE4 := S(0, 8 bits)
    atariCore.io.PADDLE5 := S(0, 8 bits)
    atariCore.io.PADDLE6 := S(0, 8 bits)
    atariCore.io.PADDLE7 := S(0, 8 bits)

    // Keyboard: local scan/response (same clock domain as POKEY and AtariCtrl)
    val scan = atariCore.io.KEYBOARD_SCAN
    val keyPressed  = atariPin[Bool]("keyPressed")
    val keyScanCode = atariPin[Bits]("keyScanCode")
    val keyBreak    = atariPin[Bool]("keyBreak")
    val keyShift    = atariPin[Bool]("keyShift")
    val keyCtrl     = atariPin[Bool]("keyCtrl")

    val keyResponse = B"11"  // default: nothing pressed
    when(keyPressed && (~scan).asUInt === keyScanCode.asUInt) {
      keyResponse(0) := False  // key match
    }
    when(keyBreak && scan(5 downto 4) === B"00") {
      keyResponse(1) := False  // break
    }
    when(keyShift && scan(5 downto 4) === B"10") {
      keyResponse(1) := False  // shift
    }
    when(keyCtrl && scan(5 downto 4) === B"11") {
      keyResponse(1) := False  // control
    }
    atariCore.io.KEYBOARD_RESPONSE := keyResponse

    atariCore.io.SIO_RXD       := True

    // Console keys from JOP AtariCtrl
    atariCore.io.CONSOL_OPTION := atariPin[Bool]("consolOption")
    atariCore.io.CONSOL_SELECT := atariPin[Bool]("consolSelect")
    atariCore.io.CONSOL_START  := atariPin[Bool]("consolStart")

    // DMA not used
    atariCore.io.DMA_FETCH              := False
    atariCore.io.DMA_READ_ENABLE        := False
    atariCore.io.DMA_32BIT_WRITE_ENABLE := False
    atariCore.io.DMA_16BIT_WRITE_ENABLE := False
    atariCore.io.DMA_8BIT_WRITE_ENABLE  := False
    atariCore.io.DMA_ADDR               := B(0, 24 bits)
    atariCore.io.DMA_WRITE_DATA         := B(0, 32 bits)

    // SDRAM Arbiter Port A: Atari core (high priority)
    jopArea.arbiter.io.a.request        := atariCore.io.SDRAM_REQUEST
    jopArea.arbiter.io.a.readEnable     := atariCore.io.SDRAM_READ_ENABLE
    jopArea.arbiter.io.a.writeEnable    := atariCore.io.SDRAM_WRITE_ENABLE
    jopArea.arbiter.io.a.addr           := B"1" ## atariCore.io.SDRAM_ADDR  // 23->24, Atari at 0x800000
    jopArea.arbiter.io.a.dataIn         := atariCore.io.SDRAM_DI
    jopArea.arbiter.io.a.byteAccess     := atariCore.io.SDRAM_8BIT_WRITE_ENABLE
    jopArea.arbiter.io.a.wordAccess     := atariCore.io.SDRAM_16BIT_WRITE_ENABLE
    jopArea.arbiter.io.a.longwordAccess := atariCore.io.SDRAM_32BIT_WRITE_ENABLE
    jopArea.arbiter.io.a.refresh        := atariCore.io.SDRAM_REFRESH
    atariCore.io.SDRAM_REQUEST_COMPLETE := jopArea.arbiter.io.a.complete
    atariCore.io.SDRAM_DO               := jopArea.arbiter.io.a.dataOut

    // =====================================================================
    // Scandoubler: 15 kHz Atari -> 31 kHz VGA
    // =====================================================================
    val scandoubler = new Scandoubler(video_bits = 8)
    scandoubler.io.VGA                := True
    scandoubler.io.COMPOSITE_ON_HSYNC := False
    scandoubler.io.colour_enable      := colourEnable
    scandoubler.io.doubled_enable     := doubledEnable
    scandoubler.io.scanlines_on       := False
    scandoubler.io.pal                := atariPin[Bool]("pal")
    scandoubler.io.colour_in          := atariCore.io.VIDEO_B
    scandoubler.io.vsync_in           := atariCore.io.VIDEO_VS
    scandoubler.io.hsync_in           := atariCore.io.VIDEO_HS
    scandoubler.io.csync_in           := atariCore.io.VIDEO_CS

    // =====================================================================
    // VGA Text Overlay — per-pixel compositing (same clock domain, no CDC)
    // =====================================================================

    // Feed scandoubler timing to overlay device
    jopArea.cluster.devicePin[Bool]("vgaText", "doubledEnable") := doubledEnable
    jopArea.cluster.devicePin[Bool]("vgaText", "hsyncIn")       := atariCore.io.VIDEO_HS
    jopArea.cluster.devicePin[Bool]("vgaText", "vsyncIn")       := atariCore.io.VIDEO_VS

    // Per-pixel compositor: overlay foreground replaces Atari video
    val vgaMux = new VgaOverlayMux
    vgaMux.io.atariR     := scandoubler.io.R
    vgaMux.io.atariG     := scandoubler.io.G
    vgaMux.io.atariB     := scandoubler.io.B
    vgaMux.io.atariHsync := scandoubler.io.HSYNC
    vgaMux.io.atariVsync := scandoubler.io.VSYNC
    vgaMux.io.overlayR      := jopArea.cluster.devicePin[Bits]("vgaText", "overlayR")
    vgaMux.io.overlayG      := jopArea.cluster.devicePin[Bits]("vgaText", "overlayG")
    vgaMux.io.overlayB      := jopArea.cluster.devicePin[Bits]("vgaText", "overlayB")
    vgaMux.io.overlayActive := jopArea.cluster.devicePin[Bool]("vgaText", "overlayActive")

    // DB_FPGA VGA DAC: 5R 6G 5B (top bits of 8-bit channels)
    io.vga_r  := vgaMux.io.r(7 downto 3)
    io.vga_g  := vgaMux.io.g(7 downto 2)
    io.vga_b  := vgaMux.io.b(7 downto 3)
    io.vga_hs := vgaMux.io.hsync
    io.vga_vs := vgaMux.io.vsync
  }

  // Keyboard scan: tie off AtariCtrl's scan input (handled locally in atariArea)
  jopArea.atariPin[Bits]("keyboardScan") := B(0, 6 bits)

  // Atari reset gating — wired here after both areas are created
  atariHoldReset := jopArea.atariPin[Bool]("holdReset")
  atariColdReset := jopArea.coldResetPulse

  // =========================================================================
  // LEDs
  // =========================================================================
  io.led(0) := jopArea.cluster.io.wd(0)(0)  // JOP watchdog
  io.led(1) := jopArea.hb                    // heartbeat (~1 Hz)
}

object Atari800Ep4cgx150DualPllSv extends App {
  import spinal.lib.io.InOutWrapper
  SpinalConfig(
    mode            = SystemVerilog,
    targetDirectory = "generated"
  ).generate(InOutWrapper(new Atari800Ep4cgx150DualPllTop))
}
