package atari800

import spinal.core._
import spinal.core.sim._

class Antic(cycle_length: Int = 16) extends Component {
  val io = new Bundle {
    val ADDR               = in  Bits(4 bits)
    val CPU_DATA_IN        = in  Bits(8 bits)
    val WR_EN              = in  Bool()
    val RNMI_N             = in  Bool() default True
    val MEMORY_READY_ANTIC = in  Bool()
    val MEMORY_READY_CPU   = in  Bool()
    val MEMORY_DATA_IN     = in  Bits(8 bits)
    val ANTIC_ENABLE_179   = in  Bool()
    val PAL                = in  Bool()
    val lightpen           = in  Bool()
    // CPU interface
    val DATA_OUT           = out Bits(8 bits)
    val NMI_N_OUT          = out Bool()
    val ANTIC_READY        = out Bool()
    // GTIA interface
    val AN                        = out Bits(3 bits)
    val COLOUR_CLOCK_ORIGINAL_OUT = out Bool()
    val COLOUR_CLOCK_OUT          = out Bool()
    val HIGHRES_COLOUR_CLOCK_OUT  = out Bool()
    // DMA fetch
    val dma_fetch_out      = out Bool()
    val dma_address_out    = out Bits(16 bits)
    // refresh
    val refresh_out        = out Bool()
    // next cycle type
    val next_cycle_type    = out Bits(3 bits)
    // turbo
    val turbo_out          = out Bool()
    // vblank
    val vblank_out         = out Bool()
    // hires enable
    val hires_ena          = in  Bool()
    // debug
    val shift_out          = out Bits(8 bits)
    val dma_clock_out      = out Bits(4 bits)
    val hcount_out         = out Bits(8 bits)
    val vcount_out         = out Bits(9 bits)
    val dbgDmactl          = out Bits(7 bits)
  }

  // Address decoder
  val decodeAddr = new CompleteAddressDecoder(4)
  decodeAddr.io.addrIn := io.ADDR
  val addrDecoded = decodeAddr.io.addrDecoded

  // DMA clock (uses the _del version)
  val anticDmaClock1 = new AnticDmaClock()

  // Counters
  val anticCounterMemoryScan  = new AnticCounter(STORE_WIDTH = 16, COUNT_WIDTH = 12)
  val anticCounterDisplayList = new AnticCounter(STORE_WIDTH = 16, COUNT_WIDTH = 10)
  val anticCounterLineBuffer  = new SimpleCounter(COUNT_WIDTH = 8)
  val anticCounterRowCount    = new SimpleCounter(COUNT_WIDTH = 4)
  val anticCounterRefreshCount = new SimpleCounter(COUNT_WIDTH = 4)

  // Line buffer (generic_ram_infer)
  val regFile1 = new GenericRamInfer(ADDRESS_WIDTH = 8, SPACE = 192)

  // Delay lines
  val nmienDelay  = new WideDelayLine(COUNT = 1, WIDTH = 2)
  val vscrolDelay = new WideDelayLine(COUNT = 1, WIDTH = 4)
  val chbaseDelay = new WideDelayLine(COUNT = 2, WIDTH = 7)
  val wsyncDelay  = new LatchDelayLine(COUNT = 1)

  // Constants
  val DMA_FETCH_LINE_BUFFER = B"000"
  val DMA_FETCH_SHIFTREG    = B"001"
  val DMA_FETCH_NULL        = B"010"
  val DMA_FETCH_INSTRUCTION = B"011"
  val DMA_FETCH_LIST_LOW    = B"100"
  val DMA_FETCH_LIST_HIGH   = B"101"

  val NO_DMA     = B"00"
  val SLOW_DMA   = B"01"
  val MEDIUM_DMA = B"10"
  val FAST_DMA   = B"11"

  val MODE_CHARACTER = B"000"
  val MODE_BITMAP    = B"001"
  val MODE_JVB       = B"010"
  val MODE_JUMP      = B"011"
  val MODE_BLANK     = B"100"

  val SLOW_SHIFT   = B"00"
  val MEDIUM_SHIFT = B"01"
  val FAST_SHIFT   = B"10"

  // -----------------------------------------------------------------------
  // Signals and registers
  // -----------------------------------------------------------------------
  val enableDma                = Bool()
  val dmaClockCharacterName    = Bool()
  val dmaClockCharacterInc     = Bool()
  val dmaClockBitmapData       = Bool()
  val dmaClockCharacterData    = Bool()

  val wsyncReg  = Reg(Bool()) init False
  val wsyncNext = Bool()
  val wsyncReset = Bool()
  val wsyncWrite = Bool()
  val wsyncDelayedWrite = Bool()

  val nmiReg  = Reg(Bool()) init False
  nmiReg.simPublic()
  val nmiNext = Bool()

  val nmistReg  = Reg(Bits(3 bits)) init B"000"
  val nmistNext = Bits(3 bits)
  val nmistReset = Bool()

  val nmienRawReg     = Reg(Bits(2 bits)) init B"00"
  nmienRawReg.simPublic()
  val nmienRawNext    = Bits(2 bits)
  val nmienDelayedReg = Bits(2 bits)

  val dliNmiReg  = Reg(Bool()) init False
  val dliNmiNext = Bool()
  val vbiNmiReg  = Reg(Bool()) init False
  vbiNmiReg.simPublic()
  val vbiNmiNext = Bool()

  val playfieldDmaStartRaw  = Bool()
  val playfieldDmaStartReg  = Reg(Bits(64 bits)) init B(0, 64 bits)
  val playfieldDmaStartNext = Bits(64 bits)
  val playfieldDmaStart     = Bool()

  val playfieldDmaEndRaw  = Bool()
  val playfieldDmaEndReg  = Reg(Bits(64 bits)) init B(0, 64 bits)
  val playfieldDmaEndNext = Bits(64 bits)
  val playfieldDmaEnd     = Bool()

  val playfieldDisplayActiveReg  = Reg(Bool()) init False
  playfieldDisplayActiveReg.simPublic()
  val playfieldDisplayActiveNext = Bool()

  val dmactlRawReg     = Reg(Bits(7 bits)) init B(0, 7 bits)
  val dmactlRawNext    = Bits(7 bits)
  val dmactlDelayedReg = Bits(7 bits)
  val dmactlDelayedEnabled = Bool()

  val playfieldDmaEnabled = Bool()

  val chactlReg  = Reg(Bits(3 bits)) init B"000"
  val chactlNext = Bits(3 bits)

  val allowRealDmaReg  = Reg(Bool()) init False
  allowRealDmaReg.simPublic()
  val allowRealDmaNext = Bool()

  val dmaFetchReg  = Reg(Bool()) init False
  dmaFetchReg.simPublic()
  val dmaFetchNext = Bool()
  val dmaAddressReg  = Reg(Bits(16 bits)) init B(0, 16 bits)
  dmaAddressReg.simPublic()
  val dmaAddressNext = Bits(16 bits)

  val dmaCacheReg  = Reg(Bits(8 bits)) init B(0, 8 bits)
  dmaCacheReg.simPublic()
  val dmaCacheNext = Bits(8 bits)
  val dmaCacheReadyReg  = Reg(Bool()) init False
  dmaCacheReadyReg.simPublic()
  val dmaCacheReadyNext = Bool()

  val dmaFetchDestinationReg  = Reg(Bits(3 bits)) init DMA_FETCH_NULL
  dmaFetchDestinationReg.simPublic()
  val dmaFetchDestinationNext = Bits(3 bits)
  val dmaFetchRequest = Bool()

  val displayListAddressLowTempReg  = Reg(Bits(8 bits)) init B(0, 8 bits)
  val displayListAddressLowTempNext = Bits(8 bits)

  val characterReg  = Reg(Bits(8 bits)) init B(0, 8 bits)
  characterReg.simPublic()
  val characterNext = Bits(8 bits)
  val displayedCharacterReg  = Reg(Bits(8 bits)) init B(0, 8 bits)
  val displayedCharacterNext = Bits(8 bits)

  val instructionReg  = Reg(Bits(8 bits)) init B(0, 8 bits)
  val instructionNext = Bits(8 bits)

  val firstLineOfInstructionReg  = Reg(Bool()) init False
  firstLineOfInstructionReg.simPublic()
  val firstLineOfInstructionNext = Bool()
  val lastLineOfInstructionLive  = Bool()
  val lastLineOfInstructionReg   = Reg(Bool()) init False
  val lastLineOfInstructionNext  = Bool()
  val forceFinalRow = Bool()

  val instructionBlankReg  = Reg(Bool()) init False
  val instructionBlankNext = Bool()

  val dmaSpeedReg  = Reg(Bits(2 bits)) init NO_DMA
  val dmaSpeedNext = Bits(2 bits)
  val slowDmaS     = Bool()
  val mediumDmaS   = Bool()
  val fastDmaS     = Bool()

  val instructionTypeReg  = Reg(Bits(3 bits)) init MODE_BLANK
  instructionTypeReg.simPublic()
  val instructionTypeNext = Bits(3 bits)

  val twoPartInstructionReg  = Reg(Bool()) init False
  val twoPartInstructionNext = Bool()

  val instructionFinalRowReg  = Reg(Bits(4 bits)) init B(0, 4 bits)
  val instructionFinalRowNext = Bits(4 bits)

  val shiftRateReg  = Reg(Bits(2 bits)) init SLOW_SHIFT
  val shiftRateNext = Bits(2 bits)

  val shiftTwobitReg  = Reg(Bool()) init False
  val shiftTwobitNext = Bool()
  val twopixelReg  = Reg(Bool()) init False
  val twopixelNext = Bool()
  val singleColourCharacterReg  = Reg(Bool()) init False
  val singleColourCharacterNext = Bool()
  val multiColourCharacterReg  = Reg(Bool()) init False
  val multiColourCharacterNext = Bool()
  val twolineCharacterReg  = Reg(Bool()) init False
  val twolineCharacterNext = Bool()
  val mapBackgroundReg  = Reg(Bool()) init False
  val mapBackgroundNext = Bool()
  val dliEnabledReg  = Reg(Bool()) init False
  val dliEnabledNext = Bool()
  val descendersReg  = Reg(Bool()) init False
  val descendersNext = Bool()

  val displayShiftReg  = Reg(Bits(8 bits)) init B(0, 8 bits)
  displayShiftReg.simPublic()
  val displayShiftNext = Bits(8 bits)
  val delayDisplayShiftReg  = Reg(Bits(25 bits)) init B(0, 25 bits)
  delayDisplayShiftReg.simPublic()
  val delayDisplayShiftNext = Bits(25 bits)

  val dataLive = Bits(2 bits)
  val loadDisplayShiftFromMemory     = Bool()
  val loadDisplayShiftFromLineBuffer = Bool()
  val enableShift = Bool()
  val shiftclockReg  = Reg(Bits(4 bits)) init B(0, 4 bits)
  val shiftclockNext = Bits(4 bits)

  val playfieldReset = Bool()
  val playfieldLoad  = Bool()

  // hcount is 10 bits in antic.vhdl (vs 8 in antic_delay)
  val hcountReg  = Reg(Bits(10 bits)) init B(0, 10 bits)
  hcountReg.simPublic()
  val hcountNext = Bits(10 bits)
  val cycleLatter = Bool()
  cycleLatter.simPublic()

  val vcountReg  = Reg(Bits(9 bits)) init B(0, 9 bits)
  vcountReg.simPublic()
  val vcountNext = Bits(9 bits)

  val vblankReg = Reg(Bool()) init False
  val vblankNext = Bool()
  val vsyncReg = Reg(Bool()) init False
  val vsyncNext = Bool()
  val hblankReg = Reg(Bool()) init False
  val hblankNext = Bool()

  val playfieldDmaStartCycle   = Bits(10 bits)
  val playfieldDmaEndCycle     = Bits(10 bits)
  val playfieldDisplayStartCycle = Bits(8 bits)
  val playfieldDisplayEndCycle   = Bits(8 bits)

  val hcountReset     = Bool()
  val vcountReset     = Bool()
  val vcountIncrement = Bool()

  val hscrolReg  = Reg(Bits(4 bits)) init B(0, 4 bits)
  val hscrolNext = Bits(4 bits)
  val hscrolAdj  = Bits(5 bits)

  val vscrolRawReg  = Reg(Bits(4 bits)) init B(0, 4 bits)
  val vscrolRawNext = Bits(4 bits)
  val vscrolDelayedReg = Bits(4 bits)

  val vscrolEnabledReg      = Reg(Bool()) init False
  val vscrolEnabledNext     = Bool()
  val vscrolLastEnabledReg  = Reg(Bool()) init False
  val vscrolLastEnabledNext = Bool()
  val updateRowCount = Bool()
  val hscrolEnabledReg  = Reg(Bool()) init False
  val hscrolEnabledNext = Bool()

  val refreshPendingReg = Reg(Bool()) init False
  val refreshPendingNext = Bool()
  val refreshFetchReg = Reg(Bool()) init False
  val refreshFetchNext = Bool()

  val incrementDisplayListAddress = Bool()
  val loadDisplayListAddress = Bool()
  val displayListAddressNext = Bits(16 bits)
  val displayListAddressReg = Bits(16 bits)
  val loadDisplayListAddressCpu = Bool()
  val loadDisplayListAddressDma = Bool()
  val displayListAddressNextCpu = Bits(16 bits)
  val displayListAddressNextDma = Bits(16 bits)

  val incrementMemoryScanAddress = Bool()
  val loadMemoryScanAddress = Bool()
  val memoryScanAddressNext = Bits(16 bits)
  val memoryScanAddressReg = Bits(16 bits)

  val incrementLineBufferAddress = Bool()
  val loadLineBufferAddress = Bool()
  val lineBufferAddressNext = Bits(8 bits)
  val lineBufferAddressReg = Bits(8 bits)

  val incrementRowCount = Bool()
  val loadRowCount = Bool()
  val rowCountNext = Bits(4 bits)
  val rowCountReg = Bits(4 bits)

  val incrementRefreshCount = Bool()
  val loadRefreshCount = Bool()
  val refreshCountNext = Bits(4 bits)
  val refreshCountReg = Bits(4 bits)

  val pmbaseReg = Reg(Bits(6 bits)) init B(0, 6 bits)
  val pmbaseNext = Bits(6 bits)
  val chbaseRawReg = Reg(Bits(7 bits)) init B(0, 7 bits)
  val chbaseRawNext = Bits(7 bits)
  val chbaseDelayedReg = Bits(7 bits)

  val lineBufferWrite = Bool()
  val lineBufferDataIn = Bits(8 bits)
  val lineBufferDataOut = Bits(8 bits)

  val anCurrentRaw = Bits(3 bits)
  val anCurrent = Bits(3 bits)
  val anPrev = Bits(3 bits)
  val anReg = Reg(Bits(3 bits)) init B"000"
  val anNext = Bits(3 bits)

  // an_current_reg is array(3 downto 0) of 3-bit
  val anCurrentReg  = Vec(Reg(Bits(3 bits)) init B"000", 4)
  val anCurrentNext = Vec(Bits(3 bits), 4)

  val penvReg = Reg(Bits(8 bits)) init B(0, 8 bits)
  val penvNext = Bits(8 bits)
  val penhReg = Reg(Bits(8 bits)) init B(0, 8 bits)
  val penhNext = Bits(8 bits)

  val colourClock8x = Bool()
  val colourClock4x = Bool()
  val colourClock2x = Bool()
  val colourClock1x = Bool()
  colourClock1x.simPublic()
  val colourClockHalfX = Bool()
  val colourClockSelected = Bool()
  val colourClockSelectedHighres = Bool()

  val colourClockShiftReg  = Reg(Bits(cycle_length bits)) init B(0, cycle_length bits)
  val colourClockShiftNext = Bits(cycle_length bits)

  val memoryReadyBoth = Bool()

  // -----------------------------------------------------------------------
  // Register update
  // -----------------------------------------------------------------------
  nmiReg := nmiNext; wsyncReg := wsyncNext; vcountReg := vcountNext; hcountReg := hcountNext
  nmistReg := nmistNext; dmactlRawReg := dmactlRawNext; chactlReg := chactlNext
  vblankReg := vblankNext; vsyncReg := vsyncNext; pmbaseReg := pmbaseNext
  allowRealDmaReg := allowRealDmaNext; displayShiftReg := displayShiftNext
  chbaseRawReg := chbaseRawNext; instructionReg := instructionNext
  firstLineOfInstructionReg := firstLineOfInstructionNext
  lastLineOfInstructionReg := lastLineOfInstructionNext
  dmaSpeedReg := dmaSpeedNext; instructionTypeReg := instructionTypeNext
  instructionFinalRowReg := instructionFinalRowNext; shiftRateReg := shiftRateNext
  shiftTwobitReg := shiftTwobitNext; twopixelReg := twopixelNext
  singleColourCharacterReg := singleColourCharacterNext
  multiColourCharacterReg := multiColourCharacterNext
  twolineCharacterReg := twolineCharacterNext; mapBackgroundReg := mapBackgroundNext
  dliEnabledReg := dliEnabledNext; twoPartInstructionReg := twoPartInstructionNext
  descendersReg := descendersNext; hscrolReg := hscrolNext
  vscrolRawReg := vscrolRawNext; vscrolEnabledReg := vscrolEnabledNext
  vscrolLastEnabledReg := vscrolLastEnabledNext; hscrolEnabledReg := hscrolEnabledNext
  shiftclockReg := shiftclockNext; hblankReg := hblankNext; anReg := anNext
  for (i <- 0 until 4) { anCurrentReg(i) := anCurrentNext(i) }
  dmaFetchReg := dmaFetchNext; dmaAddressReg := dmaAddressNext
  dmaCacheReg := dmaCacheNext; dmaCacheReadyReg := dmaCacheReadyNext
  dmaFetchDestinationReg := dmaFetchDestinationNext
  playfieldDisplayActiveReg := playfieldDisplayActiveNext
  instructionBlankReg := instructionBlankNext
  displayListAddressLowTempReg := displayListAddressLowTempNext
  characterReg := characterNext; displayedCharacterReg := displayedCharacterNext
  refreshPendingReg := refreshPendingNext; refreshFetchReg := refreshFetchNext
  dliNmiReg := dliNmiNext; vbiNmiReg := vbiNmiNext; nmienRawReg := nmienRawNext
  delayDisplayShiftReg := delayDisplayShiftNext; penhReg := penhNext; penvReg := penvNext
  colourClockShiftReg := colourClockShiftNext
  playfieldDmaStartReg := playfieldDmaStartNext
  playfieldDmaEndReg := playfieldDmaEndNext

  // -----------------------------------------------------------------------
  // Colour clock generation (shift register based)
  // -----------------------------------------------------------------------
  colourClockShiftNext := colourClockShiftReg(cycle_length - 2 downto 0) ## B"0"
  when(io.ANTIC_ENABLE_179) {
    colourClockShiftNext(cycle_length - 1 downto 1) := B(0, cycle_length - 1 bits)
    colourClockShiftNext(0) := True
  }

  colourClockHalfX := io.ANTIC_ENABLE_179

  val reduce1x = Bits(2 bits)
  reduce1x(0) := io.ANTIC_ENABLE_179
  reduce1x(1) := colourClockShiftReg(cycle_length / 2 - 1)
  colourClock1x := reduce1x.orR

  val reduce2x = Bits(2 bits)
  reduce2x(0) := colourClockShiftReg(1 * cycle_length / 4 - 1)
  reduce2x(1) := colourClockShiftReg(3 * cycle_length / 4 - 1)
  colourClock2x := (reduce1x ## reduce2x).orR

  val reduce4x = Bits(4 bits)
  reduce4x(0) := colourClockShiftReg(1 * cycle_length / 8 - 1)
  reduce4x(1) := colourClockShiftReg(3 * cycle_length / 8 - 1)
  reduce4x(2) := colourClockShiftReg(5 * cycle_length / 8 - 1)
  reduce4x(3) := colourClockShiftReg(7 * cycle_length / 8 - 1)
  colourClock4x := (reduce1x ## reduce2x ## reduce4x).orR

  val reduce8x = Bits(8 bits)
  reduce8x(0) := colourClockShiftReg(1 * cycle_length / 16 - 1)
  reduce8x(1) := colourClockShiftReg(3 * cycle_length / 16 - 1)
  reduce8x(2) := colourClockShiftReg(5 * cycle_length / 16 - 1)
  reduce8x(3) := colourClockShiftReg(7 * cycle_length / 16 - 1)
  reduce8x(4) := colourClockShiftReg(9 * cycle_length / 16 - 1)
  reduce8x(5) := colourClockShiftReg(11 * cycle_length / 16 - 1)
  reduce8x(6) := colourClockShiftReg(13 * cycle_length / 16 - 1)
  reduce8x(7) := colourClockShiftReg(15 * cycle_length / 16 - 1)
  colourClock8x := (colourClock4x ## reduce8x).orR

  // Playfield DMA start/end delay
  playfieldDmaStartNext := playfieldDmaStartReg
  playfieldDmaEndNext := playfieldDmaEndReg
  when(colourClock4x) {
    playfieldDmaStartNext := playfieldDmaStartReg(62 downto 0) ## playfieldDmaStartRaw
    playfieldDmaEndNext := playfieldDmaEndReg(62 downto 0) ## playfieldDmaEndRaw
  }

  // Speed selection
  enableDma                := colourClockHalfX
  colourClockSelected      := colourClock1x
  colourClockSelectedHighres := colourClock2x
  dmactlDelayedEnabled     := False
  playfieldDmaStart        := playfieldDmaStartRaw
  playfieldDmaEnd          := playfieldDmaEndRaw
  cycleLatter              := ~hcountReg(2)
  hscrolAdj                := hscrolReg(3 downto 1) ## B"00"

  anCurrent := anCurrentNext(0)
  anPrev    := anCurrentNext(1)

  switch(dmactlDelayedReg(6 downto 5) & (io.hires_ena ## True)) {
    is(B"01") { dmactlDelayedEnabled := True }
    is(B"10") {
      enableDma := colourClock1x; colourClockSelected := colourClock2x
      colourClockSelectedHighres := colourClock4x; dmactlDelayedEnabled := True
      playfieldDmaStart := playfieldDmaStartReg(3 + 24)
      playfieldDmaEnd := playfieldDmaEndReg(7 + 24)
      cycleLatter := hcountReg(1)
      hscrolAdj := B"0" ## hscrolReg(3 downto 1) ## B"0"
    }
    is(B"11") {
      enableDma := colourClock2x; colourClockSelected := colourClock4x
      colourClockSelectedHighres := colourClock8x; dmactlDelayedEnabled := True
      playfieldDmaStart := playfieldDmaStartReg(5 + 36)
      playfieldDmaEnd := playfieldDmaEndReg(11 + 36)
      cycleLatter := hcountReg(0)
      hscrolAdj := B"00" ## hscrolReg(3 downto 1)
    }
    default {}
  }

  // -----------------------------------------------------------------------
  // Counter connections
  // -----------------------------------------------------------------------
  anticCounterMemoryScan.io.increment := incrementMemoryScanAddress
  anticCounterMemoryScan.io.load      := loadMemoryScanAddress
  anticCounterMemoryScan.io.loadValue := memoryScanAddressNext
  memoryScanAddressReg                := anticCounterMemoryScan.io.currentValue

  loadDisplayListAddress   := loadDisplayListAddressCpu | loadDisplayListAddressDma
  displayListAddressNext   := Mux(loadDisplayListAddressCpu, displayListAddressNextCpu, displayListAddressNextDma)
  anticCounterDisplayList.io.increment := incrementDisplayListAddress
  anticCounterDisplayList.io.load      := loadDisplayListAddress
  anticCounterDisplayList.io.loadValue := displayListAddressNext
  displayListAddressReg                := anticCounterDisplayList.io.currentValue

  anticCounterLineBuffer.io.increment  := incrementLineBufferAddress
  anticCounterLineBuffer.io.load       := loadLineBufferAddress
  anticCounterLineBuffer.io.loadValue  := lineBufferAddressNext
  lineBufferAddressReg                 := anticCounterLineBuffer.io.currentValue

  anticCounterRowCount.io.increment    := incrementRowCount
  anticCounterRowCount.io.load         := loadRowCount
  anticCounterRowCount.io.loadValue    := rowCountNext
  rowCountReg                          := anticCounterRowCount.io.currentValue

  anticCounterRefreshCount.io.increment := incrementRefreshCount
  anticCounterRefreshCount.io.load      := loadRefreshCount
  anticCounterRefreshCount.io.loadValue := refreshCountNext
  refreshCountReg                       := anticCounterRefreshCount.io.currentValue

  // -----------------------------------------------------------------------
  // Horizontal counter (10 bits, incremented on colourClock4x)
  // -----------------------------------------------------------------------
  hcountNext := hcountReg
  when(colourClock4x) {
    hcountNext := B(hcountReg.asUInt + 1)
    when(colourClockHalfX) { hcountNext(2 downto 0) := B"100" }
  }
  when(hcountReset) { hcountNext := B(0, 10 bits) }

  // Vertical counter
  vcountNext := vcountReg
  when(io.ANTIC_ENABLE_179) {
    when(vcountIncrement) { vcountNext := B(vcountReg.asUInt + 1) }
    when(vcountReset)     { vcountNext := B(0, 9 bits) }
  }

  // Vertical position actions
  vblankNext  := vblankReg; vsyncNext := vsyncReg; vcountReset := False
  when(colourClock1x) {
    switch(vcountReg) {
      is(B"0" ## B"00001000") { vblankNext := False }
      is(B"0" ## B"11111000") { vblankNext := True }
      is(B"1" ## B"00010011") { vsyncNext := io.PAL }
      is(B"1" ## B"00010110") { vsyncNext := False }
      is(B"1" ## B"00111000") { vcountReset := True }
      is(B"0" ## B"11111111") { vsyncNext := ~io.PAL }
      is(B"1" ## B"00000010") { vsyncNext := False }
      is(B"1" ## B"00000110") { vcountReset := ~io.PAL }
      default {}
    }
  }

  // -----------------------------------------------------------------------
  // Playfield start/end calculation
  // -----------------------------------------------------------------------
  playfieldDmaStartCycle     := B(0x3FC, 10 bits) // 0xFF << 2 + 00
  playfieldDmaEndCycle       := B(0x3FC, 10 bits)
  playfieldDisplayStartCycle := B(0xFF, 8 bits)
  playfieldDisplayEndCycle   := B(0xFF, 8 bits)

  playfieldDmaEnabled := dmactlDelayedReg(1) | dmactlDelayedReg(0)

  val pfWidthBase = dmactlDelayedReg(1 downto 0)
  val pfWidth = Bits(2 bits)
  pfWidth := pfWidthBase
  when(hscrolEnabledReg && pfWidthBase =/= B"11") {
    pfWidth := B(pfWidthBase.asUInt + 1)
  }

  val hscrolAdjEn = Bits(6 bits)
  hscrolAdjEn := B(0, 6 bits)
  when(hscrolEnabledReg) { hscrolAdjEn := hscrolAdj ## B"0" }

  switch(pfWidth) {
    is(B"11") {
      playfieldDmaStartCycle := B((B"00010010" ## B"11").asUInt + hscrolAdjEn.asUInt.resize(10))
      playfieldDmaEndCycle   := B((B"11010000" ## B"11").asUInt + hscrolAdjEn.asUInt.resize(10))
    }
    is(B"10") {
      playfieldDmaStartCycle := B((B"00100010" ## B"11").asUInt + hscrolAdjEn.asUInt.resize(10))
      playfieldDmaEndCycle   := B((B"11000000" ## B"11").asUInt + hscrolAdjEn.asUInt.resize(10))
    }
    is(B"01") {
      playfieldDmaStartCycle := B((B"00110010" ## B"11").asUInt + hscrolAdjEn.asUInt.resize(10))
      playfieldDmaEndCycle   := B((B"10110000" ## B"11").asUInt + hscrolAdjEn.asUInt.resize(10))
    }
    default {}
  }

  switch(dmactlDelayedReg(1 downto 0)) {
    is(B"11") {
      playfieldDisplayStartCycle := B"00101100"
      when(hscrolReg.asUInt > U"1100") { playfieldDisplayStartCycle := B"00110000" }
      playfieldDisplayEndCycle := B"11011110"
    }
    is(B"10") { playfieldDisplayStartCycle := B"00110000"; playfieldDisplayEndCycle := B"11010000" }
    is(B"01") { playfieldDisplayStartCycle := B"01000000"; playfieldDisplayEndCycle := B"11000000" }
    default {}
  }

  // -----------------------------------------------------------------------
  // Horizontal position actions / DMA scheduling
  // -----------------------------------------------------------------------
  allowRealDmaNext := allowRealDmaReg
  playfieldDmaStartRaw := False; playfieldDmaEndRaw := False
  playfieldDisplayActiveNext := playfieldDisplayActiveReg
  dmaFetchRequest := False; dmaAddressNext := dmaAddressReg
  dmaFetchDestinationNext := dmaFetchDestinationReg
  firstLineOfInstructionNext := firstLineOfInstructionReg
  lastLineOfInstructionNext := lastLineOfInstructionReg
  loadDisplayShiftFromLineBuffer := False
  incrementMemoryScanAddress := False
  lineBufferAddressNext := B(0, 8 bits); loadLineBufferAddress := False; incrementLineBufferAddress := False
  hblankNext := hblankReg; hcountReset := False; vcountIncrement := False
  characterNext := characterReg; displayedCharacterNext := displayedCharacterReg
  dliNmiNext := dliNmiReg; vbiNmiNext := vbiNmiReg; wsyncReset := False
  loadRefreshCount := False; refreshCountNext := B(0, 4 bits)
  updateRowCount := False; vscrolLastEnabledNext := vscrolLastEnabledReg

  when(colourClockSelected) {
    when(dmaClockCharacterData) {
      when(dmactlDelayedEnabled && instructionTypeReg === MODE_CHARACTER) { dmaFetchRequest := True }
      when(twolineCharacterReg) {
        when(singleColourCharacterReg) {
          dmaAddressNext := chbaseDelayedReg(6 downto 0) ## characterReg(5 downto 0) ## (rowCountReg(3 downto 1) ^ (chactlReg(2) ## chactlReg(2) ## chactlReg(2)))
        } otherwise {
          dmaAddressNext := chbaseDelayedReg(6 downto 1) ## characterReg(6 downto 0) ## (rowCountReg(3 downto 1) ^ (chactlReg(2) ## chactlReg(2) ## chactlReg(2)))
        }
      } otherwise {
        when(singleColourCharacterReg) {
          dmaAddressNext := chbaseDelayedReg(6 downto 0) ## characterReg(5 downto 0) ## (rowCountReg(2 downto 0) ^ (chactlReg(2) ## chactlReg(2) ## chactlReg(2)))
        } otherwise {
          dmaAddressNext := chbaseDelayedReg(6 downto 1) ## characterReg(6 downto 0) ## (rowCountReg(2 downto 0) ^ (chactlReg(2) ## chactlReg(2) ## chactlReg(2)))
        }
      }
      displayedCharacterNext := characterReg
      dmaFetchDestinationNext := DMA_FETCH_SHIFTREG
      loadDisplayShiftFromLineBuffer := (instructionTypeReg === MODE_BITMAP)
      incrementLineBufferAddress := True
    }

    // Playfield start/end
    when(hcountReg === playfieldDmaStartCycle) { playfieldDmaStartRaw := True; loadLineBufferAddress := True }
    when(hcountReg === playfieldDmaEndCycle) { playfieldDmaEndRaw := True }

    when(colourClock1x) {
      when(hcountReg(9 downto 2) === playfieldDisplayStartCycle) { playfieldDisplayActiveNext := True }
      when(hcountReg(9 downto 2) === playfieldDisplayEndCycle) { playfieldDisplayActiveNext := False }

      switch(hcountReg(9 downto 2)) {
        is(B"00000000") { // missile DMA
          dmaFetchRequest := dmactlDelayedReg(2) | dmactlDelayedReg(3)
          dmaFetchDestinationNext := DMA_FETCH_NULL
          when(dmactlDelayedReg(4)) { dmaAddressNext := pmbaseReg(5 downto 1) ## B"011" ## vcountReg(7 downto 0) }
          .otherwise                  { dmaAddressNext := pmbaseReg ## B"011" ## vcountReg(7 downto 1) }
        }
        is(B"00000010") { // display list dma
          firstLineOfInstructionNext := False
          when(instructionTypeReg === MODE_JVB) { firstLineOfInstructionNext := firstLineOfInstructionReg | vblankReg }
          .otherwise {
            when(lastLineOfInstructionReg) {
              dmaFetchRequest := dmactlDelayedEnabled; dmaAddressNext := displayListAddressReg
              dmaFetchDestinationNext := DMA_FETCH_INSTRUCTION; firstLineOfInstructionNext := True
              vscrolLastEnabledNext := vscrolEnabledReg
            }
          }
        }
        is(B"00000101") { updateRowCount := True }
        is(B"00000100", B"00000110", B"00001000", B"00001010") { // player DMA
          dmaFetchRequest := dmactlDelayedReg(3); dmaFetchDestinationNext := DMA_FETCH_NULL
          when(dmactlDelayedReg(4)) { dmaAddressNext := pmbaseReg(5 downto 1) ## B(hcountReg(5 downto 3).asUInt + 2, 3 bits).asBits ## vcountReg(7 downto 0) }
          .otherwise                  { dmaAddressNext := pmbaseReg ## B(hcountReg(5 downto 3).asUInt + 2, 3 bits).asBits ## vcountReg(7 downto 1) }
        }
        is(B"00001100") { // lms lower byte
          dmaFetchRequest := dmactlDelayedEnabled & firstLineOfInstructionReg & twoPartInstructionReg
          dmaAddressNext := displayListAddressReg; dmaFetchDestinationNext := DMA_FETCH_LIST_LOW
        }
        is(B"00001110") { // lms upper byte
          dmaFetchRequest := dmactlDelayedEnabled & firstLineOfInstructionReg & twoPartInstructionReg
          dmaAddressNext := displayListAddressReg; dmaFetchDestinationNext := DMA_FETCH_LIST_HIGH
          when(instructionTypeReg === MODE_JVB) { firstLineOfInstructionNext := False }
          dliNmiNext := dliEnabledReg & lastLineOfInstructionLive & ~vblankReg
          when(vcountReg === (B"0" ## B"11111000")) { vbiNmiNext := True }
        }
        is(B"00010010") { dliNmiNext := False; vbiNmiNext := False }
        is(B"00100010") { hblankNext := False }
        is(B"00110001") { loadRefreshCount := True; refreshCountNext := B(0, 4 bits) }
        is(B"11010010") { wsyncReset := True }
        is(B"11010100") { allowRealDmaNext := False; loadRefreshCount := True; refreshCountNext := B"1001" }
        is(B"11011000") { lastLineOfInstructionNext := lastLineOfInstructionLive }
        is(B"11011110") { vcountIncrement := True; hblankNext := True }
        is(B"11100011") { hcountReset := True; allowRealDmaNext := ~vblankReg }
        default {}
      }
    }

    // Playfield DMA
    when(instructionTypeReg === MODE_CHARACTER && dmaClockCharacterName) {
      dmaFetchRequest := dmactlDelayedEnabled & firstLineOfInstructionReg & playfieldDmaEnabled
      dmaAddressNext := memoryScanAddressReg
      dmaFetchDestinationNext := DMA_FETCH_LINE_BUFFER
      incrementMemoryScanAddress := firstLineOfInstructionReg
    }
    when(instructionTypeReg === MODE_CHARACTER && dmaClockCharacterInc) {
      characterNext := lineBufferDataOut; incrementLineBufferAddress := True
    }
    when(instructionTypeReg === MODE_BITMAP && dmaClockBitmapData && firstLineOfInstructionReg) {
      dmaFetchRequest := dmactlDelayedEnabled & playfieldDmaEnabled
      dmaAddressNext := memoryScanAddressReg; dmaFetchDestinationNext := DMA_FETCH_LINE_BUFFER
      incrementMemoryScanAddress := True
    }
    when(vblankReg) { dmaFetchRequest := False }
    when(refreshFetchNext) { dmaAddressNext := B(0, 16 bits) }
  }

  // Refresh handling
  incrementRefreshCount := False; refreshPendingNext := refreshPendingReg; refreshFetchNext := refreshFetchReg
  when(colourClockSelected && cycleLatter) {
    refreshFetchNext := False
    when(refreshPendingReg && (~dmaFetchNext || ~allowRealDmaNext)) { refreshFetchNext := True; refreshPendingNext := False }
    when(hcountReg(4 downto 3) === B"01" && refreshCountReg.asUInt < 9) {
      incrementRefreshCount := True; refreshFetchNext := ~dmaFetchNext; refreshPendingNext := dmaFetchNext
    }
  }

  // NMI handling
  nmienDelay.io.syncReset := False; nmienDelay.io.dataIn := nmienRawReg
  nmienDelay.io.enable := io.ANTIC_ENABLE_179; nmienDelayedReg := nmienDelay.io.dataOut

  nmiNext := nmiReg
  when(io.ANTIC_ENABLE_179) {
    nmiNext := ~((dliNmiReg & nmienDelayedReg(1)) | (vbiNmiReg & nmienDelayedReg(0))) & io.RNMI_N
  }

  nmistNext(0) := (nmistReg(0) & ~nmistReset) | ~io.RNMI_N
  nmistNext(1) := ((nmistReg(1) & ~nmistReset) | vbiNmiReg | vbiNmiNext) & ~(dliNmiReg | dliNmiNext)
  nmistNext(2) := ((nmistReg(2) & ~nmistReset) | dliNmiReg | dliNmiNext) & ~(vbiNmiReg | vbiNmiNext)

  // DMA clock speed
  slowDmaS := False; mediumDmaS := False; fastDmaS := False
  switch(dmaSpeedReg) {
    is(SLOW_DMA)   { slowDmaS := True }
    is(MEDIUM_DMA) { mediumDmaS := True }
    is(FAST_DMA)   { fastDmaS := True }
    default {}
  }

  anticDmaClock1.io.enableDma := enableDma; anticDmaClock1.io.playfieldStart := playfieldDmaStart
  anticDmaClock1.io.playfieldEnd := playfieldDmaEnd; anticDmaClock1.io.vblank := vblankReg
  anticDmaClock1.io.slowDma := slowDmaS; anticDmaClock1.io.mediumDma := mediumDmaS; anticDmaClock1.io.fastDma := fastDmaS
  dmaClockCharacterName := anticDmaClock1.io.dmaClockOut0; dmaClockCharacterInc := anticDmaClock1.io.dmaClockOut1
  dmaClockBitmapData := anticDmaClock1.io.dmaClockOut2; dmaClockCharacterData := anticDmaClock1.io.dmaClockOut3

  // Line buffer
  regFile1.io.data    := lineBufferDataIn; regFile1.io.address := lineBufferAddressReg
  regFile1.io.we      := lineBufferWrite; lineBufferDataOut := regFile1.io.q

  // Vertical scrolling
  vscrolDelay.io.syncReset := False; vscrolDelay.io.dataIn := vscrolRawReg
  vscrolDelay.io.enable := io.ANTIC_ENABLE_179; vscrolDelayedReg := vscrolDelay.io.dataOut

  loadRowCount := False; rowCountNext := B(0, 4 bits); incrementRowCount := False; lastLineOfInstructionLive := False
  when(updateRowCount) {
    when(vscrolEnabledReg && ~vscrolLastEnabledReg) { rowCountNext := vscrolDelayedReg }
    when(firstLineOfInstructionReg) { loadRowCount := True } otherwise { incrementRowCount := True }
  }
  when(~vscrolEnabledReg && vscrolLastEnabledReg) {
    when(rowCountReg === vscrolDelayedReg) { lastLineOfInstructionLive := True }
  } otherwise {
    when(rowCountReg === instructionFinalRowReg) { lastLineOfInstructionLive := True }
  }
  when(vblankReg || forceFinalRow) { lastLineOfInstructionLive := True }

  // chbase delay
  chbaseDelay.io.syncReset := False; chbaseDelay.io.dataIn := chbaseRawReg
  chbaseDelay.io.enable := io.ANTIC_ENABLE_179; chbaseDelayedReg := chbaseDelay.io.dataOut

  // Instruction decode (same as AnticDelay)
  dmaSpeedNext := NO_DMA; shiftRateNext := SLOW_SHIFT
  shiftTwobitNext := False; twopixelNext := False; singleColourCharacterNext := False
  multiColourCharacterNext := False; twolineCharacterNext := False; mapBackgroundNext := False
  twoPartInstructionNext := instructionReg(6)
  instructionBlankNext := False; vscrolEnabledNext := instructionReg(5)
  hscrolEnabledNext := instructionReg(4); dliEnabledNext := instructionReg(7)
  descendersNext := False; forceFinalRow := False

  switch(instructionReg(3 downto 0)) {
    is(B"0000") { instructionTypeNext := MODE_BLANK; instructionFinalRowNext := False ## instructionReg(6 downto 4); twoPartInstructionNext := False; instructionBlankNext := True; vscrolEnabledNext := False; hscrolEnabledNext := False }
    is(B"0001") { instructionTypeNext := MODE_JUMP; instructionFinalRowNext := B(0, 4 bits); instructionBlankNext := True; vscrolEnabledNext := False; hscrolEnabledNext := False; twoPartInstructionNext := True; when(instructionReg(6)) { instructionTypeNext := MODE_JVB; forceFinalRow := True } }
    is(B"0010") { instructionTypeNext := MODE_CHARACTER; instructionFinalRowNext := B"0111"; dmaSpeedNext := FAST_DMA; shiftRateNext := FAST_SHIFT; shiftTwobitNext := True; twopixelNext := True }
    is(B"0011") { instructionTypeNext := MODE_CHARACTER; instructionFinalRowNext := B"1001"; dmaSpeedNext := FAST_DMA; shiftRateNext := FAST_SHIFT; shiftTwobitNext := True; twopixelNext := True; descendersNext := True }
    is(B"0100") { instructionTypeNext := MODE_CHARACTER; instructionFinalRowNext := B"0111"; dmaSpeedNext := FAST_DMA; shiftRateNext := FAST_SHIFT; shiftTwobitNext := True; multiColourCharacterNext := True }
    is(B"0101") { instructionTypeNext := MODE_CHARACTER; instructionFinalRowNext := B"1111"; dmaSpeedNext := FAST_DMA; shiftRateNext := FAST_SHIFT; shiftTwobitNext := True; twolineCharacterNext := True; multiColourCharacterNext := True }
    is(B"0110") { instructionTypeNext := MODE_CHARACTER; instructionFinalRowNext := B"0111"; dmaSpeedNext := MEDIUM_DMA; shiftRateNext := FAST_SHIFT; singleColourCharacterNext := True }
    is(B"0111") { instructionTypeNext := MODE_CHARACTER; instructionFinalRowNext := B"1111"; dmaSpeedNext := MEDIUM_DMA; shiftRateNext := FAST_SHIFT; singleColourCharacterNext := True; twolineCharacterNext := True }
    is(B"1000") { instructionTypeNext := MODE_BITMAP; instructionFinalRowNext := B"0111"; dmaSpeedNext := SLOW_DMA; shiftTwobitNext := True; mapBackgroundNext := True }
    is(B"1001") { instructionTypeNext := MODE_BITMAP; instructionFinalRowNext := B"0011"; dmaSpeedNext := SLOW_DMA; shiftRateNext := MEDIUM_SHIFT; mapBackgroundNext := True }
    is(B"1010") { instructionTypeNext := MODE_BITMAP; instructionFinalRowNext := B"0011"; dmaSpeedNext := MEDIUM_DMA; shiftRateNext := MEDIUM_SHIFT; shiftTwobitNext := True; mapBackgroundNext := True }
    is(B"1011") { instructionTypeNext := MODE_BITMAP; instructionFinalRowNext := B"0001"; dmaSpeedNext := MEDIUM_DMA; shiftRateNext := FAST_SHIFT; mapBackgroundNext := True }
    is(B"1100") { instructionTypeNext := MODE_BITMAP; instructionFinalRowNext := B"0000"; dmaSpeedNext := MEDIUM_DMA; shiftRateNext := FAST_SHIFT; mapBackgroundNext := True }
    is(B"1101") { instructionTypeNext := MODE_BITMAP; instructionFinalRowNext := B"0001"; dmaSpeedNext := FAST_DMA; shiftRateNext := FAST_SHIFT; shiftTwobitNext := True; mapBackgroundNext := True }
    is(B"1110") { instructionTypeNext := MODE_BITMAP; instructionFinalRowNext := B"0000"; dmaSpeedNext := FAST_DMA; shiftRateNext := FAST_SHIFT; shiftTwobitNext := True; mapBackgroundNext := True }
    is(B"1111") { instructionTypeNext := MODE_BITMAP; instructionFinalRowNext := B"0000"; dmaSpeedNext := FAST_DMA; shiftRateNext := FAST_SHIFT; shiftTwobitNext := True; twopixelNext := True }
  }

  // DMA fetching / cache
  instructionNext := instructionReg; displayListAddressNextDma := displayListAddressReg
  loadDisplayListAddressDma := False; memoryScanAddressNext := memoryScanAddressReg; loadMemoryScanAddress := False
  incrementDisplayListAddress := False; loadDisplayShiftFromMemory := False
  displayListAddressLowTempNext := displayListAddressLowTempReg
  lineBufferDataIn := B(0, 8 bits); lineBufferWrite := False
  dmaCacheNext := dmaCacheReg; dmaCacheReadyNext := dmaCacheReadyReg
  dmaFetchNext := dmaFetchRequest | dmaFetchReg

  when(dmaFetchReg && memoryReadyBoth) { dmaCacheNext := io.MEMORY_DATA_IN; dmaCacheReadyNext := True; dmaFetchNext := False }
  when(~allowRealDmaReg && allowRealDmaNext) { dmaFetchNext := False }
  when(vblankReg && instructionTypeReg === MODE_JVB) { instructionNext := B(0, 8 bits) }

  when(dmaCacheReadyReg && cycleLatter) {
    switch(dmaFetchDestinationReg) {
      is(DMA_FETCH_LINE_BUFFER) { lineBufferDataIn := dmaCacheReg; lineBufferWrite := True }
      is(DMA_FETCH_SHIFTREG) { loadDisplayShiftFromMemory := True }
      is(DMA_FETCH_NULL) {}
      is(DMA_FETCH_INSTRUCTION) { instructionNext := dmaCacheReg; incrementDisplayListAddress := True }
      is(DMA_FETCH_LIST_LOW) {
        when(instructionTypeReg === MODE_JUMP || instructionTypeReg === MODE_JVB) { displayListAddressLowTempNext := dmaCacheReg; incrementDisplayListAddress := True }
        .otherwise { incrementDisplayListAddress := True; memoryScanAddressNext(7 downto 0) := dmaCacheReg; loadMemoryScanAddress := True }
      }
      is(DMA_FETCH_LIST_HIGH) {
        when(instructionTypeReg === MODE_JUMP || instructionTypeReg === MODE_JVB) { displayListAddressNextDma := dmaCacheReg ## displayListAddressLowTempReg; loadDisplayListAddressDma := True }
        .otherwise { incrementDisplayListAddress := True; memoryScanAddressNext(15 downto 8) := dmaCacheReg; loadMemoryScanAddress := True }
      }
      default {}
    }
    dmaCacheReadyNext := False
  }

  // Shift register clock
  playfieldReset := hblankReg
  playfieldLoad  := dmaClockCharacterName

  shiftclockNext := shiftclockReg; enableShift := False
  when(colourClockSelected) {
    shiftclockNext := shiftclockReg(2 downto 0) ## shiftclockReg(3)
    when(playfieldLoad) { shiftclockNext := B"1000" }
    when(playfieldReset) { shiftclockNext := B"0000" }
    switch(shiftRateReg) {
      is(SLOW_SHIFT) { enableShift := (shiftclockReg(0) & ~(hscrolReg(1) & hscrolEnabledReg)) | (shiftclockReg(2) & hscrolReg(1) & hscrolEnabledReg) }
      is(MEDIUM_SHIFT) { enableShift := shiftclockReg(2) | shiftclockReg(0) }
      is(FAST_SHIFT) { enableShift := shiftclockReg(3) | shiftclockReg(2) | shiftclockReg(1) | shiftclockReg(0) }
      default {}
    }
  }

  displayShiftNext := displayShiftReg
  when(enableShift) {
    when(shiftTwobitReg) { displayShiftNext := displayShiftReg(5 downto 0) ## B"00" }
    .otherwise { displayShiftNext := displayShiftReg(6 downto 0) ## B"0" }
  }
  when(loadDisplayShiftFromMemory) { displayShiftNext := dmaCacheReg }
  when(loadDisplayShiftFromLineBuffer) { displayShiftNext := lineBufferDataOut }

  delayDisplayShiftNext := delayDisplayShiftReg
  when(colourClockSelected) {
    when(instructionTypeReg === MODE_CHARACTER) {
      delayDisplayShiftNext := displayedCharacterReg(7 downto 5) ## B"00" ## delayDisplayShiftReg(24 downto 22) ## displayShiftReg(7 downto 6) ## delayDisplayShiftReg(19 downto 5)
    } otherwise {
      delayDisplayShiftNext := displayedCharacterReg(7 downto 5) ## displayShiftReg(7 downto 6) ## delayDisplayShiftReg(24 downto 5)
    }
  }

  // AN output calculation (raw, no blanking)
  anCurrentRaw := B"000"; dataLive := B"00"
  when(shiftTwobitReg) {
    anCurrentRaw(0) := delayDisplayShiftReg(0); anCurrentRaw(1) := delayDisplayShiftReg(1); anCurrentRaw(2) := True
    when(instructionTypeReg === MODE_CHARACTER) {
      dataLive(0) := delayDisplayShiftReg(0); dataLive(1) := delayDisplayShiftReg(1)
      when(descendersReg) {
        when(delayDisplayShiftReg(3 downto 2) === B"11") { when(rowCountReg.asUInt < 2) { dataLive := B"00" } }
        .otherwise { when(rowCountReg.asUInt >= 8) { dataLive := B"00" } }
      }
      when(delayDisplayShiftReg(4)) {
        when(chactlReg(1)) { anCurrentRaw(0) := ~(dataLive(0) & ~chactlReg(0)); anCurrentRaw(1) := ~(dataLive(1) & ~chactlReg(0)) }
        .otherwise { anCurrentRaw(0) := dataLive(0) & ~chactlReg(0); anCurrentRaw(1) := dataLive(1) & ~chactlReg(0) }
      } otherwise { anCurrentRaw(0) := dataLive(0); anCurrentRaw(1) := dataLive(1) }
    }
    when(multiColourCharacterReg) {
      switch(delayDisplayShiftReg(1 downto 0)) {
        is(B"00") { anCurrentRaw := B"000" }; is(B"01") { anCurrentRaw := B"100" }
        is(B"10") { anCurrentRaw := B"101" }; is(B"11") { anCurrentRaw := B"11" ## delayDisplayShiftReg(4) }
      }
    }
    when(mapBackgroundReg) {
      switch(delayDisplayShiftReg(1 downto 0)) {
        is(B"00") { anCurrentRaw := B"000" }; is(B"01") { anCurrentRaw := B"100" }
        is(B"10") { anCurrentRaw := B"101" }; is(B"11") { anCurrentRaw := B"110" }
      }
    }
  } otherwise {
    when(singleColourCharacterReg) {
      when(delayDisplayShiftReg(1)) { anCurrentRaw(0) := delayDisplayShiftReg(3); anCurrentRaw(1) := delayDisplayShiftReg(4); anCurrentRaw(2) := True }
      .otherwise { anCurrentRaw := B"000" }
    }
    when(mapBackgroundReg) { when(delayDisplayShiftReg(1)) { anCurrentRaw := B"100" } .otherwise { anCurrentRaw := B"000" } }
  }

  // AN current delay shift
  for (i <- 0 until 4) { anCurrentNext(i) := anCurrentReg(i) }
  when(colourClockSelected) {
    anCurrentNext(3) := anCurrentReg(2); anCurrentNext(2) := anCurrentReg(1)
    anCurrentNext(1) := anCurrentReg(0); anCurrentNext(0) := anCurrentRaw
  }

  // AN with blanking
  anNext := anCurrent
  when(hscrolReg(0) & hscrolEnabledReg) { anNext := anPrev }
  when(~playfieldDisplayActiveReg | instructionBlankReg | vblankReg | ~playfieldDmaEnabled) { anNext := B"000" }
  when(vblankReg | hblankReg) {
    anNext(0) := vsyncReg | twopixelReg; anNext(1) := ~vsyncReg
    anNext(2) := ~hblankReg & twopixelReg & playfieldDmaEnabled
  }

  // Wsync delay
  wsyncDelay.io.syncReset := False; wsyncDelay.io.dataIn := wsyncWrite
  wsyncDelay.io.enable := io.ANTIC_ENABLE_179; wsyncDelayedWrite := wsyncDelay.io.dataOut

  dmactlDelayedReg := dmactlRawNext

  // Register writes
  nmienRawNext := nmienRawReg; dmactlRawNext := dmactlRawReg; chactlNext := chactlReg
  hscrolNext := hscrolReg; vscrolRawNext := vscrolRawReg; chbaseRawNext := chbaseRawReg
  pmbaseNext := pmbaseReg; wsyncNext := (wsyncDelayedWrite | wsyncReg) & ~wsyncReset
  nmistReset := False; wsyncWrite := False
  loadDisplayListAddressCpu := False; displayListAddressNextCpu := displayListAddressReg

  when(io.WR_EN) {
    when(addrDecoded(0))  { dmactlRawNext := io.CPU_DATA_IN(6 downto 0) }
    when(addrDecoded(1))  { chactlNext := io.CPU_DATA_IN(2 downto 0) }
    when(addrDecoded(2))  { displayListAddressNextCpu(7 downto 0) := io.CPU_DATA_IN; loadDisplayListAddressCpu := True }
    when(addrDecoded(3))  { displayListAddressNextCpu(15 downto 8) := io.CPU_DATA_IN; loadDisplayListAddressCpu := True }
    when(addrDecoded(4))  { hscrolNext := io.CPU_DATA_IN(3 downto 0) }
    when(addrDecoded(5))  { vscrolRawNext := io.CPU_DATA_IN(3 downto 0) }
    when(addrDecoded(7))  { pmbaseNext := io.CPU_DATA_IN(7 downto 2) }
    when(addrDecoded(9))  { chbaseRawNext := io.CPU_DATA_IN(7 downto 1) }
    when(addrDecoded(10)) { wsyncWrite := True }
    when(addrDecoded(14)) { nmienRawNext := io.CPU_DATA_IN(7 downto 6) }
    when(addrDecoded(15)) { nmistReset := True }
  }

  // Register reads
  io.DATA_OUT := B"11111111"
  when(addrDecoded(8))  { io.DATA_OUT := hcountReg(9 downto 2) }
  when(addrDecoded(11)) { io.DATA_OUT := vcountReg(8 downto 1) }
  when(addrDecoded(12)) { io.DATA_OUT := penhReg }
  when(addrDecoded(13)) { io.DATA_OUT := penvReg }
  when(addrDecoded(15)) { io.DATA_OUT := nmistReg ## B"00000" }

  // Light pen
  penvNext := penvReg; penhNext := penhReg
  when(~io.lightpen) { penvNext := vcountReg(8 downto 1); penhNext := hcountReg(9 downto 2) }

  // Memory ready mux
  memoryReadyBoth := Mux(allowRealDmaReg, io.MEMORY_READY_ANTIC, io.MEMORY_READY_CPU)

  // -----------------------------------------------------------------------
  // Outputs
  // -----------------------------------------------------------------------
  io.NMI_N_OUT          := nmiReg
  io.shift_out          := displayShiftReg
  io.dma_clock_out(3)   := dmaClockCharacterName
  io.dma_clock_out(2)   := dmaClockCharacterInc
  io.dma_clock_out(1)   := dmaClockBitmapData
  io.dma_clock_out(0)   := dmaClockCharacterData
  io.AN                 := anReg
  io.ANTIC_READY        := ~wsyncReg
  io.dma_fetch_out      := allowRealDmaReg & dmaFetchReg
  io.dma_address_out    := dmaAddressReg
  io.refresh_out        := refreshFetchReg
  io.COLOUR_CLOCK_ORIGINAL_OUT := colourClock1x
  io.COLOUR_CLOCK_OUT          := colourClockSelected
  io.HIGHRES_COLOUR_CLOCK_OUT  := colourClockSelectedHighres
  io.vcount_out         := vcountReg
  io.hcount_out         := hcountReg(9 downto 2)
  io.turbo_out          := dmactlRawReg(6)
  io.vblank_out         := vblankReg
  io.next_cycle_type    := B"000" // TODO
  io.dbgDmactl          := dmactlRawReg
}
