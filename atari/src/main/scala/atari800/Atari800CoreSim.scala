package atari800

import spinal.core._
import spinal.lib._

// Simulation-friendly top-level:
// - No PLL BlackBox (uses implicit clock domain)
// - No Analog inout signals
// - Uses internal RAM (no SDRAM controller needed)
// - Behavioral SDRAM model driven from testbench via simple interface
class Atari800CoreSim(cartridge_rom: String = "") extends Component {
  val io = new Bundle {
    // VGA output
    val vga_r     = out Bits(4 bits)
    val vga_g     = out Bits(4 bits)
    val vga_b     = out Bits(4 bits)
    val vga_hsync = out Bool()
    val vga_vsync = out Bool()

    // Raw video (pre-scandoubler, 15kHz)
    val videoVs    = out Bool()
    val videoHs    = out Bool()
    val videoBlank = out Bool()

    // Audio PCM (raw 16-bit, skip PWM DAC for sim)
    val audioL = out Bits(16 bits)
    val audioR = out Bits(16 bits)

    // Console buttons (active-high for sim convenience)
    val reset_btn  = in Bool()
    val option_btn = in Bool()
    val select_btn = in Bool()
    val start_btn  = in Bool()

    // Joystick port 1 (active low: fire, right, left, down, up)
    val joy1 = in Bits(5 bits)

    // SDRAM interface - directly exposed for behavioral model in testbench
    val sdramRequest         = out Bool()
    val sdramRequestComplete = in  Bool()
    val sdramReadEnable      = out Bool()
    val sdramWriteEnable     = out Bool()
    val sdramAddr            = out Bits(23 bits)
    val sdramDo              = in  Bits(32 bits)
    val sdramDi              = out Bits(32 bits)
    val sdramWrite8          = out Bool()
    val sdramWrite16         = out Bool()
    val sdramWrite32         = out Bool()
    val sdramRefresh         = out Bool()

    // Debug
    val led = out Bits(2 bits)
    val dbgCpuEnable   = out Bool()
    val dbgMemReady    = out Bool()
    val dbgThrottle    = out Bool()
    val dbgAnticRdy    = out Bool()
    val dbgCpuFetch    = out Bool()
    val dbgCpuAddr     = out Bits(16 bits)
    val dbgCpuRwN      = out Bool()
    val dbgVideoB      = out Bits(8 bits)
    val dbgDmactl      = out Bits(7 bits)
    val dbgColbk       = out Bits(7 bits)
    val dbgGtiaWrEn    = out Bool()
    val dbgAnticWrEn   = out Bool()
  }

  // Enable signals (same divider chain as real top-level)
  val colourEnable  = Reg(Bool()) init False
  val doubledEnable = Reg(Bool()) init False
  colourEnable := ~colourEnable
  when(colourEnable) {
    doubledEnable := ~doubledEnable
  }

  // =================================================================
  // Atari 800 Core with internal ROM + 48K internal RAM (BRAM-only)
  // =================================================================
  val atariCore = new Atari800CoreSimpleSdram(
    cycle_length  = 32,
    video_bits    = 8,
    palette       = 0,
    internal_rom  = 3,
    internal_ram  = 49152,
    low_memory    = 0,
    stereo        = 1,
    covox         = 1,
    cartridge_rom = cartridge_rom
  )

  // Video
  io.videoVs    := atariCore.io.VIDEO_VS
  io.videoHs    := atariCore.io.VIDEO_HS
  io.videoBlank := atariCore.io.VIDEO_BLANK

  // Audio
  io.audioL := atariCore.io.AUDIO_L
  io.audioR := atariCore.io.AUDIO_R

  // Joysticks
  atariCore.io.JOY1_n := io.joy1
  atariCore.io.JOY2_n := B"11111"
  atariCore.io.JOY3_n := B"11111"
  atariCore.io.JOY4_n := B"11111"

  // Paddles (centered)
  atariCore.io.PADDLE0 := S(0, 8 bits)
  atariCore.io.PADDLE1 := S(0, 8 bits)
  atariCore.io.PADDLE2 := S(0, 8 bits)
  atariCore.io.PADDLE3 := S(0, 8 bits)
  atariCore.io.PADDLE4 := S(0, 8 bits)
  atariCore.io.PADDLE5 := S(0, 8 bits)
  atariCore.io.PADDLE6 := S(0, 8 bits)
  atariCore.io.PADDLE7 := S(0, 8 bits)

  // Keyboard (no keys pressed)
  atariCore.io.KEYBOARD_RESPONSE := B"11"

  // SIO
  atariCore.io.SIO_RXD := True

  // Console
  atariCore.io.CONSOL_OPTION := io.option_btn
  atariCore.io.CONSOL_SELECT := io.select_btn
  atariCore.io.CONSOL_START  := io.start_btn

  // SDRAM interface directly exposed to testbench
  io.sdramRequest     := atariCore.io.SDRAM_REQUEST
  io.sdramReadEnable  := atariCore.io.SDRAM_READ_ENABLE
  io.sdramWriteEnable := atariCore.io.SDRAM_WRITE_ENABLE
  io.sdramAddr        := atariCore.io.SDRAM_ADDR
  io.sdramDi          := atariCore.io.SDRAM_DI
  io.sdramWrite8      := atariCore.io.SDRAM_8BIT_WRITE_ENABLE
  io.sdramWrite16     := atariCore.io.SDRAM_16BIT_WRITE_ENABLE
  io.sdramWrite32     := atariCore.io.SDRAM_32BIT_WRITE_ENABLE
  io.sdramRefresh     := atariCore.io.SDRAM_REFRESH
  atariCore.io.SDRAM_REQUEST_COMPLETE := io.sdramRequestComplete
  atariCore.io.SDRAM_DO               := io.sdramDo

  // No DMA
  atariCore.io.DMA_FETCH              := False
  atariCore.io.DMA_READ_ENABLE        := False
  atariCore.io.DMA_32BIT_WRITE_ENABLE := False
  atariCore.io.DMA_16BIT_WRITE_ENABLE := False
  atariCore.io.DMA_8BIT_WRITE_ENABLE  := False
  atariCore.io.DMA_ADDR               := B(0, 24 bits)
  atariCore.io.DMA_WRITE_DATA         := B(0, 32 bits)

  // Config
  atariCore.io.RAM_SELECT                := B"011"  // 48K (BRAM-only, no SDRAM)
  atariCore.io.PAL                       := True
  atariCore.io.HALT                      := False
  atariCore.io.TURBO_VBLANK_ONLY         := False
  atariCore.io.THROTTLE_COUNT_6502       := B(31, 6 bits)
  atariCore.io.emulated_cartridge_select := B(0, 6 bits)
  atariCore.io.freezer_enable            := False
  atariCore.io.freezer_activate          := False
  atariCore.io.atari800mode              := True
  atariCore.io.HIRES_ENA                 := False

  // =================================================================
  // Scandoubler: 15kHz -> 31kHz VGA
  // =================================================================
  val scandoubler = new Scandoubler(video_bits = 8)
  scandoubler.io.VGA                := True
  scandoubler.io.COMPOSITE_ON_HSYNC := False
  scandoubler.io.colour_enable      := colourEnable
  scandoubler.io.doubled_enable     := doubledEnable
  scandoubler.io.scanlines_on       := False
  scandoubler.io.pal                := True
  scandoubler.io.colour_in          := atariCore.io.VIDEO_B
  scandoubler.io.vsync_in           := atariCore.io.VIDEO_VS
  scandoubler.io.hsync_in           := atariCore.io.VIDEO_HS
  scandoubler.io.csync_in           := atariCore.io.VIDEO_CS

  io.vga_r     := scandoubler.io.R(7 downto 4)
  io.vga_g     := scandoubler.io.G(7 downto 4)
  io.vga_b     := scandoubler.io.B(7 downto 4)
  io.vga_hsync := scandoubler.io.HSYNC
  io.vga_vsync := scandoubler.io.VSYNC

  // Debug
  io.dbgCpuEnable := atariCore.io.dbgSharedEnable
  io.dbgMemReady  := atariCore.io.dbgMemReadyCpu
  io.dbgThrottle  := atariCore.io.dbgSharedEnable
  io.dbgAnticRdy  := atariCore.io.dbgAnticRdy
  io.dbgCpuFetch  := False  // TODO: needs exposing
  io.dbgCpuAddr   := B(0, 16 bits)  // TODO
  io.dbgCpuRwN    := True  // TODO

  // Debug video + GTIA/ANTIC
  io.dbgVideoB    := atariCore.io.VIDEO_B
  io.dbgDmactl    := atariCore.io.dbgDmactl
  io.dbgColbk     := atariCore.io.dbgColbk
  io.dbgGtiaWrEn  := atariCore.io.dbgGtiaWrEn
  io.dbgAnticWrEn := atariCore.io.dbgAnticWrEn

  // LEDs
  io.led(0) := ~io.reset_btn
  io.led(1) := io.sdramRequest
}
