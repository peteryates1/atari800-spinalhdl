package atari800

import spinal.core._
import spinal.lib._
import spinal.lib.bus.bmb._
import jop.system._
import jop.io._

// ALTPLL for QMTECH EP4CGX150 JOP build — 3 outputs
// c0: 56.67 MHz system clock  (50 MHz × 17 / 15)
// c1: 56.67 MHz SDRAM clock   (-3 ns phase shift)
// c2: 25 MHz VGA text clock   (50 MHz ÷ 2)
class AtariPllJop extends BlackBox {
  setDefinitionName("atari_pll_jop")

  val io = new Bundle {
    val areset = in  Bool()
    val inclk0 = in  Bool()
    val c0     = out Bool()   // 56.67 MHz system clock
    val c1     = out Bool()   // 56.67 MHz SDRAM clock (-3 ns)
    val c2     = out Bool()   // 25 MHz VGA text pixel clock
    val locked = out Bool()
  }

  noIoPrefix()
}

object Atari800Ep4cgx150JopTop {
  // Board-specific config for the QMTECH EP4CGX150 (Cyclone IV GX, ~150K LEs).
  // SDRAM: W9825G6JH6 32 MB (4M × 16-bit × 4 banks).
  // JOP:   physical bytes 0x000000 .. 0x7FFFFF  (lower 8 MB)
  // Atari: physical bytes 0x800000 .. 0xFFFFFF  (upper 8 MB)
  // JOP cache sizing: configSmall — fits comfortably in EP4CGX150 BRAM.
  // Upgrade to configLarge if timing + resources allow.
  val boardConfig = AtariBoardConfig(
    sdramBytes     = 32L * 1024 * 1024,
    atariSdramBase = 0x800000L,
    jopConfig      = JopCoreForAtari.config   // configSmall for ep4cgx150
  )
}

// Atari 800 + JOP top for QMTECH EP4CGX150 + DB_FPGA daughter board.
//
// JOP manages: CH376T (SD card + USB keyboard via SPI), UART (serial boot/debug),
//              Atari reset/config via AtariCtrl I/O device.
//
// Peripherals on DB_FPGA used:
//   UART: CP2102N at PIN_AD20 (TX) / PIN_AE21 (RX) — JOP serial boot
//   VGA:  5R-6G-5B resistor DAC                    — scandoubled output
//
// PMOD J10 — Joystick 1 (DB-9 active-low wiring, future physical connector)
// PMOD J11 — CH376T SPI module (USB keyboard + SD card host)
//   J11 pin 1 (AF25): SCLK
//   J11 pin 2 (AD21): MOSI
//   J11 pin 3 (AF23): MISO
//   J11 pin 4 (AF22): CS#
//   J11 pin 7 (AF24): INT (CH376T interrupt, wired as card-detect)
//
// Joystick 2 software-only (no hardware pins on this board).
// Joysticks 3+4 and paddles 4-7 dropped for this board.
//
// Clock: 56.67 MHz (50 MHz × 17/15) → cycle_length=32 → 1.771 MHz 6502
// SDRAM: W9825G6JH6 32MB 16-bit — memory map in Atari800Ep4cgx150JopTop.boardConfig
class Atari800Ep4cgx150JopTop(boardConfig: AtariBoardConfig = Atari800Ep4cgx150JopTop.boardConfig) extends Component {
  val io = new Bundle {
    // 50 MHz oscillator (PIN_B14)
    val clk_in = in Bool()

    // SDRAM (W9825G6JH6, 32 MB, 16-bit)
    val sdram   = master(SdramCtrlPins())
    val sdramDq = inout(Analog(Bits(16 bits)))

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
    val ch376Sclk = out Bool()   // J11 pin 1 (AF25)
    val ch376Mosi = out Bool()   // J11 pin 2 (AD21)
    val ch376Miso = in  Bool()   // J11 pin 3 (AF23)
    val ch376Cs   = out Bool()   // J11 pin 4 (AF22)
    val ch376Int  = in  Bool()   // J11 pin 7 (AF24) — CH376T interrupt

    // Joystick 1 — PMOD J10 pins 1-4,7 (active low, DB-9, future connector)
    val joy1Up    = in Bool()
    val joy1Down  = in Bool()
    val joy1Left  = in Bool()
    val joy1Right = in Bool()
    val joy1Fire  = in Bool()

    // LEDs on core board (active high)
    val led     = out Bits(2 bits)
  }

  // =========================================================================
  // PLL: 50 MHz -> 56.67 MHz system + SDRAM + 25 MHz VGA text
  // =========================================================================
  val pll = new AtariPllJop
  pll.io.areset := False
  pll.io.inclk0 := io.clk_in

  val clkSys     = pll.io.c0   // 56.67 MHz
  val clkSdram   = pll.io.c1   // 56.67 MHz (-3 ns)
  val clkVgaText = pll.io.c2   // 25 MHz
  val pllLocked  = pll.io.locked

  io.sdram.clk := clkSdram

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

    // =====================================================================
    // JOP Soft-Core
    // =====================================================================
    val jopCore   = JopCore(config = boardConfig.jopConfig, romInit = Some(JopCoreForAtari.hwRomInit), vgaCd = Some(vgaTextDomain))

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

    val resetN = pllLocked & ~atariPin[Bool]("coldReset")

    // Scandoubler clock enables (sys_clk/2 and sys_clk/4)
    val colourEnable  = Reg(Bool()) init False
    val doubledEnable = Reg(Bool()) init False
    colourEnable := ~colourEnable
    when(colourEnable) { doubledEnable := ~doubledEnable }

    // =====================================================================
    // Atari 800 Core
    // OS ROM in writable BRAM (JOP loads via DMA at boot)
    // All RAM from SDRAM (JOP + Atari share via arbiter)
    // =====================================================================
    val atariCore = new Atari800CoreSimpleSdram(
      cycle_length   = 32,
      video_bits     = 8,
      palette        = 0,
      internal_rom   = 1,
      internal_ram   = 0,
      low_memory     = 0,
      stereo         = 1,
      covox          = 1,
      basic_in_sdram = true
    )

    // Note: Atari800CoreSimpleSdram uses ClockDomain.current.readResetWire internally;
    // there is no external RESET_N port.

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

    // Joystick 1: AND hardware PMOD J10 pins with JOP software override
    val joy1Packed = io.joy1Up ## io.joy1Down ## io.joy1Left ## io.joy1Right ## io.joy1Fire
    atariCore.io.JOY1_n := joy1Packed & atariPin[Bits]("joy1_n")

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

    // Keyboard from JOP AtariCtrl
    atariCore.io.KEYBOARD_RESPONSE := atariPin[Bits]("keyboardResponse")

    atariCore.io.SIO_RXD       := True
    atariCore.io.CONSOL_OPTION := False
    atariCore.io.CONSOL_SELECT := False
    atariCore.io.CONSOL_START  := False

    // DMA: JOP uses SDRAM arbiter directly
    atariCore.io.DMA_FETCH              := False
    atariCore.io.DMA_READ_ENABLE        := False
    atariCore.io.DMA_32BIT_WRITE_ENABLE := False
    atariCore.io.DMA_16BIT_WRITE_ENABLE := False
    atariCore.io.DMA_8BIT_WRITE_ENABLE  := False
    atariCore.io.DMA_ADDR               := B(0, 24 bits)
    atariCore.io.DMA_WRITE_DATA         := B(0, 32 bits)

    // =====================================================================
    // CH376T SPI — PMOD J11 (USB keyboard + SD card host)
    // INT is wired as card-detect so JOP polls it via the cd signal.
    // =====================================================================
    io.ch376Sclk := jopCore.devicePin[Bool]("sdSpi", "sclk")
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
    // BmbToSdramReq — JOP BMB -> SDRAM request protocol
    // =====================================================================
    val bmbBridge = BmbToSdramReq(boardConfig.jopConfig.memConfig.bmbParameter)
    bmbBridge.io.bmb <> jopCore.io.bmb

    // =====================================================================
    // SDRAM Arbiter — Atari (priority) + JOP
    // =====================================================================
    val arbiter = new SdramArbiter

    arbiter.io.a.request        := atariCore.io.SDRAM_REQUEST
    arbiter.io.a.readEnable     := atariCore.io.SDRAM_READ_ENABLE
    arbiter.io.a.writeEnable    := atariCore.io.SDRAM_WRITE_ENABLE
    arbiter.io.a.addr           := B(boardConfig.atariAddrPrefix, 1 bit) ## atariCore.io.SDRAM_ADDR
    arbiter.io.a.dataIn         := atariCore.io.SDRAM_DI
    arbiter.io.a.byteAccess     := atariCore.io.SDRAM_8BIT_WRITE_ENABLE
    arbiter.io.a.wordAccess     := atariCore.io.SDRAM_16BIT_WRITE_ENABLE
    arbiter.io.a.longwordAccess := atariCore.io.SDRAM_32BIT_WRITE_ENABLE
    arbiter.io.a.refresh        := atariCore.io.SDRAM_REFRESH
    atariCore.io.SDRAM_REQUEST_COMPLETE := arbiter.io.a.complete
    atariCore.io.SDRAM_DO               := arbiter.io.a.dataOut

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

    // =====================================================================
    // SDRAM Controller
    // =====================================================================
    val sdramCtrl = new SdramStatemachine(
      ADDRESS_WIDTH = 24,
      AP_BIT        = 10,
      COLUMN_WIDTH  = 9,
      ROW_WIDTH     = 13
    )
    sdramCtrl.io.CLK_SYSTEM      := clkSys
    sdramCtrl.io.CLK_SDRAM       := clkSdram
    sdramCtrl.io.RESET_N         := resetN
    sdramCtrl.io.REQUEST         := arbiter.io.sdram.request
    sdramCtrl.io.READ_EN         := arbiter.io.sdram.readEnable
    sdramCtrl.io.WRITE_EN        := arbiter.io.sdram.writeEnable
    sdramCtrl.io.BYTE_ACCESS     := arbiter.io.sdram.byteAccess
    sdramCtrl.io.WORD_ACCESS     := arbiter.io.sdram.wordAccess
    sdramCtrl.io.LONGWORD_ACCESS := arbiter.io.sdram.longwordAccess
    sdramCtrl.io.REFRESH         := arbiter.io.sdram.refresh
    sdramCtrl.io.ADDRESS_IN      := arbiter.io.sdram.addr
    sdramCtrl.io.DATA_IN         := arbiter.io.sdram.dataIn
    arbiter.io.sdram.complete    := sdramCtrl.io.COMPLETE
    arbiter.io.sdram.dataOut     := sdramCtrl.io.DATA_OUT

    io.sdram.addr  := sdramCtrl.io.SDRAM_ADDR
    io.sdram.ba(0) := sdramCtrl.io.SDRAM_BA0
    io.sdram.ba(1) := sdramCtrl.io.SDRAM_BA1
    io.sdram.cs_n  := sdramCtrl.io.SDRAM_CS_N
    io.sdram.ras_n := sdramCtrl.io.SDRAM_RAS_N
    io.sdram.cas_n := sdramCtrl.io.SDRAM_CAS_N
    io.sdram.we_n  := sdramCtrl.io.SDRAM_WE_N
    io.sdram.dqml  := sdramCtrl.io.SDRAM_ldqm
    io.sdram.dqmh  := sdramCtrl.io.SDRAM_udqm
    sdramCtrl.io.SDRAM_DQ_IN := io.sdramDq
    when(sdramCtrl.io.SDRAM_DQ_OE) { io.sdramDq := sdramCtrl.io.SDRAM_DQ_OUT }

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
    val vgaMux = new VgaOverlayMux
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
    // LEDs
    // =====================================================================
    io.led(0) := pllLocked
    io.led(1) := ~atariPin[Bool]("osdEnable")   // on when Atari running
  }
}

object Atari800Ep4cgx150JopSv extends App {
  SpinalConfig(
    mode            = SystemVerilog,
    targetDirectory = "generated"
  ).generate(new Atari800Ep4cgx150JopTop)
}
