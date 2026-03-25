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
// Single-PLL design: JOP and Atari both at 56.67 MHz (atari_pll).
//
// JOP: 56.67 MHz, 32 MB SDRAM (W9825G6JH6), serial boot via UART at 2M baud.
//      Devices: uart, sdSpi (CH376S), vgaText (25 MHz pixel), atariCtrl.
// Atari: 56.67 MHz, 48K internal BRAM, Star Raiders ROM.
//
// Single clock domain: no CDC needed between JOP and Atari.
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
  // PLL: AtariPll — 50 MHz -> 56.67 MHz (×17/÷15) for both JOP and Atari
  // c0=56.67 MHz, c1=56.67 MHz (-3ns for SDRAM), c3=25 MHz (VGA text)
  // =========================================================================
  val pll = new AtariPll
  pll.io.areset := False
  pll.io.inclk0 := io.clk_in

  val clkSys     = pll.io.c0      // 56.67 MHz — main clock for JOP + Atari
  val clkVgaTxt  = pll.io.c3      // 25 MHz (VGA text pixel clock)
  val pllLocked  = pll.io.locked

  io.sdram_clk := pll.io.c1       // 56.67 MHz, -3ns phase shift for SDRAM

  // CH376S reset: RSTI is active HIGH — hold high during PLL reset, release (low) when locked
  io.ch376Rst := !pllLocked

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
  val atariDomain = ClockDomain(
    clock     = clkSys,
    reset     = ~sysReset,
    frequency = FixedFrequency(56670000 Hz),
    config    = ClockDomainConfig(resetKind = SYNC, resetActiveLevel = LOW)
  )

  // VGA text pixel — 25 MHz
  val vgaTextDomain = ClockDomain(
    clock     = clkVgaTxt,
    reset     = sysReset,
    config    = ClockDomainConfig(resetKind = SYNC, resetActiveLevel = HIGH)
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
      vgaCd      = Some(vgaTextDomain)
    )

    // JOP SDRAM — W9825G6JH6 32 MB via Altera SDRAM controller
    val sdramCtrl = BmbSdramCtrl32(
      bmbParameter = cluster.bmbParameter,
      layout       = SdramDeviceInfo.layoutFor(memDevice),
      timing       = SdramDeviceInfo.timingFor(memDevice),
      CAS          = memDevice.casLatency,
      useAlteraCtrl = true,
      clockFreqHz  = JopCoreForAtariDualPll.clkFreqHz
    )
    sdramCtrl.io.bmb <> cluster.io.bmb
    io.sdram <> sdramCtrl.io.sdram

    // UART
    io.uartTx := cluster.devicePin[Bool]("uart", "txd")
    cluster.devicePin[Bool]("uart", "rxd") := io.uartRx

    // CH376T SPI
    io.ch376Sck  := cluster.devicePin[Bool]("sdSpi", "sclk")
    io.ch376Mosi := cluster.devicePin[Bool]("sdSpi", "mosi")
    cluster.devicePin[Bool]("sdSpi", "miso") := io.ch376Miso
    io.ch376Cs   := cluster.devicePin[Bool]("sdSpi", "cs")
    cluster.devicePin[Bool]("sdSpi", "cd")   := io.ch376Int

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
    scandoubler.io.pal                := atariPin[Bool]("pal")
    scandoubler.io.colour_in          := atariCore.io.VIDEO_B
    scandoubler.io.vsync_in           := atariCore.io.VIDEO_VS
    scandoubler.io.hsync_in           := atariCore.io.VIDEO_HS
    scandoubler.io.csync_in           := atariCore.io.VIDEO_CS

    // =====================================================================
    // VGA Overlay Mux — Atari video / JOP OSD text
    // VgaText runs at 25 MHz — CDC needed to sys domain (56.67 MHz)
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

  // Keyboard scan: tie off AtariCtrl's scan input (handled locally in atariArea)
  jopArea.atariPin[Bits]("keyboardScan") := B(0, 6 bits)

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
