package atari800

import spinal.core._

class Sid8580Stub extends Component {
  val io = new Bundle {
    val reset       = in  Bool()
    val ce1m        = in  Bool()
    val we          = in  Bool()
    val addr        = in  Bits(5 bits)
    val dataIn      = in  Bits(8 bits)
    val dataOut     = out Bits(8 bits)
    val potX        = in  Bits(8 bits)
    val potY        = in  Bits(8 bits)
    val extfilterEn = in  Bool()
    val audioData   = out Bits(18 bits)
  }
  io.dataOut   := B(0, 8 bits)
  io.audioData := B(0, 18 bits)
}

class Atari800Core(
  cycle_length   : Int = 16,
  video_bits     : Int = 8,
  palette        : Int = 0,
  low_memory     : Int = 0,
  stereo         : Int = 1,
  sid            : Int = 0,
  covox          : Int = 1,
  internal_ram   : Int = 0,
  freezer_debug  : Int = 0
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
    val AUDIO_L   = out Bits(16 bits)
    val AUDIO_R   = out Bits(16 bits)
    val SIO_AUDIO = in  Bits(8 bits)

    // PIA
    val CA1_IN         = in  Bool()
    val CB1_IN         = in  Bool()
    val CA2_IN         = in  Bool()
    val CA2_OUT        = out Bool()
    val CA2_DIR_OUT    = out Bool()
    val CB2_IN         = in  Bool()
    val CB2_OUT        = out Bool()
    val CB2_DIR_OUT    = out Bool()
    val PORTA_IN       = in  Bits(8 bits)
    val PORTA_OUT      = out Bits(8 bits)
    val PORTA_DIR_OUT  = out Bits(8 bits)
    val PORTB_IN       = in  Bits(8 bits)
    val PORTB_OUT      = out Bits(8 bits)
    val PORTB_DIR_OUT  = out Bits(8 bits)

    // Keyboard
    val KEYBOARD_RESPONSE = in  Bits(2 bits)
    val KEYBOARD_SCAN     = out Bits(6 bits)

    // Pots
    val POT_IN    = in  Bits(8 bits)
    val POT_RESET = out Bool()

    // PBI
    val ENABLE_179_EARLY       = out Bool()
    val PBI_ADDR               = out Bits(16 bits)
    val PBI_WRITE_ENABLE       = out Bool()
    val PBI_SNOOP_DATA         = out Bits(32 bits)
    val PBI_SNOOP_READY        = out Bool()
    val PBI_WRITE_DATA         = out Bits(32 bits)
    val PBI_WIDTH_8bit_ACCESS  = out Bool()
    val PBI_WIDTH_16bit_ACCESS = out Bool()
    val PBI_WIDTH_32bit_ACCESS = out Bool()

    val PBI_ROM_DO           = in  Bits(8 bits)
    val PBI_REQUEST          = out Bool()
    val PBI_TAKEOVER         = in  Bool()
    val PBI_RELEASE          = in  Bool()
    val PBI_REQUEST_COMPLETE = in  Bool()
    val PBI_DISABLE          = in  Bool()

    val CART_RD5   = in  Bool()
    val PBI_MPD_N  = in  Bool()
    val PBI_IRQ_N  = in  Bool()

    // SIO
    val SIO_RXD         = in  Bool()
    val SIO_TXD         = out Bool()
    val SIO_CLOCKIN_IN  = in  Bool()
    val SIO_CLOCKIN_OUT = out Bool()
    val SIO_CLOCKIN_OE  = out Bool()
    val SIO_CLOCKOUT    = out Bool()

    // GTIA consol
    val CONSOL_OPTION = in  Bool()
    val CONSOL_SELECT = in  Bool()
    val CONSOL_START  = in  Bool()
    val GTIA_TRIG     = in  Bits(4 bits)

    // ANTIC
    val ANTIC_LIGHTPEN = in  Bool()
    val ANTIC_REFRESH  = out Bool()
    val ANTIC_TURBO    = out Bool()
    val ANTIC_RNMI_N   = in  Bool()

    val EXT_NMI_N = in  Bool()

    // SDRAM interface
    val SDRAM_REQUEST          = out Bool()
    val SDRAM_REQUEST_COMPLETE = in  Bool()
    val SDRAM_READ_ENABLE      = out Bool()
    val SDRAM_WRITE_ENABLE     = out Bool()
    val SDRAM_ADDR             = out Bits(23 bits)
    val SDRAM_DO               = in  Bits(32 bits)

    // RAM
    val RAM_ADDR             = out Bits(19 bits)
    val RAM_DO               = in  Bits(16 bits)
    val RAM_REQUEST          = out Bool()
    val RAM_REQUEST_COMPLETE = in  Bool()
    val RAM_WRITE_ENABLE     = out Bool()

    // ROM
    val ROM_ADDR             = out Bits(22 bits)
    val ROM_DO               = in  Bits(8 bits)
    val ROM_REQUEST          = out Bool()
    val ROM_REQUEST_COMPLETE = in  Bool()
    val ROM_WRITE_ENABLE     = out Bool()

    // DMA
    val DMA_FETCH              = in  Bool()
    val DMA_READ_ENABLE        = in  Bool()
    val DMA_32BIT_WRITE_ENABLE = in  Bool()
    val DMA_16BIT_WRITE_ENABLE = in  Bool()
    val DMA_8BIT_WRITE_ENABLE  = in  Bool()
    val DMA_ADDR               = in  Bits(24 bits)
    val DMA_WRITE_DATA         = in  Bits(32 bits)
    val MEMORY_READY_DMA       = out Bool()

    // Config
    val RAM_SELECT              = in  Bits(3 bits)
    val CART_EMULATION_SELECT   = in  Bits(6 bits)
    val PAL                     = in  Bool()
    val ROM_IN_RAM              = in  Bool()
    val THROTTLE_COUNT_6502     = in  Bits(6 bits)
    val HALT                    = in  Bool()
    val TURBO_VBLANK_ONLY       = in  Bool()
    val freezer_enable          = in  Bool()
    val freezer_activate        = in  Bool()
    val ATARI800MODE            = in  Bool()
    val HIRES_ENA               = in  Bool()
    val RESET_N                 = in  Bool()

    // Freezer debug
    val freezer_debug_addr       = in  Bits(16 bits)
    val freezer_debug_data       = in  Bits(8 bits)
    val freezer_debug_read       = in  Bool()
    val freezer_debug_write      = in  Bool()
    val freezer_debug_data_match = in  Bool()

    // Debug outputs
    val freezer_state_out       = out Bits(3 bits)
    val state_reg_out           = out Bits(2 bits)
    val memory_ready_antic_out  = out Bool()
    val memory_ready_cpu_out    = out Bool()
    val shared_enable_out       = out Bool()
    val nmi_n_out               = out Bool()
    val irq_n_out               = out Bool()
    val rdy_out                 = out Bool()
    val AN_out                  = out Bits(3 bits)
  }

  // ---- Internal signals ----
  val ANTIC_ADDR                      = Bits(16 bits)
  val ANTIC_AN                        = Bits(3 bits)
  val ANTIC_COLOUR_CLOCK_OUT          = Bool()
  val ANTIC_DO                        = Bits(8 bits)
  val CACHE_ANTIC_DO                  = Bits(8 bits)
  val ANTIC_FETCH                     = Bool()
  val ANTIC_HIGHRES_COLOUR_CLOCK_OUT  = Bool()
  val ANTIC_ORIGINAL_COLOUR_CLOCK_OUT = Bool()
  val ANTIC_RDY                       = Bool()
  val ANTIC_WRITE_ENABLE              = Bool()
  val ANTIC_REFRESH_CYCLE             = Bool()
  val ANTIC_VBLANK                    = Bool()

  val GTIA_SOUND       = Bool()
  val CONSOL_OUT       = Bits(4 bits)
  val CONSOL_IN        = Bits(4 bits)
  val GTIA_TRIG_MERGED = Bits(4 bits)
  val GTIA_DO          = Bits(8 bits)
  val CACHE_GTIA_DO    = Bits(8 bits)
  val GTIA_WRITE_ENABLE = Bool()
  val COLOUR           = Bits(8 bits)

  val VIDEO_R_WIDE = Bits(8 bits)
  val VIDEO_G_WIDE = Bits(8 bits)
  val VIDEO_B_WIDE = Bits(8 bits)

  val CPU_6502_RESET  = Bool()
  val CPU_ADDR        = Bits(16 bits)
  val CPU_DO          = Bits(8 bits)
  val CPU_FETCH       = Bool()
  val IRQ_n           = Bool()
  val ANTIC_NMI_n     = Bool()
  val NMI_n           = Bool()
  val R_W_N           = Bool()

  val CPU_SHARED_ENABLE      = Bool()
  val ENABLE_179_MEMWAIT     = Bool()
  val ANTIC_ENABLE_179       = Bool()
  val THROTTLE_COUNT_6502_ADJ = Bits(6 bits)

  val POKEY_IRQ             = Bool()
  val POKEY1_DO             = Bits(8 bits)
  val CACHE_POKEY1_DO       = Bits(8 bits)
  val POKEY1_WRITE_ENABLE   = Bool()
  val POKEY1_CHANNEL0       = Bits(4 bits)
  val POKEY1_CHANNEL1       = Bits(4 bits)
  val POKEY1_CHANNEL2       = Bits(4 bits)
  val POKEY1_CHANNEL3       = Bits(4 bits)

  val POKEY2_DO             = Bits(8 bits)
  val CACHE_POKEY2_DO       = Bits(8 bits)
  val POKEY2_WRITE_ENABLE   = Bool()
  val POKEY2_CHANNEL0       = Bits(4 bits)
  val POKEY2_CHANNEL1       = Bits(4 bits)
  val POKEY2_CHANNEL2       = Bits(4 bits)
  val POKEY2_CHANNEL3       = Bits(4 bits)

  val POKEY_DO              = Bits(8 bits)
  val CACHE_POKEY_DO        = Bits(8 bits)
  val POKEY_WRITE_ENABLE    = Bool()

  val covox_write_enable = Bool()
  val covox_channel0     = Bits(8 bits)
  val covox_channel1     = Bits(8 bits)
  val covox_channel2     = Bits(8 bits)
  val covox_channel3     = Bits(8 bits)

  val MEMORY_DATA       = Bits(32 bits)
  val MEMORY_READY_ANTIC = Bool()
  val MEMORY_READY_CPU  = Bool()
  val WRITE_DATA        = Bits(32 bits)
  val WIDTH_16BIT_ACCESS = Bool()
  val WIDTH_32BIT_ACCESS = Bool()
  val WIDTH_8BIT_ACCESS  = Bool()

  val PIA_DO           = Bits(8 bits)
  val PIA_IRQA         = Bool()
  val PIA_IRQB         = Bool()
  val PIA_READ_ENABLE  = Bool()
  val PIA_WRITE_ENABLE = Bool()
  val PORTB_OUT_INT    = Bits(8 bits)
  val PORTB_OPTIONS    = Bits(8 bits)

  val PBI_ADDR_INT = Bits(16 bits)
  val cart_trig3_out = Bool()

  val freezer_trigger_activate = Bool()
  val freezer_activate_combined = Bool()
  val freezer_state            = Bits(3 bits)
  val freezer_trigger_nmi_n    = Bool()

  val enable_sid       = Bool()
  val SID1_DO          = Bits(8 bits)
  val SID2_DO          = Bits(8 bits)
  val SID1_WRITE_ENABLE = Bool()
  val SID2_WRITE_ENABLE = Bool()
  val SID1_AUDIO       = Bits(8 bits)
  val SID2_AUDIO       = Bits(8 bits)

  // ---- PBI output wiring ----
  io.PBI_WIDTH_8bit_ACCESS  := WIDTH_8BIT_ACCESS
  io.PBI_WIDTH_16bit_ACCESS := WIDTH_16BIT_ACCESS
  io.PBI_WIDTH_32bit_ACCESS := WIDTH_32BIT_ACCESS
  io.PBI_WRITE_DATA         := WRITE_DATA
  io.PBI_SNOOP_DATA         := MEMORY_DATA
  io.PBI_SNOOP_READY        := MEMORY_READY_CPU | MEMORY_READY_ANTIC

  // VBI only turbo
  THROTTLE_COUNT_6502_ADJ := io.THROTTLE_COUNT_6502
  when(~ANTIC_VBLANK & io.TURBO_VBLANK_ONLY) {
    THROTTLE_COUNT_6502_ADJ := B"000001"
  }

  // ---- SharedEnable (native) ----
  val enables = new SharedEnable(cycle_length)
  enables.io.memoryReadyCpu    := MEMORY_READY_CPU
  enables.io.memoryReadyAntic  := MEMORY_READY_ANTIC
  enables.io.anticRefresh      := ANTIC_REFRESH_CYCLE
  enables.io.pause6502         := io.HALT
  enables.io.throttleCount6502 := THROTTLE_COUNT_6502_ADJ
  ANTIC_ENABLE_179   := enables.io.anticEnable179
  ENABLE_179_MEMWAIT := enables.io.oldcpuEnable
  CPU_SHARED_ENABLE  := enables.io.cpuEnableOut

  // ---- CPU (native) ----
  CPU_6502_RESET := ~io.RESET_N
  val cpu6502 = new Cpu
  cpu6502.io.ENABLE       := io.RESET_N
  cpu6502.io.IRQ_n        := IRQ_n
  cpu6502.io.NMI_n        := NMI_n
  cpu6502.io.MEMORY_READY := MEMORY_READY_CPU
  cpu6502.io.THROTTLE     := CPU_SHARED_ENABLE
  cpu6502.io.RDY          := ANTIC_RDY
  cpu6502.io.DI           := MEMORY_DATA(7 downto 0)
  R_W_N     := cpu6502.io.R_W_n
  CPU_FETCH := cpu6502.io.CPU_FETCH
  CPU_ADDR  := cpu6502.io.A
  CPU_DO    := cpu6502.io.DO

  // ---- ANTIC (native) ----
  val antic1 = new Antic(cycle_length)
  antic1.io.WR_EN              := ANTIC_WRITE_ENABLE
  antic1.io.RNMI_N             := io.ANTIC_RNMI_N
  antic1.io.MEMORY_READY_ANTIC := MEMORY_READY_ANTIC
  antic1.io.MEMORY_READY_CPU   := MEMORY_READY_CPU
  antic1.io.ANTIC_ENABLE_179   := ANTIC_ENABLE_179
  antic1.io.PAL                := io.PAL
  antic1.io.lightpen           := io.ANTIC_LIGHTPEN
  antic1.io.ADDR               := PBI_ADDR_INT(3 downto 0)
  antic1.io.CPU_DATA_IN        := WRITE_DATA(7 downto 0)
  antic1.io.MEMORY_DATA_IN     := MEMORY_DATA(7 downto 0)
  antic1.io.hires_ena          := io.HIRES_ENA
  ANTIC_NMI_n                       := antic1.io.NMI_N_OUT
  ANTIC_RDY                         := antic1.io.ANTIC_READY
  ANTIC_ORIGINAL_COLOUR_CLOCK_OUT   := antic1.io.COLOUR_CLOCK_ORIGINAL_OUT
  ANTIC_COLOUR_CLOCK_OUT            := antic1.io.COLOUR_CLOCK_OUT
  ANTIC_HIGHRES_COLOUR_CLOCK_OUT    := antic1.io.HIGHRES_COLOUR_CLOCK_OUT
  ANTIC_FETCH                       := antic1.io.dma_fetch_out
  ANTIC_REFRESH_CYCLE               := antic1.io.refresh_out
  io.ANTIC_TURBO                    := antic1.io.turbo_out
  ANTIC_VBLANK                      := antic1.io.vblank_out
  ANTIC_AN                          := antic1.io.AN
  ANTIC_DO                          := antic1.io.DATA_OUT
  ANTIC_ADDR                        := antic1.io.dma_address_out

  NMI_n := ANTIC_NMI_n & io.EXT_NMI_N & freezer_trigger_nmi_n

  // ---- POKEY mixer (native) ----
  val pokey_mixer_both = new PokeyMixerMux
  pokey_mixer_both.io.enable179       := ANTIC_ENABLE_179
  pokey_mixer_both.io.gtiaSound       := GTIA_SOUND
  pokey_mixer_both.io.sioAudio        := io.SIO_AUDIO
  pokey_mixer_both.io.channelL0       := POKEY1_CHANNEL0
  pokey_mixer_both.io.channelL1       := POKEY1_CHANNEL1
  pokey_mixer_both.io.channelL2       := POKEY1_CHANNEL2
  pokey_mixer_both.io.channelL3       := POKEY1_CHANNEL3
  pokey_mixer_both.io.covoxChannelL0  := covox_channel0
  pokey_mixer_both.io.covoxChannelL1  := covox_channel1
  pokey_mixer_both.io.sidChannelL0    := SID1_AUDIO
  pokey_mixer_both.io.channelR0       := POKEY2_CHANNEL0
  pokey_mixer_both.io.channelR1       := POKEY2_CHANNEL1
  pokey_mixer_both.io.channelR2       := POKEY2_CHANNEL2
  pokey_mixer_both.io.channelR3       := POKEY2_CHANNEL3
  pokey_mixer_both.io.covoxChannelR0  := covox_channel2
  pokey_mixer_both.io.covoxChannelR1  := covox_channel3
  pokey_mixer_both.io.sidChannelR0    := SID2_AUDIO
  io.AUDIO_L := pokey_mixer_both.io.volumeOutL
  io.AUDIO_R := pokey_mixer_both.io.volumeOutR

  // ---- Stereo POKEY (native) ----
  if (stereo == 1) {
    val pokey2 = new Pokey
    pokey2.io.enable179          := ANTIC_ENABLE_179
    pokey2.io.wrEn               := POKEY2_WRITE_ENABLE
    pokey2.io.addr               := PBI_ADDR_INT(3 downto 0)
    pokey2.io.dataIn             := WRITE_DATA(7 downto 0)
    POKEY2_CHANNEL0              := pokey2.io.channel0Out
    POKEY2_CHANNEL1              := pokey2.io.channel1Out
    POKEY2_CHANNEL2              := pokey2.io.channel2Out
    POKEY2_CHANNEL3              := pokey2.io.channel3Out
    POKEY2_DO                    := pokey2.io.dataOut
    pokey2.io.sioIn1             := True
    pokey2.io.sioIn2             := True
    pokey2.io.sioIn3             := True
    pokey2.io.keyboardResponse   := B"00"
    pokey2.io.potIn              := B"00000000"
    pokey2.io.keyboardScanEnable := True
    pokey2.io.sioClockinIn       := True
  } else {
    POKEY2_CHANNEL0 := POKEY1_CHANNEL0
    POKEY2_CHANNEL1 := POKEY1_CHANNEL1
    POKEY2_CHANNEL2 := POKEY1_CHANNEL2
    POKEY2_CHANNEL3 := POKEY1_CHANNEL3
  }

  // ---- SID (stub - no native conversion available) ----
  if (sid == 1) {
    val sidenable = new EnableDivider(58 / (cycle_length / 32))
    sidenable.io.enableIn := True
    enable_sid := sidenable.io.enableOut

    val sid1 = new Sid8580Stub
    sid1.io.reset         := ~io.RESET_N
    sid1.io.ce1m          := enable_sid
    sid1.io.we            := SID1_WRITE_ENABLE
    sid1.io.addr          := PBI_ADDR_INT(4 downto 0)
    sid1.io.dataIn        := WRITE_DATA(7 downto 0)
    SID1_DO               := sid1.io.dataOut
    sid1.io.potX          := B(0, 8 bits)
    sid1.io.potY          := B(0, 8 bits)
    sid1.io.extfilterEn   := False
    SID1_AUDIO            := sid1.io.audioData(17 downto 10)

    val sid2 = new Sid8580Stub
    sid2.io.reset         := ~io.RESET_N
    sid2.io.ce1m          := enable_sid
    sid2.io.we            := SID2_WRITE_ENABLE
    sid2.io.addr          := PBI_ADDR_INT(4 downto 0)
    sid2.io.dataIn        := WRITE_DATA(7 downto 0)
    SID2_DO               := sid2.io.dataOut
    sid2.io.potX          := B(0, 8 bits)
    sid2.io.potY          := B(0, 8 bits)
    sid2.io.extfilterEn   := False
    SID2_AUDIO            := sid2.io.audioData(17 downto 10)

    POKEY1_WRITE_ENABLE := False
    POKEY2_WRITE_ENABLE := False
    SID1_WRITE_ENABLE   := False
    SID2_WRITE_ENABLE   := False
    POKEY_DO            := B(0, 8 bits)
    CACHE_POKEY_DO      := B(0, 8 bits)

    switch(PBI_ADDR_INT(6 downto 4)) {
      is(B"000", B"010") {
        POKEY1_WRITE_ENABLE := POKEY_WRITE_ENABLE
        POKEY_DO            := POKEY1_DO
        CACHE_POKEY_DO      := CACHE_POKEY1_DO
      }
      is(B"001", B"011") {
        POKEY2_WRITE_ENABLE := POKEY_WRITE_ENABLE
        POKEY_DO            := POKEY2_DO
        CACHE_POKEY_DO      := CACHE_POKEY2_DO
      }
      is(B"100", B"101") {
        SID1_WRITE_ENABLE := POKEY_WRITE_ENABLE
        POKEY_DO          := SID1_DO
      }
      is(B"110", B"111") {
        SID2_WRITE_ENABLE := POKEY_WRITE_ENABLE
        POKEY_DO          := SID2_DO
      }
    }
  } else {
    SID1_AUDIO := B(0, 8 bits)
    SID2_AUDIO := B(0, 8 bits)
    enable_sid := False
    SID1_DO := B(0, 8 bits)
    SID2_DO := B(0, 8 bits)
    SID1_WRITE_ENABLE := False
    SID2_WRITE_ENABLE := False

    POKEY1_WRITE_ENABLE := False
    POKEY2_WRITE_ENABLE := False
    POKEY_DO            := B(0, 8 bits)
    CACHE_POKEY_DO      := B(0, 8 bits)

    if (stereo == 1) {
      when(~PBI_ADDR_INT(4)) {
        POKEY1_WRITE_ENABLE := POKEY_WRITE_ENABLE
        POKEY_DO            := POKEY1_DO
        CACHE_POKEY_DO      := CACHE_POKEY1_DO
      } otherwise {
        POKEY2_WRITE_ENABLE := POKEY_WRITE_ENABLE
        POKEY_DO            := POKEY2_DO
        CACHE_POKEY_DO      := CACHE_POKEY2_DO
      }
    } else {
      POKEY_DO            := POKEY1_DO
      POKEY1_WRITE_ENABLE := POKEY_WRITE_ENABLE
      POKEY2_WRITE_ENABLE := False
    }
  }

  // ---- PIA (native) ----
  val pia1 = new Pia
  pia1.io.en          := PIA_READ_ENABLE
  pia1.io.wrEn        := PIA_WRITE_ENABLE
  pia1.io.enableOrig  := ENABLE_179_MEMWAIT
  pia1.io.ca1         := io.CA1_IN
  pia1.io.cb1         := io.CB1_IN
  pia1.io.ca2In       := io.CA2_IN
  io.CA2_OUT          := pia1.io.ca2Out
  io.CA2_DIR_OUT      := pia1.io.ca2DirOut
  pia1.io.cb2In       := io.CB2_IN
  io.CB2_OUT          := pia1.io.cb2Out
  io.CB2_DIR_OUT      := pia1.io.cb2DirOut
  pia1.io.addr        := PBI_ADDR_INT(1 downto 0)
  pia1.io.cpuDataIn   := WRITE_DATA(7 downto 0)
  PIA_IRQA            := pia1.io.irqaN
  PIA_IRQB            := pia1.io.irqbN
  PIA_DO              := pia1.io.dataOut
  pia1.io.portaIn     := io.PORTA_IN
  io.PORTA_DIR_OUT    := pia1.io.portaDirOut
  io.PORTA_OUT        := pia1.io.portaOut
  pia1.io.portbIn     := io.PORTB_IN
  io.PORTB_DIR_OUT    := pia1.io.portbDirOut
  PORTB_OUT_INT       := pia1.io.portbOut

  // ---- Address decoder (native) ----
  val mmu1 = new AddressDecoder(low_memory, 0, internal_ram)
  mmu1.io.cpuFetch               := CPU_FETCH
  mmu1.io.cpuWriteN              := R_W_N
  mmu1.io.anticFetch             := ANTIC_FETCH
  mmu1.io.dmaFetch               := io.DMA_FETCH
  mmu1.io.dmaReadEnable          := io.DMA_READ_ENABLE
  mmu1.io.dma32bitWriteEnable    := io.DMA_32BIT_WRITE_ENABLE
  mmu1.io.dma16bitWriteEnable    := io.DMA_16BIT_WRITE_ENABLE
  mmu1.io.dma8bitWriteEnable     := io.DMA_8BIT_WRITE_ENABLE
  mmu1.io.ramRequestComplete     := io.RAM_REQUEST_COMPLETE
  mmu1.io.romRequestComplete     := io.ROM_REQUEST_COMPLETE
  mmu1.io.pbiRequestComplete     := io.PBI_REQUEST_COMPLETE
  mmu1.io.pbiTakeover            := io.PBI_TAKEOVER
  mmu1.io.pbiRelease             := io.PBI_RELEASE
  mmu1.io.cartRd5                := io.CART_RD5
  mmu1.io.pbiMpdN                := io.PBI_MPD_N
  mmu1.io.resetN                 := io.RESET_N
  mmu1.io.sdramRequestComplete   := io.SDRAM_REQUEST_COMPLETE
  mmu1.io.anticAddr              := ANTIC_ADDR
  mmu1.io.anticData              := ANTIC_DO
  mmu1.io.cacheAnticData         := CACHE_ANTIC_DO
  mmu1.io.pbiData                := io.PBI_ROM_DO
  mmu1.io.cpuAddr                := CPU_ADDR
  mmu1.io.cpuWriteData           := CPU_DO
  mmu1.io.gtiaData               := GTIA_DO
  mmu1.io.cacheGtiaData          := CACHE_GTIA_DO
  mmu1.io.piaData                := PIA_DO
  mmu1.io.pokeyData              := POKEY_DO
  mmu1.io.cachePokeyData         := CACHE_POKEY_DO
  mmu1.io.portb                  := PORTB_OPTIONS
  mmu1.io.ramData                := io.RAM_DO
  mmu1.io.ramSelect              := io.RAM_SELECT(2 downto 0)
  mmu1.io.atari800mode           := io.ATARI800MODE
  mmu1.io.romData                := io.ROM_DO
  mmu1.io.sdramData              := io.SDRAM_DO
  mmu1.io.dmaAddr                := io.DMA_ADDR
  mmu1.io.dmaWriteData           := io.DMA_WRITE_DATA
  mmu1.io.cartSelect             := io.CART_EMULATION_SELECT
  mmu1.io.romInRam               := io.ROM_IN_RAM
  mmu1.io.freezerEnable          := io.freezer_enable
  mmu1.io.freezerActivate        := freezer_activate_combined

  MEMORY_READY_ANTIC  := mmu1.io.memoryReadyAntic
  io.MEMORY_READY_DMA := mmu1.io.memoryReadyDma
  MEMORY_READY_CPU    := mmu1.io.memoryReadyCpu
  GTIA_WRITE_ENABLE   := mmu1.io.gtiaWrEnable
  POKEY_WRITE_ENABLE  := mmu1.io.pokeyWrEnable
  ANTIC_WRITE_ENABLE  := mmu1.io.anticWrEnable
  PIA_WRITE_ENABLE    := mmu1.io.piaWrEnable
  PIA_READ_ENABLE     := mmu1.io.piaRdEnable
  io.RAM_WRITE_ENABLE := mmu1.io.ramWrEnable
  io.ROM_WRITE_ENABLE := mmu1.io.romWrEnable
  io.PBI_WRITE_ENABLE := mmu1.io.pbiWrEnable
  io.RAM_REQUEST      := mmu1.io.ramRequest
  io.ROM_REQUEST      := mmu1.io.romRequest
  io.PBI_REQUEST      := mmu1.io.pbiRequest
  cart_trig3_out      := mmu1.io.cartTrig3Out
  WIDTH_8BIT_ACCESS   := mmu1.io.width8bitAccess
  WIDTH_16BIT_ACCESS  := mmu1.io.width16bitAccess
  WIDTH_32BIT_ACCESS  := mmu1.io.width32bitAccess
  io.SDRAM_READ_ENABLE  := mmu1.io.sdramReadEn
  io.SDRAM_WRITE_ENABLE := mmu1.io.sdramWriteEn
  io.SDRAM_REQUEST      := mmu1.io.sdramRequest
  MEMORY_DATA           := mmu1.io.memoryData
  PBI_ADDR_INT          := mmu1.io.pbiAddr
  io.RAM_ADDR           := mmu1.io.ramAddr
  io.ROM_ADDR           := mmu1.io.romAddr
  io.SDRAM_ADDR         := mmu1.io.sdramAddr
  WRITE_DATA            := mmu1.io.writeData
  covox_write_enable    := mmu1.io.d6WrEnable
  freezer_state         := mmu1.io.freezerStateOut
  io.state_reg_out      := mmu1.io.stateRegOut

  // PORTB options / trig merge
  PORTB_OPTIONS    := B(0, 8 bits)
  GTIA_TRIG_MERGED := B(0, 4 bits)
  when(~io.ATARI800MODE) {
    PORTB_OPTIONS    := PORTB_OUT_INT
    GTIA_TRIG_MERGED := (cart_trig3_out & io.GTIA_TRIG(3)) ## io.GTIA_TRIG(2 downto 0)
  } otherwise {
    PORTB_OPTIONS    := B(0, 8 bits)
    GTIA_TRIG_MERGED := io.GTIA_TRIG(3 downto 0)
  }

  // ---- POKEY 1 (native) ----
  val pokey1 = new Pokey
  pokey1.io.enable179          := ANTIC_ENABLE_179
  pokey1.io.wrEn               := POKEY1_WRITE_ENABLE
  pokey1.io.sioIn1             := io.SIO_RXD
  pokey1.io.sioIn2             := True
  pokey1.io.sioIn3             := True
  pokey1.io.sioClockinIn       := io.SIO_CLOCKIN_IN
  io.SIO_CLOCKIN_OUT           := pokey1.io.sioClockinOut
  io.SIO_CLOCKIN_OE            := pokey1.io.sioClockinOe
  pokey1.io.addr               := PBI_ADDR_INT(3 downto 0)
  pokey1.io.dataIn             := WRITE_DATA(7 downto 0)
  pokey1.io.keyboardResponse   := io.KEYBOARD_RESPONSE
  pokey1.io.potIn              := io.POT_IN
  pokey1.io.keyboardScanEnable := True
  POKEY_IRQ                    := pokey1.io.irqNOut
  io.SIO_TXD                   := pokey1.io.sioOut1
  io.SIO_CLOCKOUT              := pokey1.io.sioClockout
  io.POT_RESET                 := pokey1.io.potReset
  POKEY1_CHANNEL0              := pokey1.io.channel0Out
  POKEY1_CHANNEL1              := pokey1.io.channel1Out
  POKEY1_CHANNEL2              := pokey1.io.channel2Out
  POKEY1_CHANNEL3              := pokey1.io.channel3Out
  POKEY1_DO                    := pokey1.io.dataOut
  io.KEYBOARD_SCAN             := pokey1.io.keyboardScan

  // Console
  CONSOL_IN := True ## io.CONSOL_OPTION ## io.CONSOL_SELECT ## io.CONSOL_START

  // ---- GTIA (native) ----
  val gtia1 = new Gtia
  gtia1.io.wrEn                   := GTIA_WRITE_ENABLE
  gtia1.io.anticFetch             := ANTIC_FETCH
  gtia1.io.cpuEnableOriginal      := ENABLE_179_MEMWAIT
  gtia1.io.pal                    := io.PAL
  gtia1.io.enable179              := ANTIC_ENABLE_179
  gtia1.io.colourClockOriginal    := ANTIC_ORIGINAL_COLOUR_CLOCK_OUT
  gtia1.io.colourClock            := ANTIC_COLOUR_CLOCK_OUT
  gtia1.io.colourClockHighres     := ANTIC_HIGHRES_COLOUR_CLOCK_OUT
  CONSOL_OUT                      := gtia1.io.consolOut
  gtia1.io.consolIn               := CONSOL_IN
  gtia1.io.trig                   := GTIA_TRIG_MERGED
  gtia1.io.addr                   := PBI_ADDR_INT(4 downto 0)
  gtia1.io.an                     := ANTIC_AN
  gtia1.io.cpuDataIn              := WRITE_DATA(7 downto 0)
  gtia1.io.memoryDataIn           := MEMORY_DATA(7 downto 0)
  io.VIDEO_VS                     := gtia1.io.vsync
  io.VIDEO_HS                     := gtia1.io.hsync
  io.VIDEO_CS                     := gtia1.io.csync
  io.VIDEO_BLANK                  := gtia1.io.blank
  io.VIDEO_BURST                  := gtia1.io.burst
  io.VIDEO_START_OF_FIELD         := gtia1.io.startOfField
  io.VIDEO_ODD_LINE               := gtia1.io.oddLine
  COLOUR                          := gtia1.io.colourOut
  GTIA_DO                         := gtia1.io.dataOut

  GTIA_SOUND := CONSOL_OUT(3)

  // Colour palette
  if (palette == 0) {
    VIDEO_B_WIDE := COLOUR
    VIDEO_R_WIDE := B(0, 8 bits)
    VIDEO_G_WIDE := B(0, 8 bits)
  } else {
    val palette4 = new GtiaPalette
    palette4.io.pal         := io.PAL
    palette4.io.atariColour := COLOUR
    VIDEO_R_WIDE := palette4.io.rNext
    VIDEO_G_WIDE := palette4.io.gNext
    VIDEO_B_WIDE := palette4.io.bNext
  }

  io.VIDEO_R := VIDEO_R_WIDE(7 downto 8 - video_bits)
  io.VIDEO_G := VIDEO_G_WIDE(7 downto 8 - video_bits)
  io.VIDEO_B := VIDEO_B_WIDE(7 downto 8 - video_bits)

  // IRQ glue (native)
  val irq_glue1 = new IrqGlue
  irq_glue1.io.pokeyIrq := POKEY_IRQ
  irq_glue1.io.piaIrqa  := PIA_IRQA
  irq_glue1.io.piaIrqb  := PIA_IRQB
  irq_glue1.io.pbiIrq   := io.PBI_IRQ_N
  IRQ_n := irq_glue1.io.combinedIrq

  // Register mirrors (native)
  val pokey1_mirror = new RegFile(16, 4)
  pokey1_mirror.io.ADDR    := PBI_ADDR_INT(3 downto 0)
  pokey1_mirror.io.DATA_IN := WRITE_DATA(7 downto 0)
  pokey1_mirror.io.WR_EN   := POKEY1_WRITE_ENABLE
  CACHE_POKEY1_DO          := pokey1_mirror.io.DATA_OUT

  val pokey2_mirror = new RegFile(16, 4)
  pokey2_mirror.io.ADDR    := PBI_ADDR_INT(3 downto 0)
  pokey2_mirror.io.DATA_IN := WRITE_DATA(7 downto 0)
  pokey2_mirror.io.WR_EN   := POKEY2_WRITE_ENABLE
  CACHE_POKEY2_DO          := pokey2_mirror.io.DATA_OUT

  val gtia_mirror = new RegFile(32, 5)
  gtia_mirror.io.ADDR    := PBI_ADDR_INT(4 downto 0)
  gtia_mirror.io.DATA_IN := WRITE_DATA(7 downto 0)
  gtia_mirror.io.WR_EN   := GTIA_WRITE_ENABLE
  CACHE_GTIA_DO          := gtia_mirror.io.DATA_OUT

  val antic_mirror = new RegFile(16, 4)
  antic_mirror.io.ADDR    := PBI_ADDR_INT(3 downto 0)
  antic_mirror.io.DATA_IN := WRITE_DATA(7 downto 0)
  antic_mirror.io.WR_EN   := ANTIC_WRITE_ENABLE
  CACHE_ANTIC_DO          := antic_mirror.io.DATA_OUT

  // COVOX (native)
  if (covox == 0) {
    covox_channel0 := B(0, 8 bits)
    covox_channel1 := B(0, 8 bits)
    covox_channel2 := B(0, 8 bits)
    covox_channel3 := B(0, 8 bits)
  } else {
    val covox1 = new Covox
    covox1.io.addr    := PBI_ADDR_INT(1 downto 0)
    covox1.io.dataIn  := WRITE_DATA(7 downto 0)
    covox1.io.wrEn    := covox_write_enable
    covox_channel0    := covox1.io.covoxChannel0
    covox_channel1    := covox1.io.covoxChannel1
    covox_channel2    := covox1.io.covoxChannel2
    covox_channel3    := covox1.io.covoxChannel3
  }

  // Freezer debug trigger (native)
  if (freezer_debug == 1) {
    val freezertrig = new FreezerDebugTrigger
    freezertrig.io.cpuAddr          := CPU_ADDR
    freezertrig.io.cpuWriteData     := CPU_DO
    freezertrig.io.cpuReadData      := MEMORY_DATA(7 downto 0)
    freezertrig.io.cpuFetch         := CPU_FETCH
    freezertrig.io.cpuFetchComplete := MEMORY_READY_CPU
    freezertrig.io.cpuWN            := R_W_N
    freezertrig.io.freezerEnable    := io.freezer_enable
    freezertrig.io.freezerState     := freezer_state
    freezertrig.io.debugAddr        := io.freezer_debug_addr
    freezertrig.io.debugData        := io.freezer_debug_data
    freezertrig.io.debugRead        := io.freezer_debug_read
    freezertrig.io.debugWrite       := io.freezer_debug_write
    freezertrig.io.debugDataMatch   := io.freezer_debug_data_match
    freezer_trigger_activate        := freezertrig.io.freezerTrigger
    freezer_trigger_nmi_n           := freezertrig.io.freezerNmiN
  } else {
    freezer_trigger_activate := False
    freezer_trigger_nmi_n    := True
  }

  freezer_activate_combined := freezer_trigger_activate | io.freezer_activate

  // Outputs
  io.PBI_ADDR              := PBI_ADDR_INT
  io.ENABLE_179_EARLY      := ANTIC_ENABLE_179
  io.PORTB_OUT             := PORTB_OUT_INT
  io.ANTIC_REFRESH         := ANTIC_REFRESH_CYCLE

  io.memory_ready_antic_out := MEMORY_READY_ANTIC
  io.memory_ready_cpu_out   := MEMORY_READY_CPU
  io.shared_enable_out      := CPU_SHARED_ENABLE
  io.nmi_n_out              := NMI_n
  io.irq_n_out              := IRQ_n
  io.rdy_out                := ANTIC_RDY
  io.AN_out                 := ANTIC_AN
  io.freezer_state_out      := freezer_state
}

