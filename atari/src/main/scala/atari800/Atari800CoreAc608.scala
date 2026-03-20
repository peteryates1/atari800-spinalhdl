package atari800

import spinal.core._
import spinal.lib._

// Atari 800XL on CoreCourse AC608 (Cyclone 10 LP 10CL025YU256C8G)
// Clean hardware implementation:
//   VGA output, PWM audio, physical cartridge port,
//   joystick inputs, Pico companion for SD/USB keyboard/config
class Atari800CoreAc608 extends Component {
  val io = new Bundle {
    val clock50 = in Bool()

    // SDRAM (W9825G6KH, 256Mbit, 16-bit) - CKE tied high on board
    val sdram    = master(SdramCtrlPins())
    val sdramDq  = inout(Analog(Bits(16 bits)))

    // VGA output (resistor DAC on carrier board)
    val vga     = master(VgaPins(4))

    // Audio PWM output (RC filter to audio jack)
    val audioL  = out Bool()
    val audioR  = out Bool()

    // Physical cartridge port
    val cart    = master(CartridgePins())

    // Joystick ports (DE-9, active low)
    val joy1    = JoystickPins()
    val joy2    = JoystickPins()

    // Console buttons (active low)
    val console = ConsolePins()

    // PS/2 keyboard (from Pico USB-to-PS/2 output)
    val ps2     = Ps2Pins()

    // Pico companion bus
    val picoData = inout(Analog(Bits(8 bits)))
    val picoAddr = in  Bits(3 bits)
    val picoWrN  = in  Bool()
    val picoRdN  = in  Bool()
    val picoIrqN = out Bool()

    // LEDs
    val led     = out Bits(2 bits)
  }

  // =========================================================================
  // PLL: 50 MHz -> 56.67 MHz system + SDRAM clocks (Altera IP BlackBox)
  // =========================================================================
  val pll = new PllSys
  pll.io.inclk0 := io.clock50

  val clk         = pll.io.c0
  val sdramClkInt = pll.io.c1
  val pllLocked   = pll.io.locked

  io.sdram.clk := sdramClkInt

  // Create system clock domain from PLL output
  val sysDomain = ClockDomain(
    clock  = clk,
    reset  = pllLocked,
    config = ClockDomainConfig(
      clockEdge        = RISING,
      resetKind        = ASYNC,
      resetActiveLevel = LOW
    )
  )

  val sysArea = new ClockingArea(sysDomain) {
    // Reset: hold until PLL locks, or Pico requests cold reset
    val picoColdReset = Bool()
    val resetN = pllLocked & io.console.reset_n & ~picoColdReset

    // Throttle: cycle_length-1 for standard 1.79MHz 6502 speed
    val throttleCount = B(31, 6 bits)

    // =====================================================================
    // Video signals from core (15kHz)
    // =====================================================================
    val videoVs, videoHs, videoCs = Bool()
    val videoRRaw, videoGRaw, videoBRaw = Bits(8 bits)
    val videoBlank, videoBurst, videoStartOfField, videoOddLine = Bool()

    // Scandoubled video (31kHz VGA)
    val sdVs, sdHs = Bool()
    val sdR, sdG, sdB = Bits(8 bits)

    // Enable signals
    val colourEnable  = Reg(Bool()) init False
    val doubledEnable = Reg(Bool()) init False
    colourEnable := ~colourEnable
    when(colourEnable) {
      doubledEnable := ~doubledEnable
    }

    // Audio from core
    val audioLPcm, audioRPcm = Bits(16 bits)

    // Keyboard
    val keyboardScan     = Bits(6 bits)
    val keyboardResponse = Bits(2 bits)

    // SDRAM controller signals
    val sdramRequest, sdramRequestComplete = Bool()
    val sdramReadEnable, sdramWriteEnable = Bool()
    val sdramAddr = Bits(23 bits)
    val sdramDo, sdramDi = Bits(32 bits)
    val sdramWrite32, sdramWrite16, sdramWrite8 = Bool()
    val sdramRefresh = Bool()
    val sdramResetN = Bool()

    // DMA interface
    val dmaFetch, dmaReadEnable = Bool()
    val dmaWrite32, dmaWrite16, dmaWrite8 = Bool()
    val dmaAddr = Bits(24 bits)
    val dmaWriteData = Bits(32 bits)
    val dmaMemReady = Bool()
    val dmaMemData  = Bits(32 bits)

    // Pico config
    val pal       = Bool()
    val ramSelect = Bits(3 bits)
    val turbo     = Bool()
    val cartSelect = Bits(6 bits)

    // Paddle values
    val paddle0, paddle1, paddle2, paddle3 = SInt(8 bits)

    // Cart bus internals
    val cartAddrOut = Bits(13 bits)
    val cartS4NInt, cartS5NInt, cartCctlNInt = Bool()

    // =================================================================
    // Atari 800 Core (native SpinalHDL)
    // =================================================================
    val atariCore = new Atari800CoreSimpleSdram(
      cycle_length   = 32,
      video_bits     = 8,
      palette        = 0,
      internal_rom   = 1,
      internal_ram   = 0,
      low_memory     = 0,
      stereo         = 1,
      covox          = 1,
      basic_in_sdram = true   // BASIC loaded from SD; not burned into FPGA fabric
    )

    // Video
    videoVs           := atariCore.io.VIDEO_VS
    videoHs           := atariCore.io.VIDEO_HS
    videoCs           := atariCore.io.VIDEO_CS
    videoBRaw         := atariCore.io.VIDEO_B
    videoGRaw         := atariCore.io.VIDEO_G
    videoRRaw         := atariCore.io.VIDEO_R
    videoBlank        := atariCore.io.VIDEO_BLANK
    videoBurst        := atariCore.io.VIDEO_BURST
    videoStartOfField := atariCore.io.VIDEO_START_OF_FIELD
    videoOddLine      := atariCore.io.VIDEO_ODD_LINE

    // Audio
    audioLPcm := atariCore.io.AUDIO_L
    audioRPcm := atariCore.io.AUDIO_R

    // Joysticks (FRLDU)
    atariCore.io.JOY1_n := io.joy1.packed
    atariCore.io.JOY2_n := io.joy2.packed
    atariCore.io.JOY3_n := B"11111"
    atariCore.io.JOY4_n := B"11111"

    // Paddles
    atariCore.io.PADDLE0 := paddle0
    atariCore.io.PADDLE1 := paddle1
    atariCore.io.PADDLE2 := paddle2
    atariCore.io.PADDLE3 := paddle3

    // Keyboard
    keyboardScan := atariCore.io.KEYBOARD_SCAN
    atariCore.io.KEYBOARD_RESPONSE := keyboardResponse

    // SIO
    atariCore.io.SIO_RXD := True

    // Console buttons
    atariCore.io.CONSOL_OPTION := ~io.console.option_n
    atariCore.io.CONSOL_SELECT := ~io.console.select_n
    atariCore.io.CONSOL_START  := ~io.console.start_n

    // SDRAM
    sdramRequest     := atariCore.io.SDRAM_REQUEST
    sdramReadEnable  := atariCore.io.SDRAM_READ_ENABLE
    sdramWriteEnable := atariCore.io.SDRAM_WRITE_ENABLE
    sdramAddr        := atariCore.io.SDRAM_ADDR
    sdramDi          := atariCore.io.SDRAM_DI
    sdramWrite32     := atariCore.io.SDRAM_32BIT_WRITE_ENABLE
    sdramWrite16     := atariCore.io.SDRAM_16BIT_WRITE_ENABLE
    sdramWrite8      := atariCore.io.SDRAM_8BIT_WRITE_ENABLE
    sdramRefresh     := atariCore.io.SDRAM_REFRESH
    atariCore.io.SDRAM_REQUEST_COMPLETE := sdramRequestComplete
    atariCore.io.SDRAM_DO := sdramDo

    // DMA
    atariCore.io.DMA_FETCH              := dmaFetch
    atariCore.io.DMA_READ_ENABLE        := dmaReadEnable
    atariCore.io.DMA_32BIT_WRITE_ENABLE := dmaWrite32
    atariCore.io.DMA_16BIT_WRITE_ENABLE := dmaWrite16
    atariCore.io.DMA_8BIT_WRITE_ENABLE  := dmaWrite8
    atariCore.io.DMA_ADDR               := dmaAddr
    atariCore.io.DMA_WRITE_DATA         := dmaWriteData
    dmaMemReady := atariCore.io.MEMORY_READY_DMA
    dmaMemData  := atariCore.io.DMA_MEMORY_DATA

    // Config
    atariCore.io.RAM_SELECT                := ramSelect
    atariCore.io.PAL                       := pal
    atariCore.io.HALT                      := False
    atariCore.io.TURBO_VBLANK_ONLY         := turbo
    atariCore.io.THROTTLE_COUNT_6502       := throttleCount
    atariCore.io.emulated_cartridge_select := cartSelect
    atariCore.io.freezer_enable            := False
    atariCore.io.freezer_activate          := False
    atariCore.io.atari800mode              := False
    atariCore.io.HIRES_ENA                 := False

    // =================================================================
    // SDRAM Controller (native SpinalHDL)
    // =================================================================
    val sdramCtrl = new SdramStatemachine(
      ADDRESS_WIDTH = 24,
      AP_BIT        = 10,
      COLUMN_WIDTH  = 9,
      ROW_WIDTH     = 13
    )
    sdramCtrl.io.CLK_SYSTEM      := clk
    sdramCtrl.io.CLK_SDRAM       := sdramClkInt
    sdramCtrl.io.RESET_N         := resetN
    sdramCtrl.io.READ_EN         := sdramReadEnable
    sdramCtrl.io.WRITE_EN        := sdramWriteEnable
    sdramCtrl.io.REQUEST         := sdramRequest
    sdramCtrl.io.BYTE_ACCESS     := sdramWrite8
    sdramCtrl.io.WORD_ACCESS     := sdramWrite16
    sdramCtrl.io.LONGWORD_ACCESS := sdramWrite32
    sdramCtrl.io.REFRESH         := sdramRefresh
    sdramCtrl.io.ADDRESS_IN      := B"00" ## sdramAddr
    sdramCtrl.io.DATA_IN         := sdramDi

    sdramRequestComplete := sdramCtrl.io.COMPLETE
    sdramDo              := sdramCtrl.io.DATA_OUT
    sdramResetN          := sdramCtrl.io.reset_client_n

    // SDRAM pins
    io.sdram.addr  := sdramCtrl.io.SDRAM_ADDR
    io.sdram.ba(0) := sdramCtrl.io.SDRAM_BA0
    io.sdram.ba(1) := sdramCtrl.io.SDRAM_BA1
    io.sdram.cs_n  := sdramCtrl.io.SDRAM_CS_N
    io.sdram.ras_n := sdramCtrl.io.SDRAM_RAS_N
    io.sdram.cas_n := sdramCtrl.io.SDRAM_CAS_N
    io.sdram.we_n  := sdramCtrl.io.SDRAM_WE_N
    io.sdram.cke   := sdramCtrl.io.SDRAM_CKE
    io.sdram.dqml  := sdramCtrl.io.SDRAM_ldqm
    io.sdram.dqmh  := sdramCtrl.io.SDRAM_udqm
    // SDRAM DQ tristate at top level
    sdramCtrl.io.SDRAM_DQ_IN := io.sdramDq
    when(sdramCtrl.io.SDRAM_DQ_OE) {
      io.sdramDq := sdramCtrl.io.SDRAM_DQ_OUT
    }

    // =================================================================
    // PS/2 Keyboard -> Atari keyboard matrix (native SpinalHDL)
    // =================================================================
    val ps2Keyboard = new Ps2ToAtari800(ps2_enable = 1, direct_enable = 0)
    ps2Keyboard.io.PS2_CLK       := io.ps2.clk
    ps2Keyboard.io.PS2_DAT       := io.ps2.dat
    ps2Keyboard.io.INPUT         := B(0, 32 bits)
    ps2Keyboard.io.KEY_TYPE      := True   // ANSI keyboard
    ps2Keyboard.io.KEYBOARD_SCAN := keyboardScan
    keyboardResponse := ps2Keyboard.io.KEYBOARD_RESPONSE

    // =================================================================
    // Scandoubler: 15kHz -> 31kHz VGA (native SpinalHDL)
    // =================================================================
    val scandoubler = new Scandoubler(video_bits = 8)
    scandoubler.io.VGA                := True
    scandoubler.io.COMPOSITE_ON_HSYNC := False
    scandoubler.io.colour_enable      := colourEnable
    scandoubler.io.doubled_enable     := doubledEnable
    scandoubler.io.scanlines_on       := False
    scandoubler.io.pal                := pal
    scandoubler.io.colour_in          := videoBRaw
    scandoubler.io.vsync_in           := videoVs
    scandoubler.io.hsync_in           := videoHs
    scandoubler.io.csync_in           := videoCs

    sdR  := scandoubler.io.R
    sdG  := scandoubler.io.G
    sdB  := scandoubler.io.B
    sdVs := scandoubler.io.VSYNC
    sdHs := scandoubler.io.HSYNC

    // VGA output (top 4 bits of each channel)
    io.vga.r     := sdR(7 downto 4)
    io.vga.g     := sdG(7 downto 4)
    io.vga.b     := sdB(7 downto 4)
    io.vga.hsync := sdHs
    io.vga.vsync := sdVs

    // =================================================================
    // Audio: Sigma-delta PWM DAC
    // =================================================================
    val audioDacL = new AudioPwm
    audioDacL.io.audioIn := audioLPcm
    io.audioL := audioDacL.io.pwmOut

    val audioDacR = new AudioPwm
    audioDacR.io.audioIn := audioRPcm
    io.audioR := audioDacR.io.pwmOut

    // =================================================================
    // Pico Companion Bus Interface
    // =================================================================
    val picoIf = new PicoBus
    // Tristate bus: Pico data bus is bidirectional
    picoIf.io.picoDataRead := io.picoData
    when(picoIf.io.picoDataOe) {
      io.picoData := picoIf.io.picoDataWrite
    }
    picoIf.io.picoAddr  := io.picoAddr
    picoIf.io.picoWrN   := io.picoWrN
    picoIf.io.picoRdN   := io.picoRdN
    io.picoIrqN         := picoIf.io.picoIrqN

    // DMA to SDRAM
    dmaFetch      := picoIf.io.dmaFetch
    dmaAddr       := picoIf.io.dmaAddr
    dmaWriteData  := picoIf.io.dmaWriteData
    dmaWrite8     := picoIf.io.dmaWrite8
    dmaWrite16    := picoIf.io.dmaWrite16
    dmaWrite32    := picoIf.io.dmaWrite32
    dmaReadEnable := picoIf.io.dmaReadEn
    picoIf.io.dmaMemReady := dmaMemReady
    picoIf.io.dmaMemData  := dmaMemData

    // Cartridge emulation
    cartSelect := picoIf.io.cartSelect

    // Paddles
    paddle0 := picoIf.io.paddle0
    paddle1 := picoIf.io.paddle1
    paddle2 := picoIf.io.paddle2
    paddle3 := picoIf.io.paddle3

    // Physical cartridge bus
    cartAddrOut  := picoIf.io.cartAddrOut
    cartS4NInt   := picoIf.io.cartS4N
    cartS5NInt   := picoIf.io.cartS5N
    cartCctlNInt := picoIf.io.cartCctlN
    picoIf.io.cartDataIn := io.cart.data

    // Config
    pal       := picoIf.io.pal
    ramSelect := picoIf.io.ramSelect
    turbo     := picoIf.io.turbo
    picoColdReset := picoIf.io.coldReset

    // Status
    picoIf.io.pllLocked := pllLocked

    // Physical cartridge port outputs
    io.cart.addr   := cartAddrOut
    io.cart.s4_n   := cartS4NInt
    io.cart.s5_n   := cartS5NInt
    io.cart.cctl_n := cartCctlNInt
    io.cart.phi2   := clk  // simplified: use system clock

    // =================================================================
    // LEDs: status indicators
    // =================================================================
    io.led(0) := pllLocked
    io.led(1) := ~dmaFetch  // blinks during Pico DMA transfers
  }
}
