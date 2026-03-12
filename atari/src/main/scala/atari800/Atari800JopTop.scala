package atari800

import spinal.core._
import spinal.lib._
import spinal.lib.bus.bmb._
import jop.system._
import jop.io._

// =========================================================================
// HDMI pin bundle
// =========================================================================
case class HdmiPins() extends Bundle with IMasterSlave {
  val d0p  = Bool()
  val d0n  = Bool()
  val d1p  = Bool()
  val d1n  = Bool()
  val d2p  = Bool()
  val d2n  = Bool()
  val clkp = Bool()
  val clkn = Bool()

  override def asMaster(): Unit = out(d0p, d0n, d1p, d1n, d2p, d2n, clkp, clkn)
}

// =========================================================================
// PLL BlackBox for 10CL025 board
// 50MHz -> 56.67MHz system, 56.67MHz phase-shifted SDRAM, 25MHz VGA text
// (Quartus IP: ALTPLL, 3 outputs)
// =========================================================================
class PllAtari800 extends BlackBox {
  val io = new Bundle {
    val inclk0 = in  Bool()   // 50 MHz input
    val c0     = out Bool()   // 56.67 MHz system clock
    val c1     = out Bool()   // 56.67 MHz SDRAM clock (phase-shifted)
    val c2     = out Bool()   // 25 MHz VGA text pixel clock
    val locked = out Bool()
  }
  noIoPrefix()
}

// =========================================================================
// Atari800JopTop — Custom board top-level
//
// Integrates:
//   Atari 800 core + scandoubler
//   JOP soft-core (SD/USB/OSD/config) — direct instantiation, no BlackBox
//   SDRAM arbiter (Atari priority)
//   VGA overlay mux (Atari video / JOP text)
//   HDMI TMDS encoder (DvidOut)
//   Audio sigma-delta DAC
//
// Target: Intel Cyclone 10 LP 10CL025YU256C8G
// SDRAM: W9825G6KH (32MB, 16-bit SDR)
// =========================================================================
class Atari800JopTop extends Component {
  val io = new Bundle {
    val clock50 = in Bool()

    // SDRAM
    val sdram   = master(SdramCtrlPins())
    val sdramDq = inout(Analog(Bits(16 bits)))

    // VGA (resistor DAC, 4-bit per channel)
    val vga = master(VgaPins(4))

    // HDMI (4 LVDS pairs via DvidOut)
    val hdmi = master(HdmiPins())

    // Audio (sigma-delta DAC)
    val audioL = out Bool()
    val audioR = out Bool()

    // SPI bus (CH376T + MCP3208)
    val spiSclk = out Bool()
    val spiMosi = out Bool()
    val spiMiso = in  Bool()
    val spiCs0  = out Bool()   // CH376T
    val spiCs1  = out Bool()   // MCP3208

    // Joystick ports (4x DB-9, active low)
    val joy1 = JoystickPins()
    val joy2 = JoystickPins()
    val joy3 = JoystickPins()
    val joy4 = JoystickPins()

    // Cartridge slot
    val cart = master(CartridgePins())

    // UART debug
    val uartTx = out Bool()
    val uartRx = in  Bool()

    // LEDs
    val led = out Bits(4 bits)
  }

  // =========================================================================
  // PLL
  // =========================================================================
  val pll = new PllAtari800
  pll.io.inclk0 := io.clock50

  val clkSys      = pll.io.c0      // 56.67 MHz
  val clkSdram    = pll.io.c1      // 56.67 MHz phase-shifted
  val clkVgaText  = pll.io.c2      // 25 MHz
  val pllLocked   = pll.io.locked

  io.sdram.clk := clkSdram

  // System clock domain
  val sysDomain = ClockDomain(
    clock  = clkSys,
    reset  = pllLocked,
    config = ClockDomainConfig(
      clockEdge        = RISING,
      resetKind        = ASYNC,
      resetActiveLevel = LOW
    )
  )

  // VGA text pixel clock domain (25 MHz)
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
    // JOP Soft-Core (direct instantiation)
    // =====================================================================
    val jopConfig = JopCoreForAtari.config
    val jopCore = JopCore(config = jopConfig, vgaCd = Some(vgaTextDomain))

    // Single core — tie off multicore signals
    jopCore.io.syncIn.halted := False
    jopCore.io.syncIn.s_out  := False
    jopCore.io.syncIn.status := False
    jopCore.io.snoopIn.foreach { si =>
      si.valid := False; si.isArray := False; si.handle := 0; si.index := 0
    }
    jopCore.io.debugHalt    := False
    jopCore.io.debugRamAddr := 0

    // AtariCtrl — access external pins via JopCore passthrough (no hierarchy violation)
    val atariPins = jopCore.devicePins("atariCtrl")
    def atariPin[T <: Data](name: String): T =
      atariPins.elements.find(_._1 == name).get._2.asInstanceOf[T]

    atariPin[Bool]("pllLocked") := pllLocked

    // Reset: PLL lock + JOP cold-reset control
    val resetN = pllLocked & ~atariPin[Bool]("coldReset")

    // Enable dividers (same as AC608)
    val colourEnable  = Reg(Bool()) init False
    val doubledEnable = Reg(Bool()) init False
    colourEnable := ~colourEnable
    when(colourEnable) {
      doubledEnable := ~doubledEnable
    }

    // =====================================================================
    // Atari 800 Core
    // =====================================================================
    val atariCore = new Atari800CoreSimpleSdram(
      cycle_length = 32,
      video_bits   = 8,
      palette      = 0,
      internal_rom = 1,
      internal_ram = 0,
      low_memory   = 0,
      stereo       = 1,
      covox        = 1
    )

    // Video
    val videoVs = atariCore.io.VIDEO_VS
    val videoHs = atariCore.io.VIDEO_HS
    val videoCs = atariCore.io.VIDEO_CS
    val videoB  = atariCore.io.VIDEO_B

    // Audio
    val audioLPcm = atariCore.io.AUDIO_L
    val audioRPcm = atariCore.io.AUDIO_R

    // Joysticks: merge hardware DB-9 ports with JOP software overrides
    // Active low — AND hardware pins with JOP override (both must release)
    atariCore.io.JOY1_n := io.joy1.packed & atariPin[Bits]("joy1_n")
    atariCore.io.JOY2_n := io.joy2.packed & atariPin[Bits]("joy2_n")
    atariCore.io.JOY3_n := io.joy3.packed & atariPin[Bits]("joy3_n")
    atariCore.io.JOY4_n := io.joy4.packed & atariPin[Bits]("joy4_n")

    // Paddles (from MCP3208 via JOP -> AtariCtrl)
    atariCore.io.PADDLE0 := atariPin[SInt]("paddle0")
    atariCore.io.PADDLE1 := atariPin[SInt]("paddle1")
    atariCore.io.PADDLE2 := atariPin[SInt]("paddle2")
    atariCore.io.PADDLE3 := atariPin[SInt]("paddle3")

    // Keyboard (from USB via JOP -> AtariCtrl)
    atariCore.io.KEYBOARD_RESPONSE := atariPin[Bits]("keyboardResponse")

    // SIO (no SIO on this board — directly unused)
    atariCore.io.SIO_RXD := True

    // Console buttons — directly active-high mapped
    // On the custom board, console buttons come from JOP/USB keyboard.
    // No physical buttons — directly use JOP control.
    atariCore.io.CONSOL_OPTION := False
    atariCore.io.CONSOL_SELECT := False
    atariCore.io.CONSOL_START  := False

    // Config from AtariCtrl
    atariCore.io.RAM_SELECT                := atariPin[Bits]("ramSelect")
    atariCore.io.PAL                       := atariPin[Bool]("pal")
    atariCore.io.HALT                      := False
    atariCore.io.TURBO_VBLANK_ONLY         := atariPin[Bool]("turboVblankOnly")
    atariCore.io.THROTTLE_COUNT_6502       := atariPin[Bits]("throttleCount")
    atariCore.io.emulated_cartridge_select := atariPin[Bits]("cartSelect")
    atariCore.io.freezer_enable            := False
    atariCore.io.freezer_activate          := False
    atariCore.io.atari800mode              := atariPin[Bool]("atari800mode")
    atariCore.io.HIRES_ENA                 := atariPin[Bool]("hiresEna")

    // DMA — JOP uses SDRAM arbiter directly, not the DMA path
    atariCore.io.DMA_FETCH              := False
    atariCore.io.DMA_READ_ENABLE        := False
    atariCore.io.DMA_32BIT_WRITE_ENABLE := False
    atariCore.io.DMA_16BIT_WRITE_ENABLE := False
    atariCore.io.DMA_8BIT_WRITE_ENABLE  := False
    atariCore.io.DMA_ADDR               := B(0, 24 bits)
    atariCore.io.DMA_WRITE_DATA         := B(0, 32 bits)

    // =====================================================================
    // SPI pins (single CS to CH376T; MCP3208 CS1 not yet in JOP)
    // =====================================================================
    io.spiSclk := jopCore.devicePin[Bool]("sdSpi", "sclk")
    io.spiMosi := jopCore.devicePin[Bool]("sdSpi", "mosi")
    jopCore.devicePin[Bool]("sdSpi", "miso") := io.spiMiso
    io.spiCs0 := jopCore.devicePin[Bool]("sdSpi", "cs")
    jopCore.devicePin[Bool]("sdSpi", "cd") := True   // card always present
    io.spiCs1 := True   // MCP3208 deselected (CS1 not yet exposed by JOP)

    // UART debug
    io.uartTx := jopCore.devicePin[Bool]("uart", "txd")
    jopCore.devicePin[Bool]("uart", "rxd") := io.uartRx

    // =====================================================================
    // BmbToSdramReq — JOP BMB -> SDRAM request protocol
    // =====================================================================
    val bmbBridge = BmbToSdramReq(jopConfig.memConfig.bmbParameter)
    bmbBridge.io.bmb <> jopCore.io.bmb

    // =====================================================================
    // SDRAM Arbiter — Atari (priority) + JOP -> SdramStatemachine
    // =====================================================================
    val arbiter = new SdramArbiter

    // Port A: Atari core
    arbiter.io.a.request        := atariCore.io.SDRAM_REQUEST
    arbiter.io.a.readEnable     := atariCore.io.SDRAM_READ_ENABLE
    arbiter.io.a.writeEnable    := atariCore.io.SDRAM_WRITE_ENABLE
    arbiter.io.a.addr           := B"0" ## atariCore.io.SDRAM_ADDR  // 23->24, bank 0
    arbiter.io.a.dataIn         := atariCore.io.SDRAM_DI
    arbiter.io.a.byteAccess     := atariCore.io.SDRAM_8BIT_WRITE_ENABLE
    arbiter.io.a.wordAccess     := atariCore.io.SDRAM_16BIT_WRITE_ENABLE
    arbiter.io.a.longwordAccess := atariCore.io.SDRAM_32BIT_WRITE_ENABLE
    arbiter.io.a.refresh        := atariCore.io.SDRAM_REFRESH
    atariCore.io.SDRAM_REQUEST_COMPLETE := arbiter.io.a.complete
    atariCore.io.SDRAM_DO               := arbiter.io.a.dataOut

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

    // SDRAM physical pins
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
    when(sdramCtrl.io.SDRAM_DQ_OE) {
      io.sdramDq := sdramCtrl.io.SDRAM_DQ_OUT
    }

    // =====================================================================
    // Scandoubler: 15kHz Atari -> 31kHz VGA
    // =====================================================================
    val scandoubler = new Scandoubler(video_bits = 8)
    scandoubler.io.VGA                := True
    scandoubler.io.COMPOSITE_ON_HSYNC := False
    scandoubler.io.colour_enable      := colourEnable
    scandoubler.io.doubled_enable     := doubledEnable
    scandoubler.io.scanlines_on       := False
    scandoubler.io.pal                := atariPin[Bool]("pal")
    scandoubler.io.colour_in          := videoB
    scandoubler.io.vsync_in           := videoVs
    scandoubler.io.hsync_in           := videoHs
    scandoubler.io.csync_in           := videoCs

    // =====================================================================
    // VGA Overlay Mux — Atari video / JOP OSD text
    // =====================================================================
    val vgaMux = new VgaOverlayMux
    vgaMux.io.osdEnable := atariPin[Bool]("osdEnable")

    // Atari scandoubler -> mux
    vgaMux.io.atariR     := scandoubler.io.R
    vgaMux.io.atariG     := scandoubler.io.G
    vgaMux.io.atariB     := scandoubler.io.B
    vgaMux.io.atariHsync := scandoubler.io.HSYNC
    vgaMux.io.atariVsync := scandoubler.io.VSYNC

    // JOP VGA text -> mux
    vgaMux.io.jopR     := jopCore.devicePin[Bits]("vgaText", "vgaR")
    vgaMux.io.jopG     := jopCore.devicePin[Bits]("vgaText", "vgaG")
    vgaMux.io.jopB     := jopCore.devicePin[Bits]("vgaText", "vgaB")
    vgaMux.io.jopHsync := jopCore.devicePin[Bool]("vgaText", "vgaHsync")
    vgaMux.io.jopVsync := jopCore.devicePin[Bool]("vgaText", "vgaVsync")

    // VGA output (top 4 bits via resistor DAC)
    io.vga.r     := vgaMux.io.r(7 downto 4)
    io.vga.g     := vgaMux.io.g(7 downto 4)
    io.vga.b     := vgaMux.io.b(7 downto 4)
    io.vga.hsync := vgaMux.io.hsync
    io.vga.vsync := vgaMux.io.vsync

    // =====================================================================
    // HDMI Output (DvidOut TMDS encoder)
    // TODO: Restore DvidOut.scala from unused_scala/ when ready.
    //       Needs 5x pixel clock PLL output (~142 MHz for 28.34 MHz pixel).
    //       For now, directly tie HDMI pins low.
    // =====================================================================
    io.hdmi.d0p  := False
    io.hdmi.d0n  := False
    io.hdmi.d1p  := False
    io.hdmi.d1n  := False
    io.hdmi.d2p  := False
    io.hdmi.d2n  := False
    io.hdmi.clkp := False
    io.hdmi.clkn := False

    // =====================================================================
    // Audio: Sigma-delta PWM DAC
    // =====================================================================
    val audioDacL = new AudioPwm
    audioDacL.io.audioIn := audioLPcm
    io.audioL := audioDacL.io.pwmOut

    val audioDacR = new AudioPwm
    audioDacR.io.audioIn := audioRPcm
    io.audioR := audioDacR.io.pwmOut

    // =====================================================================
    // Cartridge slot
    // TODO: Atari800CoreSimpleSdram doesn't expose cart bus pins.
    //       Need to add CART_ADDR, CART_S4_N, CART_S5_N, CART_CCTL_N,
    //       CART_RD4, CART_RD5 to the SimpleSdram wrapper, then wire here.
    // =====================================================================
    io.cart.addr   := B(0, 13 bits)
    io.cart.s4_n   := True
    io.cart.s5_n   := True
    io.cart.cctl_n := True
    io.cart.phi2   := clkSys

    // =====================================================================
    // LEDs
    // =====================================================================
    io.led(0) := pllLocked
    io.led(1) := ~atariPin[Bool]("osdEnable")  // on when Atari running
    io.led(2) := arbiter.io.sdram.request      // SDRAM activity
    io.led(3) := jopCore.devicePin[Bool]("uart", "txd")  // UART activity (inverted)
  }
}
