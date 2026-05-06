package atari800

import spinal.core._
import spinal.lib._

// Atari 800 standalone top for ATARI-800-LG-V1 base board + QMTECH EP4CGX150.
// BRAM-only: 48K user space, OS ROM, Star Raiders cartridge — no SDRAM, no JOP.
// Cart ROM auto-replaces upper RAM so total BRAM = 58 M9K (fits 10CL025).
//
// Clock: 50 MHz ALTPLL (x17/÷15) -> 56.67 MHz -> cycle_length=32 -> 6502 at ~1.77 MHz
// VGA:   5-5-5 resistor DAC on base board, scandoubled ~31 kHz
// Audio: 1-bit sigma-delta DAC on AUDIO_L / AUDIO_R pins
class Atari800LgV1Top extends Component {
  val io = new Bundle {
    val clk_in = in Bool()

    // VGA — 5-bit R/G/B DAC on base board
    val vga_hs = out Bool()
    val vga_vs = out Bool()
    val vga_r  = out Bits(5 bits)
    val vga_g  = out Bits(5 bits)
    val vga_b  = out Bits(5 bits)

    // Audio — sigma-delta 1-bit DAC
    val audio_l = out Bool()
    val audio_r = out Bool()

    // Joystick 1 (active low)
    val joy1Up    = in Bool()
    val joy1Down  = in Bool()
    val joy1Left  = in Bool()
    val joy1Right = in Bool()
    val joy1Fire  = in Bool()

    // Joystick 2 (active low)
    val joy2Up    = in Bool()
    val joy2Down  = in Bool()
    val joy2Left  = in Bool()
    val joy2Right = in Bool()
    val joy2Fire  = in Bool()

    // Console switches (active low — active when pressed)
    val consolOption = in Bool()
    val consolSelect = in Bool()
    val consolStart  = in Bool()
    val consolReset  = in Bool()

    // CH376T USB keyboard (SPI)
    val ch376Sck  = out Bool()
    val ch376Mosi = out Bool()
    val ch376Miso = in  Bool()
    val ch376Cs   = out Bool()
    val ch376Int  = in  Bool()
    val ch376Rst  = out Bool()
    val ch376SpiN = out Bool()

    // Debug UART TX (CH340 on base board)
    val uartTx = out Bool()

    // LEDs (4 on base board + 2 on core board)
    val led = out Bits(2 bits)
    val ledBase = out Bits(4 bits)
  }

  // =========================================================================
  // PLL: ALTPLL 50 MHz -> 56.67 MHz
  // =========================================================================
  val pll = new AtariPll
  pll.io.areset := False
  pll.io.inclk0 := io.clk_in

  val clkSys    = pll.io.c0
  val pllLocked = pll.io.locked

  // Reset: active low — system runs when PLL locked AND reset button not pressed
  val sysResetN = pllLocked & io.consolReset  // both active low

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

    // =========================================================================
    // Atari 800 Core — 48K BRAM, 800 OS, Star Raiders cartridge
    // =========================================================================
    val atari = new Atari800CoreSimpleSdram(
      cycle_length   = 32,
      video_bits     = 8,
      palette        = 0,
      internal_rom   = 3,
      internal_ram   = 49152,  // 48K address space; cart ROM auto-replaces upper RAM
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

    // Joystick 1: active low, bit order FRLDU (MSB to LSB)
    atari.io.JOY1_n := io.joy1Fire ## io.joy1Right ## io.joy1Left ## io.joy1Down ## io.joy1Up

    // Joystick 2
    atari.io.JOY2_n := io.joy2Fire ## io.joy2Right ## io.joy2Left ## io.joy2Down ## io.joy2Up

    // Joystick 3 & 4 — not connected
    atari.io.JOY3_n := B"11111"
    atari.io.JOY4_n := B"11111"

    // Paddles — not connected (no ADC driver yet)
    atari.io.PADDLE0 := S(0, 8 bits)
    atari.io.PADDLE1 := S(0, 8 bits)
    atari.io.PADDLE2 := S(0, 8 bits)
    atari.io.PADDLE3 := S(0, 8 bits)
    atari.io.PADDLE4 := S(0, 8 bits)
    atari.io.PADDLE5 := S(0, 8 bits)
    atari.io.PADDLE6 := S(0, 8 bits)
    atari.io.PADDLE7 := S(0, 8 bits)

    // =========================================================================
    // CH376T USB keyboard bridge (runs at 56.67 MHz sys clock)
    // =========================================================================
    val kbd = new Ch376UsbKeyboard(sysClkHz = 56670000)
    kbd.io.spiMiso       := io.ch376Miso
    kbd.io.intN          := io.ch376Int
    io.ch376Sck          := kbd.io.spiSck
    io.ch376Mosi         := kbd.io.spiMosi
    io.ch376Cs           := kbd.io.spiCsN
    io.ch376Rst          := kbd.io.rstOut
    io.ch376SpiN         := kbd.io.spiModeN
    kbd.io.keyboardScan  := atari.io.KEYBOARD_SCAN
    atari.io.KEYBOARD_RESPONSE := kbd.io.keyboardResponse
    kbd.io.pokeyKeyIrq   := atari.io.KEY_IRQ_PULSE
    kbd.io.pokeyKeyHeld  := atari.io.KEY_HELD
    kbd.io.kbIrqEnabled  := atari.io.KB_IRQ_ENABLED
    kbd.io.kbIrqPending  := atari.io.KB_IRQ_PENDING
    kbd.io.kbIrqAck      := atari.io.KB_IRQ_ACK
    io.uartTx            := kbd.io.uartTx

    // SIO — idle
    atari.io.SIO_RXD := True

    // Console — buttons pull low on press, Atari expects active high for pressed
    // OR keyboard console keys with physical buttons
    atari.io.CONSOL_OPTION := ~io.consolOption | kbd.io.consolOption
    atari.io.CONSOL_SELECT := ~io.consolSelect | kbd.io.consolSelect
    atari.io.CONSOL_START  := ~io.consolStart  | kbd.io.consolStart

    // DMA not used
    atari.io.DMA_FETCH              := False
    atari.io.DMA_READ_ENABLE        := False
    atari.io.DMA_32BIT_WRITE_ENABLE := False
    atari.io.DMA_16BIT_WRITE_ENABLE := False
    atari.io.DMA_8BIT_WRITE_ENABLE  := False
    atari.io.DMA_ADDR               := B(0, 24 bits)
    atari.io.DMA_WRITE_DATA         := B(0, 32 bits)

    // SDRAM not used
    atari.io.SDRAM_REQUEST_COMPLETE := False
    atari.io.SDRAM_DO               := B(0, 32 bits)

    // =========================================================================
    // Scandoubler: 15 kHz -> 31 kHz VGA
    // =========================================================================
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

    // LG-V1 VGA DAC: 5-5-5 (take top 5 bits of each 8-bit channel)
    io.vga_r  := scandoubler.io.R(7 downto 3)
    io.vga_g  := scandoubler.io.G(7 downto 3)
    io.vga_b  := scandoubler.io.B(7 downto 3)
    io.vga_hs := scandoubler.io.HSYNC
    io.vga_vs := scandoubler.io.VSYNC

    // =========================================================================
    // Audio: 16-bit sigma-delta DAC -> 1-bit output
    // =========================================================================
    val sigmaDeltaL = Reg(UInt(17 bits)) init 0
    val sigmaDeltaR = Reg(UInt(17 bits)) init 0
    // Convert signed 16-bit audio to unsigned by flipping MSB
    val audioUnsignedL = (atari.io.AUDIO_L.asUInt ^ U(0x8000, 16 bits)).resize(17)
    val audioUnsignedR = (atari.io.AUDIO_R.asUInt ^ U(0x8000, 16 bits)).resize(17)
    sigmaDeltaL := sigmaDeltaL(15 downto 0).resize(17) + audioUnsignedL
    sigmaDeltaR := sigmaDeltaR(15 downto 0).resize(17) + audioUnsignedR
    io.audio_l := sigmaDeltaL(16)
    io.audio_r := sigmaDeltaR(16)

    // LEDs — core board
    io.led(0) := pllLocked
    io.led(1) := kbd.io.connected

    // Audio activity detector — lights LED when core produces non-silent audio
    val audioActive = RegInit(False)
    val audioTimeout = Reg(UInt(24 bits)) init 0
    val silence = (atari.io.AUDIO_L === B(0x0000, 16 bits)) || (atari.io.AUDIO_L === B(0x8000, 16 bits))
    when(!silence) {
      audioActive := True
      audioTimeout := 0
    } otherwise {
      audioTimeout := audioTimeout + 1
      when(audioTimeout.msb) { audioActive := False }
    }

    // LEDs — base board
    io.ledBase(0) := pllLocked
    io.ledBase(1) := kbd.io.connected
    io.ledBase(2) := ~atari.io.VIDEO_BLANK
    io.ledBase(3) := audioActive  // audio activity indicator
  }
}

object Atari800LgV1Sv extends App {
  SpinalConfig(
    mode            = SystemVerilog,
    targetDirectory = "generated"
  ).generate(new Atari800LgV1Top)
}
