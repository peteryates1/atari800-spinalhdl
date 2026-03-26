package atari800

import spinal.core._
import spinal.lib._
import spinal.lib.bus.bmb._
import jop.system._
import jop.system.memory.MemoryControllerFactory
import jop.io._

// Atari 800 + JOP top for QMTECH EP4CGX150 + DB_FPGA daughter board.
//
// Both Atari and JOP are BRAM-only (no SDRAM).
// Atari: 48K internal RAM + Star Raiders ROM in BRAM.
// JOP:   256K BRAM for Java heap/stack (serial boot via UART).
//
// JOP manages: CH376T (SD card + USB keyboard via SPI), UART (serial boot/debug),
//              Atari reset/config/keyboard via AtariCtrl I/O device.
//
// Peripherals on DB_FPGA used:
//   UART: CP2102N at PIN_AD20 (TX) / PIN_AE21 (RX) — JOP serial boot
//   VGA:  5R-6G-5B resistor DAC                    — scandoubled output
//
// PMOD J10 — Joystick 1 (active-low, DB-9, debounced)
//   J10: pin1=AF20 (up), pin2=AE19 (down), pin3=AC19 (left),
//        pin4=AE18 (right), pin7=AF21 (fire)
//
// PMOD J11 — CH376T SPI module (USB keyboard + SD card host)
//   J11: pin2=AC21 (CS), pin3=AE23 (MOSI), pin4=AE22 (RSTI),
//        pin8=AD21 (MISO), pin9=AF23 (INT#), pin10=AF22 (SCK)
//
// Clock: 56.67 MHz (50 MHz × 17/15) → cycle_length=32 → 1.771 MHz 6502
class Atari800Ep4cgx150JopTop extends Component {
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
    val ch376Sck  = out Bool()   // J11 pin 10 (AF22)
    val ch376Mosi = out Bool()   // J11 pin 3  (AE23)
    val ch376Miso = in  Bool()   // J11 pin 8  (AD21)
    val ch376Cs   = out Bool()   // J11 pin 2  (AC21)
    val ch376Int  = in  Bool()   // J11 pin 9  (AF23) — CH376T interrupt
    val ch376Rst  = out Bool()   // J11 pin 4  (AE22) — CH376T reset (active low)

    // Joystick 1 — PMOD J10 pins 1-4,7 (active low, DB-9)
    val joy1Up    = in Bool()    // J10 pin 1 (AF20)
    val joy1Down  = in Bool()    // J10 pin 2 (AE19)
    val joy1Left  = in Bool()    // J10 pin 3 (AC19)
    val joy1Right = in Bool()    // J10 pin 4 (AE18)
    val joy1Fire  = in Bool()    // J10 pin 7 (AF21)

    // LEDs on core board (active high)
    val led     = out Bits(2 bits)
  }

  // =========================================================================
  // PLL: 50 MHz -> 56.67 MHz system (reuse proven atari_pll.sv)
  // VGA text clock: derived from 50 MHz input via toggle (25 MHz)
  // =========================================================================
  val pll = new AtariPll
  pll.io.areset := False
  pll.io.inclk0 := io.clk_in

  val clkSys     = pll.io.c0   // 56.67 MHz
  val pllLocked  = pll.io.locked

  // Derive 25 MHz VGA text clock from 50 MHz input via toggle FF
  val vgaTextToggle = Bool()
  val vgaTextClkArea = new ClockingArea(ClockDomain(
    clock  = io.clk_in,
    config = ClockDomainConfig(resetKind = BOOT)
  )) {
    val toggle = RegInit(False)
    toggle := ~toggle
    vgaTextToggle := toggle
  }
  val clkVgaText = vgaTextToggle

  // CH376T reset: hold low during PLL reset, release when locked
  io.ch376Rst := pllLocked

  val sysDomain = ClockDomain(
    clock  = clkSys,
    reset  = pllLocked,
    config = ClockDomainConfig(
      clockEdge        = RISING,
      resetKind        = ASYNC,
      resetActiveLevel = LOW
    )
  )

  val vgaTextDomain = ClockDomain(
    clock  = clkVgaText,
    reset  = pllLocked,
    config = ClockDomainConfig(
      clockEdge        = RISING,
      resetKind        = ASYNC,
      resetActiveLevel = LOW
    )
  )

  val sysArea = new ClockingArea(sysDomain) {

    val jopConfig = JopCoreForAtari.config

    // =====================================================================
    // JOP Soft-Core
    // =====================================================================
    val jopCore   = JopCore(
      config  = jopConfig,
      romInit = Some(JopCoreForAtari.hwRomInit),
      ramInit = Some(JopCoreForAtari.simRamInit),
      jbcInit = Some(Seq.fill(2048)(BigInt(0))),
      vgaCd   = Some(vgaTextDomain)
    )

    jopCore.io.syncIn.halted := False
    jopCore.io.syncIn.s_out  := False
    jopCore.io.syncIn.status := False
    jopCore.io.snoopIn.foreach { si =>
      si.valid := False; si.isArray := False; si.handle := 0; si.index := 0
    }
    jopCore.io.debugHalt    := False
    jopCore.io.debugRamAddr := 0

    val atariPins = jopCore.devicePins("atariCtrl")
    def atariPin[T <: Data](name: String): T =
      atariPins.elements.find(_._1 == name).get._2.asInstanceOf[T]

    atariPin[Bool]("pllLocked") := pllLocked

    // Atari cold reset from JOP AtariCtrl (active high pulse)
    val atariColdReset = atariPin[Bool]("coldReset")

    // Scandoubler clock enables (sys_clk/2 and sys_clk/4)
    val colourEnable  = Reg(Bool()) init False
    val doubledEnable = Reg(Bool()) init False
    colourEnable := ~colourEnable
    when(colourEnable) { doubledEnable := ~doubledEnable }

    // =====================================================================
    // Atari 800 Core — BRAM-only
    // 48K internal RAM, Atari 800 OS, Star Raiders from BRAM.
    // =====================================================================
    val atariCore = new Atari800CoreSimpleSdram(
      cycle_length   = 32,
      video_bits     = 8,
      palette        = 0,
      internal_rom   = 3,        // Atari 800 OS (Os8 + Os2)
      internal_ram   = 49152,    // 48K in BRAM
      basic_in_sdram = false,    // Cartridge ROM in internal BRAM
      cartridge_rom  = "roms/Star Raiders.rom"
    )

    // Config from JOP AtariCtrl
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

    // =====================================================================
    // Joystick 1: debounce hardware pins, AND with JOP software override
    // =====================================================================
    val joy1Debounce = new Debounce(width = 5)
    joy1Debounce.io.raw := io.joy1Fire ## io.joy1Right ## io.joy1Left ## io.joy1Down ## io.joy1Up
    atariCore.io.JOY1_n := joy1Debounce.io.debounced & atariPin[Bits]("joy1_n")

    // Joystick 2-4: no hardware pins — software-only via JOP AtariCtrl
    atariCore.io.JOY2_n := atariPin[Bits]("joy2_n")
    atariCore.io.JOY3_n := atariPin[Bits]("joy3_n")
    atariCore.io.JOY4_n := atariPin[Bits]("joy4_n")

    // Paddles 0-3 from JOP; paddles 4-7 not present on this board
    atariCore.io.PADDLE0 := atariPin[SInt]("paddle0")
    atariCore.io.PADDLE1 := atariPin[SInt]("paddle1")
    atariCore.io.PADDLE2 := atariPin[SInt]("paddle2")
    atariCore.io.PADDLE3 := atariPin[SInt]("paddle3")
    atariCore.io.PADDLE4 := S(0, 8 bits)
    atariCore.io.PADDLE5 := S(0, 8 bits)
    atariCore.io.PADDLE6 := S(0, 8 bits)
    atariCore.io.PADDLE7 := S(0, 8 bits)

    // Keyboard: POKEY scan → AtariCtrl → hardware response
    atariPin[Bits]("keyboardScan") := atariCore.io.KEYBOARD_SCAN
    atariCore.io.KEYBOARD_RESPONSE := atariPin[Bits]("keyboardResponse")

    atariCore.io.SIO_RXD       := True

    // Console keys from JOP AtariCtrl (active high: 1 = pressed)
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

    // SDRAM not used by Atari — 48K BRAM covers all Atari memory
    atariCore.io.SDRAM_REQUEST_COMPLETE := False
    atariCore.io.SDRAM_DO               := B(0, 32 bits)

    // Physical cartridge slot not present — tie off AtariCtrl inputs
    atariPin[Bits]("cartSlotData") := B(0, 8 bits)
    atariPin[Bool]("cartSlotRd4")  := False
    atariPin[Bool]("cartSlotRd5")  := False

    // =====================================================================
    // CH376T SPI — PMOD J11 (USB keyboard + SD card host)
    // INT is wired as card-detect so JOP polls it via the cd signal.
    // =====================================================================
    io.ch376Sck  := jopCore.devicePin[Bool]("sdSpi", "sclk")
    io.ch376Mosi := jopCore.devicePin[Bool]("sdSpi", "mosi")
    jopCore.devicePin[Bool]("sdSpi", "miso") := io.ch376Miso
    io.ch376Cs   := jopCore.devicePin[Bool]("sdSpi", "cs")
    jopCore.devicePin[Bool]("sdSpi", "cd")   := io.ch376Int

    // =====================================================================
    // UART — DB_FPGA CP2102N
    // =====================================================================
    io.uartTx := jopCore.devicePin[Bool]("uart", "txd")
    jopCore.devicePin[Bool]("uart", "rxd") := io.uartRx

    // =====================================================================
    // JOP Memory — 256 KB on-chip BRAM (serial boot, filled via UART)
    // =====================================================================
    val bramCtrl = MemoryControllerFactory.createBram(
      bmbParameter = jopConfig.memConfig.bmbParameter,
      memSize      = jopConfig.memConfig.mainMemSize.toInt
    )
    bramCtrl.ram.io.bus <> jopCore.io.bmb

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
    // =====================================================================
    val vgaMux = new VgaFullScreenMux
    vgaMux.io.osdEnable  := atariPin[Bool]("osdEnable")
    vgaMux.io.atariR     := scandoubler.io.R
    vgaMux.io.atariG     := scandoubler.io.G
    vgaMux.io.atariB     := scandoubler.io.B
    vgaMux.io.atariHsync := scandoubler.io.HSYNC
    vgaMux.io.atariVsync := scandoubler.io.VSYNC
    vgaMux.io.jopR       := jopCore.devicePin[Bits]("vgaText", "vgaR")
    vgaMux.io.jopG       := jopCore.devicePin[Bits]("vgaText", "vgaG")
    vgaMux.io.jopB       := jopCore.devicePin[Bits]("vgaText", "vgaB")
    vgaMux.io.jopHsync   := jopCore.devicePin[Bool]("vgaText", "vgaHsync")
    vgaMux.io.jopVsync   := jopCore.devicePin[Bool]("vgaText", "vgaVsync")

    // DB_FPGA VGA DAC: 5R 6G 5B (top bits of 8-bit channels)
    io.vga_r  := vgaMux.io.r(7 downto 3)
    io.vga_g  := vgaMux.io.g(7 downto 2)
    io.vga_b  := vgaMux.io.b(7 downto 3)
    io.vga_hs := vgaMux.io.hsync
    io.vga_vs := vgaMux.io.vsync

    // =====================================================================
    // LEDs — JOP diagnostic
    // =====================================================================
    // LED0: JOP watchdog bit 0 (toggles if JOP microcode runs and reaches Java)
    // LED1: JOP memBusy inverted (ON = pipeline running freely)
    io.led(0) := jopCore.io.wd(0)
    io.led(1) := ~jopCore.io.memBusy
  }
}

object Atari800Ep4cgx150JopSv extends App {
  SpinalConfig(
    mode            = SystemVerilog,
    targetDirectory = "generated"
  ).generate(new Atari800Ep4cgx150JopTop)
}
