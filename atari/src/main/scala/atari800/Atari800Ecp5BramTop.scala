package atari800

import spinal.core._
import spinal.lib._

// ECP5 PLL BlackBox — matches pll_ecp5.sv (EHXPLLL, 25 MHz -> 37.5 MHz)
class PllEcp5 extends BlackBox {
  setDefinitionName("PllAtari800")
  val io = new Bundle {
    val inclk0 = in  Bool()
    val c0     = out Bool()
    val c1     = out Bool()
    val c2     = out Bool()
    val locked = out Bool()
  }
  noIoPrefix()
}

// Atari 800 BRAM-only top for ECP5 (Colorlight i5 v7.0, LFE5U-25F).
// 48K user space, 800 OS ROM, Star Raiders cartridge — no SDRAM, no JOP.
// Cart ROM auto-replaces upper RAM so total BRAM stays constant.
//
// Clock: 25 MHz EHXPLLL -> 37.5 MHz -> cycle_length=21 -> 6502 at ~1.786 MHz
// VGA:   4-bit R/G/B, scandoubled ~31 kHz
class Atari800Ecp5BramTop extends Component {
  val io = new Bundle {
    val clk_in = in Bool()

    // VGA — 4-bit R/G/B
    val vga_hs = out Bool()
    val vga_vs = out Bool()
    val vga_r  = out Bits(4 bits)
    val vga_g  = out Bits(4 bits)
    val vga_b  = out Bits(4 bits)

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

    // Console switches (active low)
    val consolOption = in Bool()
    val consolSelect = in Bool()
    val consolStart  = in Bool()
    val consolReset  = in Bool()

    // Keyboard (directly exposed POKEY scan/response)
    val keyboardResponse = in  Bits(2 bits)
    val keyboardScan     = out Bits(6 bits)

    // SIO
    val sioRxd      = in  Bool()
    val sioTxd      = out Bool()
    val sioClockout = out Bool()

    // LEDs
    val led = out Bits(4 bits)
  }

  // =========================================================================
  // PLL: EHXPLLL 25 MHz -> 37.5 MHz
  // =========================================================================
  val pll = new PllEcp5
  pll.io.inclk0 := io.clk_in

  val clkSys    = pll.io.c0
  val pllLocked = pll.io.locked

  val sysResetN = pllLocked  // consolReset directly to core (active-low button when wired)

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
      cycle_length   = 21,
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
    atari.io.THROTTLE_COUNT_6502       := B(20, 6 bits)  // cycle_length - 1
    atari.io.emulated_cartridge_select := B(0, 6 bits)
    atari.io.freezer_enable            := False
    atari.io.freezer_activate          := False
    atari.io.atari800mode              := True
    atari.io.HIRES_ENA                 := False

    // Joystick 1: active low, bit order FRLDU (MSB to LSB)
    atari.io.JOY1_n := io.joy1Fire ## io.joy1Right ## io.joy1Left ## io.joy1Down ## io.joy1Up
    atari.io.JOY2_n := io.joy2Fire ## io.joy2Right ## io.joy2Left ## io.joy2Down ## io.joy2Up
    atari.io.JOY3_n := B"11111"
    atari.io.JOY4_n := B"11111"

    // Paddles — not connected
    atari.io.PADDLE0 := S(0, 8 bits)
    atari.io.PADDLE1 := S(0, 8 bits)
    atari.io.PADDLE2 := S(0, 8 bits)
    atari.io.PADDLE3 := S(0, 8 bits)
    atari.io.PADDLE4 := S(0, 8 bits)
    atari.io.PADDLE5 := S(0, 8 bits)
    atari.io.PADDLE6 := S(0, 8 bits)
    atari.io.PADDLE7 := S(0, 8 bits)

    // Keyboard
    atari.io.KEYBOARD_RESPONSE := io.keyboardResponse
    io.keyboardScan := atari.io.KEYBOARD_SCAN

    // SIO
    atari.io.SIO_RXD := io.sioRxd
    io.sioTxd     := atari.io.SIO_TXD
    io.sioClockout := atari.io.SIO_CLOCKOUT

    // Console — active-low buttons, Atari expects active high for pressed
    atari.io.CONSOL_OPTION := ~io.consolOption
    atari.io.CONSOL_SELECT := ~io.consolSelect
    atari.io.CONSOL_START  := ~io.consolStart

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

    // 4-bit VGA (top 4 bits of 8-bit scandoubler output)
    io.vga_r  := scandoubler.io.R(7 downto 4)
    io.vga_g  := scandoubler.io.G(7 downto 4)
    io.vga_b  := scandoubler.io.B(7 downto 4)
    io.vga_hs := scandoubler.io.HSYNC
    io.vga_vs := scandoubler.io.VSYNC

    // =========================================================================
    // Audio: 16-bit sigma-delta DAC -> 1-bit output
    // =========================================================================
    val sigmaDeltaL = Reg(UInt(17 bits)) init 0
    val sigmaDeltaR = Reg(UInt(17 bits)) init 0
    val audioUnsignedL = (atari.io.AUDIO_L.asUInt ^ U(0x8000, 16 bits)).resize(17)
    val audioUnsignedR = (atari.io.AUDIO_R.asUInt ^ U(0x8000, 16 bits)).resize(17)
    sigmaDeltaL := sigmaDeltaL(15 downto 0).resize(17) + audioUnsignedL
    sigmaDeltaR := sigmaDeltaR(15 downto 0).resize(17) + audioUnsignedR
    io.audio_l := sigmaDeltaL(16)
    io.audio_r := sigmaDeltaR(16)

    // LEDs
    io.led(0) := pllLocked
    io.led(1) := ~atari.io.VIDEO_BLANK
    io.led(2) := False
    io.led(3) := False
  }
}

object Atari800Ecp5BramSv extends App {
  SpinalConfig(
    mode            = SystemVerilog,
    targetDirectory = "generated"
  ).generate(new Atari800Ecp5BramTop)
}
