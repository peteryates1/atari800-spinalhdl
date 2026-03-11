package atari800

import spinal.core._

class Atari800CoreSimpleSdram(
  cycle_length : Int = 16,
  video_bits   : Int = 8,
  palette      : Int = 1,
  internal_rom : Int = 1,
  internal_ram : Int = 16384,
  low_memory   : Int = 0,
  stereo       : Int = 1,
  covox        : Int = 1
) extends Component {
  val io = new Bundle {
    // Video
    val VIDEO_VS             = out Bool()
    val VIDEO_HS             = out Bool()
    val VIDEO_CS             = out Bool()
    val VIDEO_B              = out Bits(video_bits bits)
    val VIDEO_G              = out Bits(video_bits bits)
    val VIDEO_R              = out Bits(video_bits bits)
    val VIDEO_BLANK          = out Bool()
    val VIDEO_BURST          = out Bool()
    val VIDEO_START_OF_FIELD = out Bool()
    val VIDEO_ODD_LINE       = out Bool()

    // Audio
    val AUDIO_L = out Bits(16 bits)
    val AUDIO_R = out Bits(16 bits)

    // Joysticks
    val JOY1_n  = in  Bits(5 bits)
    val JOY2_n  = in  Bits(5 bits)
    val JOY3_n  = in  Bits(5 bits)
    val JOY4_n  = in  Bits(5 bits)
    val PADDLE0 = in  SInt(8 bits)
    val PADDLE1 = in  SInt(8 bits)
    val PADDLE2 = in  SInt(8 bits)
    val PADDLE3 = in  SInt(8 bits)

    // Keyboard
    val KEYBOARD_RESPONSE = in  Bits(2 bits)
    val KEYBOARD_SCAN     = out Bits(6 bits)

    // SIO
    val SIO_COMMAND  = out Bool()
    val SIO_RXD      = in  Bool()
    val SIO_TXD      = out Bool()
    val SIO_CLOCKOUT = out Bool()

    // Console
    val CONSOL_OPTION = in  Bool()
    val CONSOL_SELECT = in  Bool()
    val CONSOL_START  = in  Bool()

    // SDRAM interface
    val SDRAM_REQUEST              = out Bool()
    val SDRAM_REQUEST_COMPLETE     = in  Bool()
    val SDRAM_READ_ENABLE          = out Bool()
    val SDRAM_WRITE_ENABLE         = out Bool()
    val SDRAM_ADDR                 = out Bits(23 bits)
    val SDRAM_DO                   = in  Bits(32 bits)
    val SDRAM_DI                   = out Bits(32 bits)
    val SDRAM_32BIT_WRITE_ENABLE   = out Bool()
    val SDRAM_16BIT_WRITE_ENABLE   = out Bool()
    val SDRAM_8BIT_WRITE_ENABLE    = out Bool()
    val SDRAM_REFRESH              = out Bool()

    // DMA
    val DMA_FETCH              = in  Bool()
    val DMA_READ_ENABLE        = in  Bool()
    val DMA_32BIT_WRITE_ENABLE = in  Bool()
    val DMA_16BIT_WRITE_ENABLE = in  Bool()
    val DMA_8BIT_WRITE_ENABLE  = in  Bool()
    val DMA_ADDR               = in  Bits(24 bits)
    val DMA_WRITE_DATA         = in  Bits(32 bits)
    val MEMORY_READY_DMA       = out Bool()
    val DMA_MEMORY_DATA        = out Bits(32 bits)

    // Config
    val RAM_SELECT                 = in  Bits(3 bits)
    val PAL                        = in  Bool()
    val HALT                       = in  Bool()
    val TURBO_VBLANK_ONLY          = in  Bool()
    val THROTTLE_COUNT_6502        = in  Bits(6 bits)
    val emulated_cartridge_select  = in  Bits(6 bits)
    val freezer_enable             = in  Bool()
    val freezer_activate           = in  Bool()
    val atari800mode               = in  Bool()
    val HIRES_ENA                  = in  Bool()

    // Debug
    val dbgMemReadyCpu  = out Bool()
    val dbgSharedEnable = out Bool()
    val dbgAnticRdy     = out Bool()
  }

  // Internal signals
  val CA1_IN        = Bool()
  val CB1_IN        = Bool()
  val CA2_OUT       = Bool()
  val CA2_DIR_OUT   = Bool()
  val CB2_OUT       = Bool()
  val CB2_DIR_OUT   = Bool()
  val CA2_IN        = Bool()
  val CB2_IN        = Bool()
  val PORTA_IN      = Bits(8 bits)
  val PORTA_OUT     = Bits(8 bits)
  val PORTA_DIR_OUT = Bits(8 bits)
  val PORTB_IN      = Bits(8 bits)
  val PORTB_OUT     = Bits(8 bits)
  val PORTB_DIR_OUT = Bits(8 bits)

  val GTIA_TRIG      = Bits(4 bits)
  val ANTIC_LIGHTPEN  = Bool()
  val PBI_WRITE_DATA  = Bits(32 bits)

  val RAM_ADDR             = Bits(19 bits)
  val RAM_DO               = Bits(16 bits)
  val RAM_REQUEST          = Bool()
  val RAM_REQUEST_COMPLETE = Bool()
  val RAM_WRITE_ENABLE     = Bool()

  val ROM_ADDR             = Bits(22 bits)
  val ROM_DO               = Bits(8 bits)
  val ROM_REQUEST          = Bool()
  val ROM_REQUEST_COMPLETE = Bool()
  val ROM_WRITE_ENABLE     = Bool()

  val ROM_IN_RAM = Bool()
  val POT_RESET  = Bool()
  val POT_IN     = Bits(8 bits)

  // PIA mapping
  CA1_IN := True
  CB1_IN := True
  CA2_IN := Mux(CA2_DIR_OUT, CA2_OUT, True)
  CB2_IN := Mux(CB2_DIR_OUT, CB2_OUT, True)
  io.SIO_COMMAND := CB2_OUT

  PORTA_IN := ((io.JOY2_n(3) ## io.JOY2_n(2) ## io.JOY2_n(1) ## io.JOY2_n(0) ##
                 io.JOY1_n(3) ## io.JOY1_n(2) ## io.JOY1_n(1) ## io.JOY1_n(0)) & ~PORTA_DIR_OUT) |
               (PORTA_DIR_OUT & PORTA_OUT)

  PORTB_IN := ((io.JOY4_n(3) ## io.JOY4_n(2) ## io.JOY4_n(1) ## io.JOY4_n(0) ##
                 io.JOY3_n(3) ## io.JOY3_n(2) ## io.JOY3_n(1) ## io.JOY3_n(0)) & ~PORTB_DIR_OUT) |
               (PORTB_DIR_OUT & PORTB_OUT &
                (io.JOY4_n(3) ## io.JOY4_n(2) ## io.JOY4_n(1) ## io.JOY4_n(0) ##
                 io.JOY3_n(3) ## io.JOY3_n(2) ## io.JOY3_n(1) ## io.JOY3_n(0)))

  ANTIC_LIGHTPEN := io.JOY2_n(4) & io.JOY1_n(4)
  GTIA_TRIG := io.JOY4_n(4) ## io.JOY3_n(4) ## io.JOY2_n(4) ## io.JOY1_n(4)

  io.SDRAM_DI := PBI_WRITE_DATA

  // Paddles
  val pot0 = new PotFromSigned(cycleLength = cycle_length, reverse = 1)
  pot0.io.enabled   := True
  pot0.io.potReset  := POT_RESET
  pot0.io.pos       := io.PADDLE0
  pot0.io.forceLow  := False
  pot0.io.forceHigh := False
  POT_IN(0)         := pot0.io.potHigh

  val pot1 = new PotFromSigned(cycleLength = cycle_length, reverse = 1)
  pot1.io.enabled   := True
  pot1.io.potReset  := POT_RESET
  pot1.io.pos       := io.PADDLE1
  pot1.io.forceLow  := False
  pot1.io.forceHigh := False
  POT_IN(1)         := pot1.io.potHigh

  val pot2 = new PotFromSigned(cycleLength = cycle_length, reverse = 1)
  pot2.io.enabled   := True
  pot2.io.potReset  := POT_RESET
  pot2.io.pos       := io.PADDLE2
  pot2.io.forceLow  := False
  pot2.io.forceHigh := False
  POT_IN(2)         := pot2.io.potHigh

  val pot3 = new PotFromSigned(cycleLength = cycle_length, reverse = 1)
  pot3.io.enabled   := True
  pot3.io.potReset  := POT_RESET
  pot3.io.pos       := io.PADDLE3
  pot3.io.forceLow  := False
  pot3.io.forceHigh := False
  POT_IN(3)         := pot3.io.potHigh

  POT_IN(7 downto 4) := B"0000"

  // Internal ROM/RAM
  val internalromram1 = new InternalRomRam(internalRom = internal_rom, internalRam = internal_ram)
  internalromram1.io.clock                := ClockDomain.current.readClockWire
  internalromram1.io.resetN               := ClockDomain.current.readResetWire
  internalromram1.io.romAddr              := ROM_ADDR
  internalromram1.io.romWrEnable          := ROM_WRITE_ENABLE
  internalromram1.io.romDataIn            := PBI_WRITE_DATA(7 downto 0)
  ROM_REQUEST_COMPLETE                    := internalromram1.io.romRequestComplete
  internalromram1.io.romRequest           := ROM_REQUEST
  ROM_DO                                  := internalromram1.io.romData

  internalromram1.io.ramAddr              := RAM_ADDR
  internalromram1.io.ramWrEnable          := RAM_WRITE_ENABLE
  internalromram1.io.ramDataIn            := PBI_WRITE_DATA(7 downto 0)
  RAM_REQUEST_COMPLETE                    := internalromram1.io.ramRequestComplete
  internalromram1.io.ramRequest           := RAM_REQUEST
  RAM_DO(7 downto 0)                      := internalromram1.io.ramData
  RAM_DO(15 downto 8)                     := B(0, 8 bits)

  ROM_IN_RAM := Bool(internal_rom == 0)

  // Atari 800 core
  val atari800xl = new Atari800Core(
    cycle_length = cycle_length,
    video_bits   = video_bits,
    palette      = palette,
    low_memory   = low_memory,
    stereo       = stereo,
    covox        = covox,
    internal_ram = internal_ram
  )

  atari800xl.io.VIDEO_VS             <> io.VIDEO_VS
  atari800xl.io.VIDEO_HS             <> io.VIDEO_HS
  atari800xl.io.VIDEO_CS             <> io.VIDEO_CS
  atari800xl.io.VIDEO_B              <> io.VIDEO_B
  atari800xl.io.VIDEO_G              <> io.VIDEO_G
  atari800xl.io.VIDEO_R              <> io.VIDEO_R
  atari800xl.io.VIDEO_BLANK          <> io.VIDEO_BLANK
  atari800xl.io.VIDEO_BURST          <> io.VIDEO_BURST
  atari800xl.io.VIDEO_START_OF_FIELD <> io.VIDEO_START_OF_FIELD
  atari800xl.io.VIDEO_ODD_LINE       <> io.VIDEO_ODD_LINE

  atari800xl.io.AUDIO_L              <> io.AUDIO_L
  atari800xl.io.AUDIO_R              <> io.AUDIO_R
  atari800xl.io.SIO_AUDIO            := B"00000000"

  atari800xl.io.CA1_IN       := CA1_IN
  atari800xl.io.CB1_IN       := CB1_IN
  atari800xl.io.CA2_IN       := CA2_IN
  CA2_OUT                    := atari800xl.io.CA2_OUT
  CA2_DIR_OUT                := atari800xl.io.CA2_DIR_OUT
  atari800xl.io.CB2_IN       := CB2_IN
  CB2_OUT                    := atari800xl.io.CB2_OUT
  CB2_DIR_OUT                := atari800xl.io.CB2_DIR_OUT
  atari800xl.io.PORTA_IN     := PORTA_IN
  PORTA_DIR_OUT              := atari800xl.io.PORTA_DIR_OUT
  PORTA_OUT                  := atari800xl.io.PORTA_OUT
  atari800xl.io.PORTB_IN     := PORTB_IN
  PORTB_DIR_OUT              := atari800xl.io.PORTB_DIR_OUT
  PORTB_OUT                  := atari800xl.io.PORTB_OUT

  atari800xl.io.KEYBOARD_RESPONSE := io.KEYBOARD_RESPONSE
  io.KEYBOARD_SCAN                := atari800xl.io.KEYBOARD_SCAN

  atari800xl.io.POT_IN  := POT_IN
  POT_RESET              := atari800xl.io.POT_RESET

  // PBI - expose what's needed for SDRAM
  io.DMA_MEMORY_DATA                     := atari800xl.io.PBI_SNOOP_DATA
  PBI_WRITE_DATA                         := atari800xl.io.PBI_WRITE_DATA
  io.SDRAM_8BIT_WRITE_ENABLE             := atari800xl.io.PBI_WIDTH_8bit_ACCESS
  io.SDRAM_16BIT_WRITE_ENABLE            := atari800xl.io.PBI_WIDTH_16bit_ACCESS
  io.SDRAM_32BIT_WRITE_ENABLE            := atari800xl.io.PBI_WIDTH_32bit_ACCESS

  atari800xl.io.PBI_ROM_DO          := B"11111111"
  atari800xl.io.PBI_TAKEOVER        := False
  atari800xl.io.PBI_RELEASE         := False
  atari800xl.io.PBI_REQUEST_COMPLETE := False
  atari800xl.io.PBI_DISABLE         := True

  atari800xl.io.CART_RD5  := False
  atari800xl.io.PBI_MPD_N := True
  atari800xl.io.PBI_IRQ_N := True

  atari800xl.io.SIO_RXD   := io.SIO_RXD
  io.SIO_TXD               := atari800xl.io.SIO_TXD
  io.SIO_CLOCKOUT           := atari800xl.io.SIO_CLOCKOUT

  atari800xl.io.CONSOL_OPTION := io.CONSOL_OPTION
  atari800xl.io.CONSOL_SELECT := io.CONSOL_SELECT
  atari800xl.io.CONSOL_START  := io.CONSOL_START
  atari800xl.io.GTIA_TRIG     := GTIA_TRIG

  atari800xl.io.ANTIC_LIGHTPEN := ANTIC_LIGHTPEN
  io.SDRAM_REFRESH              := atari800xl.io.ANTIC_REFRESH

  atari800xl.io.SDRAM_REQUEST_COMPLETE := io.SDRAM_REQUEST_COMPLETE
  io.SDRAM_REQUEST                     := atari800xl.io.SDRAM_REQUEST
  io.SDRAM_READ_ENABLE                 := atari800xl.io.SDRAM_READ_ENABLE
  io.SDRAM_WRITE_ENABLE                := atari800xl.io.SDRAM_WRITE_ENABLE
  io.SDRAM_ADDR                        := atari800xl.io.SDRAM_ADDR
  atari800xl.io.SDRAM_DO               := io.SDRAM_DO

  RAM_ADDR                           := atari800xl.io.RAM_ADDR
  atari800xl.io.RAM_DO               := RAM_DO
  RAM_REQUEST                        := atari800xl.io.RAM_REQUEST
  atari800xl.io.RAM_REQUEST_COMPLETE := RAM_REQUEST_COMPLETE
  RAM_WRITE_ENABLE                   := atari800xl.io.RAM_WRITE_ENABLE

  ROM_ADDR                           := atari800xl.io.ROM_ADDR
  atari800xl.io.ROM_DO               := ROM_DO
  ROM_REQUEST                        := atari800xl.io.ROM_REQUEST
  atari800xl.io.ROM_REQUEST_COMPLETE := ROM_REQUEST_COMPLETE
  ROM_WRITE_ENABLE                   := atari800xl.io.ROM_WRITE_ENABLE

  atari800xl.io.DMA_FETCH              := io.DMA_FETCH
  atari800xl.io.DMA_READ_ENABLE        := io.DMA_READ_ENABLE
  atari800xl.io.DMA_32BIT_WRITE_ENABLE := io.DMA_32BIT_WRITE_ENABLE
  atari800xl.io.DMA_16BIT_WRITE_ENABLE := io.DMA_16BIT_WRITE_ENABLE
  atari800xl.io.DMA_8BIT_WRITE_ENABLE  := io.DMA_8BIT_WRITE_ENABLE
  atari800xl.io.DMA_ADDR               := io.DMA_ADDR
  atari800xl.io.DMA_WRITE_DATA         := io.DMA_WRITE_DATA
  io.MEMORY_READY_DMA                  := atari800xl.io.MEMORY_READY_DMA

  atari800xl.io.RAM_SELECT              := io.RAM_SELECT
  atari800xl.io.CART_EMULATION_SELECT   := io.emulated_cartridge_select
  atari800xl.io.PAL                     := io.PAL
  atari800xl.io.ROM_IN_RAM              := ROM_IN_RAM
  atari800xl.io.THROTTLE_COUNT_6502     := io.THROTTLE_COUNT_6502
  atari800xl.io.HALT                    := io.HALT
  atari800xl.io.TURBO_VBLANK_ONLY       := io.TURBO_VBLANK_ONLY
  atari800xl.io.freezer_enable          := io.freezer_enable
  atari800xl.io.freezer_activate        := io.freezer_activate
  atari800xl.io.ATARI800MODE            := io.atari800mode
  atari800xl.io.HIRES_ENA               := io.HIRES_ENA
  atari800xl.io.RESET_N                 := ClockDomain.current.readResetWire

  // Unused inputs with defaults
  atari800xl.io.SIO_CLOCKIN_IN          := True
  atari800xl.io.ANTIC_RNMI_N            := True
  atari800xl.io.EXT_NMI_N               := True
  atari800xl.io.freezer_debug_addr       := B(0, 16 bits)
  atari800xl.io.freezer_debug_data       := B(0, 8 bits)
  atari800xl.io.freezer_debug_read       := False
  atari800xl.io.freezer_debug_write      := False
  atari800xl.io.freezer_debug_data_match := False

  // Debug
  io.dbgMemReadyCpu  := atari800xl.io.memory_ready_cpu_out
  io.dbgSharedEnable := atari800xl.io.shared_enable_out
  io.dbgAnticRdy     := atari800xl.io.rdy_out
}

