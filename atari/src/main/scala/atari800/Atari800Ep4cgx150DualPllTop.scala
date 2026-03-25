package atari800

import spinal.core._
import spinal.lib._
import spinal.lib.bus.bmb._
import spinal.lib.memory.sdram.sdr.SdramInterface
import jop.system._
import jop.system.memory.MemoryControllerFactory
import jop.config.MemoryDevice
import jop.memory.{BmbSdramCtrl32, SdramDeviceInfo}
import jop.io._

// Atari 800 + JOP top for QMTECH EP4CGX150 + DB_FPGA daughter board.
// Dual-PLL design: JOP at 80 MHz (dram_pll), Atari at 56.67 MHz (atari_pll).
//
// JOP: 80 MHz, 32 MB SDRAM (W9825G6JH6), serial boot via UART at 2M baud.
//      Devices: uart, sdSpi (CH376S), vgaText (25 MHz pixel), atariCtrl.
// Atari: 56.67 MHz, 48K internal BRAM, Star Raiders ROM.
//
// CDC: AtariCtrl signals cross between JOP (80 MHz) and Atari (56.67 MHz)
//      via BufferCC synchronizers. All crossing signals are slow-changing
//      register outputs (config, paddles, joystick, keyboard).
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

    // CH376T SPI module — PMOD J11 (USB keyboard + SD card host)
    val ch376Sck  = out Bool()
    val ch376Mosi = out Bool()
    val ch376Miso = in  Bool()
    val ch376Cs   = out Bool()
    val ch376Int  = in  Bool()
    val ch376Rst  = out Bool()

    // SDRAM — W9825G6JH6 (32 MB, 16-bit)
    val sdram = master(SdramInterface(SdramDeviceInfo.layoutFor(MemoryDevice.W9825G6JH6)))
    val sdram_clk = out Bool()

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
  // PLL 1: DramPll — 50 MHz -> 80 MHz for JOP, 25 MHz for VGA text
  // c0=50 MHz, c1=80 MHz, c2=80 MHz (-3ns), c3=25 MHz
  // =========================================================================
  val jopPll = DramPll()
  jopPll.io.areset := False
  jopPll.io.inclk0 := io.clk_in

  val clkJop    = jopPll.io.c1      // 80 MHz
  val clkVgaTxt = jopPll.io.c3      // 25 MHz (VGA text pixel clock)
  val jopLocked = jopPll.io.locked

  // =========================================================================
  // PLL 2: AtariPll — 50 MHz -> 56.67 MHz for Atari (×17/÷15)
  // =========================================================================
  val atariPll = new AtariPll
  atariPll.io.areset := False
  atariPll.io.inclk0 := io.clk_in

  val clkAtari    = atariPll.io.c0    // 56.67 MHz
  val atariLocked = atariPll.io.locked

  // CH376S reset: RSTI is active HIGH — hold high during PLL reset, release (low) when locked
  io.ch376Rst := !jopLocked

  // =========================================================================
  // Clock Domains
  // =========================================================================

  // JOP — 80 MHz, SYNC reset via ResetGenerator (proven standalone config)
  val jopReset = ResetGenerator(jopLocked, clkJop)
  val jopDomain = ClockDomain(
    clock     = clkJop,
    reset     = jopReset,
    frequency = FixedFrequency(80 MHz),
    config    = ClockDomainConfig(resetKind = SYNC, resetActiveLevel = HIGH)
  )

  // Atari — 56.67 MHz, active-LOW reset (matches all Atari core conventions).
  // ResetGenerator returns active-HIGH; invert for Atari's RESET_N / resetN ports
  // which use ClockDomain.current.readResetWire expecting active-LOW.
  val atariResetHigh = ResetGenerator(atariLocked, clkAtari)
  val atariDomain = ClockDomain(
    clock     = clkAtari,
    reset     = ~atariResetHigh,
    frequency = FixedFrequency(56670000 Hz),
    config    = ClockDomainConfig(resetKind = SYNC, resetActiveLevel = LOW)
  )

  // VGA text pixel — 25 MHz, uses JOP PLL locked/reset
  val vgaTextDomain = ClockDomain(
    clock     = clkVgaTxt,
    reset     = jopReset,
    config    = ClockDomainConfig(resetKind = SYNC, resetActiveLevel = HIGH)
  )

  // =========================================================================
  // JOP Area — 80 MHz domain
  // =========================================================================
  val jopArea = new ClockingArea(jopDomain) {

    val jopConfig = JopCoreForAtariDualPll.config
    val memDevice = JopCoreForAtariDualPll.memDevice

    val cluster = JopCluster(
      cpuCnt     = 1,
      baseConfig = jopConfig,
      romInit    = Some(JopCoreForAtariDualPll.hwRomInit),
      ramInit    = Some(JopCoreForAtariDualPll.simRamInit),
      jbcInit    = Some(Seq.fill(2048)(BigInt(0))),
      vgaCd      = Some(vgaTextDomain)
    )

    // JOP SDRAM — W9825G6JH6 32 MB via Altera SDRAM controller
    val sdramCtrl = BmbSdramCtrl32(
      bmbParameter = cluster.bmbParameter,
      layout       = SdramDeviceInfo.layoutFor(memDevice),
      timing       = SdramDeviceInfo.timingFor(memDevice),
      CAS          = memDevice.casLatency,
      useAlteraCtrl = true,
      clockFreqHz  = 80000000L
    )
    sdramCtrl.io.bmb <> cluster.io.bmb
    io.sdram <> sdramCtrl.io.sdram
    io.sdram_clk := jopPll.io.c2  // 80 MHz, -3ns phase shift for SDRAM

    // UART
    io.uartTx := cluster.devicePin[Bool]("uart", "txd")
    cluster.devicePin[Bool]("uart", "rxd") := io.uartRx

    // CH376T SPI
    io.ch376Sck  := cluster.devicePin[Bool]("sdSpi", "sclk")
    io.ch376Mosi := cluster.devicePin[Bool]("sdSpi", "mosi")
    cluster.devicePin[Bool]("sdSpi", "miso") := io.ch376Miso
    io.ch376Cs   := cluster.devicePin[Bool]("sdSpi", "cs")
    cluster.devicePin[Bool]("sdSpi", "cd")   := io.ch376Int

    // AtariCtrl pins — accessed from jopArea, CDC'd to atariArea
    val atariPins = cluster.devicePins("atariCtrl")
    def atariPin[T <: Data](name: String): T =
      atariPins.elements.find(_._1 == name).get._2.asInstanceOf[T]

    atariPin[Bool]("pllLocked") := atariLocked

    // Cartridge slot not present — tie off
    atariPin[Bits]("cartSlotData") := B(0, 8 bits)
    atariPin[Bool]("cartSlotRd4")  := False
    atariPin[Bool]("cartSlotRd5")  := False

    // Cold reset: toggle in JOP domain on pulse, detect edge in Atari domain
    val coldResetToggle = Reg(Bool()) init False
    when(atariPin[Bool]("coldReset")) { coldResetToggle := ~coldResetToggle }

    // Heartbeat LED — 1 Hz blink from 80 MHz
    val hb = Reg(Bool()) init False
    val hbCnt = Reg(UInt(26 bits)) init 0
    val hbHalf = (80000000 / 2 - 1)
    hbCnt := hbCnt + 1
    when(hbCnt === hbHalf) {
      hbCnt := 0
      hb := ~hb
    }
  }

  // =========================================================================
  // Atari Area — 56.67 MHz domain
  // =========================================================================
  val atariArea = new ClockingArea(atariDomain) {

    // --- CDC: JOP (80 MHz) -> Atari (56.67 MHz) via BufferCC ---
    // All these are slow-changing register outputs, safe for double-FF sync.
    val osdEnable       = BufferCC(jopArea.atariPin[Bool]("osdEnable"), init = True)
    val pal             = BufferCC(jopArea.atariPin[Bool]("pal"))
    val ramSelect       = BufferCC(jopArea.atariPin[Bits]("ramSelect"))
    val turboVblankOnly = BufferCC(jopArea.atariPin[Bool]("turboVblankOnly"))
    val atari800mode    = BufferCC(jopArea.atariPin[Bool]("atari800mode"))
    val hiresEna        = BufferCC(jopArea.atariPin[Bool]("hiresEna"))
    val throttleCount   = BufferCC(jopArea.atariPin[Bits]("throttleCount"))
    val cartSelect      = BufferCC(jopArea.atariPin[Bits]("cartSelect"))
    val joy1_n          = BufferCC(jopArea.atariPin[Bits]("joy1_n"))
    val joy2_n          = BufferCC(jopArea.atariPin[Bits]("joy2_n"))
    val joy3_n          = BufferCC(jopArea.atariPin[Bits]("joy3_n"))
    val joy4_n          = BufferCC(jopArea.atariPin[Bits]("joy4_n"))
    val paddle0         = BufferCC(jopArea.atariPin[SInt]("paddle0"))
    val paddle1         = BufferCC(jopArea.atariPin[SInt]("paddle1"))
    val paddle2         = BufferCC(jopArea.atariPin[SInt]("paddle2"))
    val paddle3         = BufferCC(jopArea.atariPin[SInt]("paddle3"))
    // Key state CDC'd from JOP (slow-changing registers, safe for BufferCC)
    val keyScanCode     = BufferCC(jopArea.atariPin[Bits]("keyScanCode"))
    val keyPressed      = BufferCC(jopArea.atariPin[Bool]("keyPressed"))
    val keyShift        = BufferCC(jopArea.atariPin[Bool]("keyShift"))
    val keyCtrl         = BufferCC(jopArea.atariPin[Bool]("keyCtrl"))
    val keyBreak        = BufferCC(jopArea.atariPin[Bool]("keyBreak"))
    val consolOption    = BufferCC(jopArea.atariPin[Bool]("consolOption"))
    val consolSelect    = BufferCC(jopArea.atariPin[Bool]("consolSelect"))
    val consolStart     = BufferCC(jopArea.atariPin[Bool]("consolStart"))

    // Cold reset: edge-detect the toggle from JOP domain
    val coldResetSync  = BufferCC(jopArea.coldResetToggle, init = False)
    val coldResetPrev  = RegNext(coldResetSync) init False
    val coldResetPulse = coldResetSync =/= coldResetPrev

    // --- CDC: Atari (56.67 MHz) -> JOP (80 MHz) for keyboard scan ---
    // keyboardScan is written in Atari domain, read by AtariCtrl in JOP domain.
    // We'll use a register here and BufferCC it into jopArea below.

    // Scandoubler clock enables (sys_clk/2 and sys_clk/4)
    val colourEnable  = Reg(Bool()) init False
    val doubledEnable = Reg(Bool()) init False
    colourEnable := ~colourEnable
    when(colourEnable) { doubledEnable := ~doubledEnable }

    // =====================================================================
    // Atari 800 Core — BRAM-only
    // =====================================================================
    val atariCore = new Atari800CoreSimpleSdram(
      cycle_length   = 32,
      video_bits     = 8,
      palette        = 0,
      internal_rom   = 3,
      internal_ram   = 49152,
      basic_in_sdram = false,
      cartridge_rom  = "roms/Star Raiders.rom"
    )

    atariCore.io.PAL                       := pal
    atariCore.io.RAM_SELECT                := ramSelect
    atariCore.io.HALT                      := False
    atariCore.io.TURBO_VBLANK_ONLY         := turboVblankOnly
    atariCore.io.THROTTLE_COUNT_6502       := throttleCount
    atariCore.io.emulated_cartridge_select := cartSelect
    atariCore.io.freezer_enable            := False
    atariCore.io.freezer_activate          := False
    atariCore.io.atari800mode              := atari800mode
    atariCore.io.HIRES_ENA                 := hiresEna

    // Joystick 1: debounce hardware pins, AND with CDC'd JOP software override
    val joy1Debounce = new Debounce(width = 5)
    joy1Debounce.io.raw := io.joy1Fire ## io.joy1Right ## io.joy1Left ## io.joy1Down ## io.joy1Up
    atariCore.io.JOY1_n := joy1Debounce.io.debounced & joy1_n

    // Joystick 2-4: software-only via JOP AtariCtrl
    atariCore.io.JOY2_n := joy2_n
    atariCore.io.JOY3_n := joy3_n
    atariCore.io.JOY4_n := joy4_n

    // Paddles 0-3 from JOP; 4-7 not present
    atariCore.io.PADDLE0 := paddle0
    atariCore.io.PADDLE1 := paddle1
    atariCore.io.PADDLE2 := paddle2
    atariCore.io.PADDLE3 := paddle3
    atariCore.io.PADDLE4 := S(0, 8 bits)
    atariCore.io.PADDLE5 := S(0, 8 bits)
    atariCore.io.PADDLE6 := S(0, 8 bits)
    atariCore.io.PADDLE7 := S(0, 8 bits)

    // Keyboard: local scan/response in Atari domain (no CDC round-trip).
    // Key state was CDC'd from JOP above (slow-changing, safe).
    // Scan comparison happens here in same clock domain as POKEY.
    val scan = atariCore.io.KEYBOARD_SCAN
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
    atariCore.io.CONSOL_OPTION := consolOption
    atariCore.io.CONSOL_SELECT := consolSelect
    atariCore.io.CONSOL_START  := consolStart

    // DMA not used
    atariCore.io.DMA_FETCH              := False
    atariCore.io.DMA_READ_ENABLE        := False
    atariCore.io.DMA_32BIT_WRITE_ENABLE := False
    atariCore.io.DMA_16BIT_WRITE_ENABLE := False
    atariCore.io.DMA_8BIT_WRITE_ENABLE  := False
    atariCore.io.DMA_ADDR               := B(0, 24 bits)
    atariCore.io.DMA_WRITE_DATA         := B(0, 32 bits)

    // SDRAM not used — BRAM covers all Atari memory
    atariCore.io.SDRAM_REQUEST_COMPLETE := False
    atariCore.io.SDRAM_DO               := B(0, 32 bits)

    // =====================================================================
    // Scandoubler: 15 kHz Atari -> 31 kHz VGA
    // =====================================================================
    val scandoubler = new Scandoubler(video_bits = 8)
    scandoubler.io.VGA                := True
    scandoubler.io.COMPOSITE_ON_HSYNC := False
    scandoubler.io.colour_enable      := colourEnable
    scandoubler.io.doubled_enable     := doubledEnable
    scandoubler.io.scanlines_on       := False
    scandoubler.io.pal                := pal
    scandoubler.io.colour_in          := atariCore.io.VIDEO_B
    scandoubler.io.vsync_in           := atariCore.io.VIDEO_VS
    scandoubler.io.hsync_in           := atariCore.io.VIDEO_HS
    scandoubler.io.csync_in           := atariCore.io.VIDEO_CS

    // =====================================================================
    // VGA Overlay Mux — Atari video / JOP OSD text
    // CDC: VgaText outputs (25 MHz) -> Atari domain (56.67 MHz)
    // =====================================================================
    val jopVgaR     = BufferCC(jopArea.cluster.devicePin[Bits]("vgaText", "vgaR"))
    val jopVgaG     = BufferCC(jopArea.cluster.devicePin[Bits]("vgaText", "vgaG"))
    val jopVgaB     = BufferCC(jopArea.cluster.devicePin[Bits]("vgaText", "vgaB"))
    val jopVgaHsync = BufferCC(jopArea.cluster.devicePin[Bool]("vgaText", "vgaHsync"), init = True)
    val jopVgaVsync = BufferCC(jopArea.cluster.devicePin[Bool]("vgaText", "vgaVsync"), init = True)

    val vgaMux = new VgaOverlayMux
    vgaMux.io.osdEnable  := False  // TODO: restore osdEnable once Atari video confirmed
    vgaMux.io.atariR     := scandoubler.io.R
    vgaMux.io.atariG     := scandoubler.io.G
    vgaMux.io.atariB     := scandoubler.io.B
    vgaMux.io.atariHsync := scandoubler.io.HSYNC
    vgaMux.io.atariVsync := scandoubler.io.VSYNC
    vgaMux.io.jopR       := jopVgaR
    vgaMux.io.jopG       := jopVgaG
    vgaMux.io.jopB       := jopVgaB
    vgaMux.io.jopHsync   := jopVgaHsync
    vgaMux.io.jopVsync   := jopVgaVsync

    // DB_FPGA VGA DAC: 5R 6G 5B (top bits of 8-bit channels)
    io.vga_r  := vgaMux.io.r(7 downto 3)
    io.vga_g  := vgaMux.io.g(7 downto 2)
    io.vga_b  := vgaMux.io.b(7 downto 3)
    io.vga_hs := vgaMux.io.hsync
    io.vga_vs := vgaMux.io.vsync
  }

  // Keyboard scan/response now handled locally in Atari domain (no CDC round-trip).
  // Tie off AtariCtrl's scan input — its internal response logic is unused.
  jopArea.atariPin[Bits]("keyboardScan") := B(0, 6 bits)

  // =========================================================================
  // LEDs
  // =========================================================================
  io.led(0) := jopArea.cluster.io.wd(0)(0)  // JOP watchdog
  io.led(1) := jopArea.hb                    // 80 MHz heartbeat (~1 Hz)
}

object Atari800Ep4cgx150DualPllSv extends App {
  import spinal.lib.io.InOutWrapper
  SpinalConfig(
    mode            = SystemVerilog,
    targetDirectory = "generated"
  ).generate(InOutWrapper(new Atari800Ep4cgx150DualPllTop))
}
