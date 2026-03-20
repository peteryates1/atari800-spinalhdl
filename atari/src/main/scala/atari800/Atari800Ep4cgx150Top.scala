package atari800

import spinal.core._
import spinal.lib._

// AtariPll BlackBox — QMTECH EP4CGX150 ALTPLL
// 50 MHz -> 56.67 MHz (multiply=17, divide=15)
class AtariPll extends BlackBox {
  setDefinitionName("atari_pll")

  val io = new Bundle {
    val areset = in  Bool()
    val inclk0 = in  Bool()
    val c0     = out Bool()   // 56.67 MHz system clock
    val c1     = out Bool()   // 56.67 MHz SDRAM clock (-3 ns phase shift)
    val c2     = out Bool()   // unused
    val c3     = out Bool()   // unused
    val locked = out Bool()
  }

  noIoPrefix()
}

// Atari 800 bare-metal top for QMTECH EP4CGX150 + DB_FPGA daughter board.
// BRAM-only Atari core — 48K internal RAM, no SDRAM access.
// SDRAM is reserved for JOP supervisor (future).
//
// Clock: 50 MHz ALTPLL (×17/÷15) → 56.67 MHz → cycle_length=32 → 6502 at ~1.77 MHz
// RAM:   48K in BRAM (24 M9K of 414 available, ~6%)
// VGA:   DB_FPGA daughter board, 5-6-5 resistor DAC, scandoubled ~31 kHz
class Atari800Ep4cgx150Top extends Component {
  val io = new Bundle {
    // 50 MHz oscillator (PIN_B14)
    val clk_in  = in Bool()

    // VGA — DB_FPGA daughter board 5-6-5 DAC
    val vga_hs  = out Bool()
    val vga_vs  = out Bool()
    val vga_r   = out Bits(5 bits)
    val vga_g   = out Bits(6 bits)
    val vga_b   = out Bits(5 bits)

    // LEDs on core board (active high)
    val led     = out Bits(2 bits)
  }

  // =========================================================================
  // PLL: ALTPLL 50 MHz -> 56.67 MHz (multiply=17, divide=15)
  // =========================================================================
  val pll = new AtariPll
  pll.io.areset := False
  pll.io.inclk0 := io.clk_in

  val clkSys    = pll.io.c0   // 56.67 MHz
  val pllLocked = pll.io.locked

  val sysDomain = ClockDomain(
    clock  = clkSys,
    reset  = pllLocked,
    config = ClockDomainConfig(
      clockEdge        = RISING,
      resetKind        = ASYNC,
      resetActiveLevel = LOW
    )
  )

  val sysArea = new ClockingArea(sysDomain) {

    // Scandoubler enable dividers (identical to AC608 pattern)
    val colourEnable  = Reg(Bool()) init False
    val doubledEnable = Reg(Bool()) init False
    colourEnable := ~colourEnable
    when(colourEnable) { doubledEnable := ~doubledEnable }

    // =========================================================================
    // Atari 800 Core
    // Atari 800 OS (Os8 + Os2), 48K internal BRAM, no BASIC.
    // 56.67 MHz / cycle_length=32 = 1.771 MHz 6502 (target 1.790 MHz, -1.1%)
    // 48K BRAM: 0x0000-0xBFFF in GenericRamInfer (24 M9K). No SDRAM access.
    // =========================================================================
    val atari = new Atari800CoreSimpleSdram(
      cycle_length   = 32,
      video_bits     = 8,
      palette        = 0,
      internal_rom   = 3,       // Atari 800 OS (Os8 + Os2)
      internal_ram   = 49152,   // 48K in BRAM
      basic_in_sdram = false,   // Cartridge ROM in internal BRAM — route $A000-BFFF to internal ROM
      cartridge_rom  = "roms/Star Raiders.rom"
    )

    // Config — Star Raiders cartridge, 48K BRAM-only mode
    atari.io.PAL                       := True
    atari.io.RAM_SELECT                := B"011"  // 48K mode
    atari.io.HALT                      := False
    atari.io.TURBO_VBLANK_ONLY         := False
    atari.io.THROTTLE_COUNT_6502       := B(31, 6 bits)   // cycle_length - 1
    atari.io.emulated_cartridge_select := B(0, 6 bits)
    atari.io.freezer_enable            := False
    atari.io.freezer_activate          := False
    atari.io.atari800mode              := True
    atari.io.HIRES_ENA                 := False

    // Inputs not connected for initial bring-up
    atari.io.JOY1_n           := B"11111"
    atari.io.JOY2_n           := B"11111"
    atari.io.JOY3_n           := B"11111"
    atari.io.JOY4_n           := B"11111"
    atari.io.PADDLE0          := S(0, 8 bits)
    atari.io.PADDLE1          := S(0, 8 bits)
    atari.io.PADDLE2          := S(0, 8 bits)
    atari.io.PADDLE3          := S(0, 8 bits)
    atari.io.PADDLE4          := S(0, 8 bits)
    atari.io.PADDLE5          := S(0, 8 bits)
    atari.io.PADDLE6          := S(0, 8 bits)
    atari.io.PADDLE7          := S(0, 8 bits)
    atari.io.KEYBOARD_RESPONSE := B"11"
    atari.io.SIO_RXD           := True
    atari.io.CONSOL_OPTION     := False
    atari.io.CONSOL_SELECT     := False
    atari.io.CONSOL_START      := False
    atari.io.DMA_FETCH              := False
    atari.io.DMA_READ_ENABLE        := False
    atari.io.DMA_32BIT_WRITE_ENABLE := False
    atari.io.DMA_16BIT_WRITE_ENABLE := False
    atari.io.DMA_8BIT_WRITE_ENABLE  := False
    atari.io.DMA_ADDR               := B(0, 24 bits)
    atari.io.DMA_WRITE_DATA         := B(0, 32 bits)

    // SDRAM not used — 48K BRAM covers all Atari memory.
    // Tie off SDRAM interface so the core sees no completions.
    atari.io.SDRAM_REQUEST_COMPLETE := False
    atari.io.SDRAM_DO               := B(0, 32 bits)

    // =========================================================================
    // Scandoubler: 15 kHz → 31 kHz VGA
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

    // DB_FPGA VGA DAC: 5 red, 6 green, 5 blue bits (top bits of 8-bit channels)
    io.vga_r  := scandoubler.io.R(7 downto 3)
    io.vga_g  := scandoubler.io.G(7 downto 2)
    io.vga_b  := scandoubler.io.B(7 downto 3)
    io.vga_hs := scandoubler.io.HSYNC
    io.vga_vs := scandoubler.io.VSYNC

    // LEDs
    io.led(0) := pllLocked
    io.led(1) := ~atari.io.VIDEO_BLANK   // blinks during active video
  }
}

object Atari800Ep4cgx150Sv extends App {
  SpinalConfig(
    mode            = SystemVerilog,
    targetDirectory = "generated"
  ).generate(new Atari800Ep4cgx150Top)
}
