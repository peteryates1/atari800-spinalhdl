package atari800

import spinal.core._

class AddressDecoder(
  LOW_MEMORY   : Int = 0,  // 0=8MB SDRAM, 1=1MB, 2=512KB
  SYSTEM       : Int = 0,  // 0=Atari XL/XE, 10=Atari5200
  INTERNAL_RAM : Int = 0   // must be power of 2
) extends Component {
  val io = new Bundle {
    // bus masters
    val cpuAddr             = in  Bits(16 bits)
    val cpuFetch            = in  Bool()
    val cpuWriteN           = in  Bool()
    val cpuWriteData        = in  Bits(8 bits)

    val anticAddr           = in  Bits(16 bits)
    val anticFetch          = in  Bool()

    val dmaAddr             = in  Bits(24 bits)
    val dmaFetch            = in  Bool()
    val dmaReadEnable       = in  Bool()
    val dma32bitWriteEnable = in  Bool()
    val dma16bitWriteEnable = in  Bool()
    val dma8bitWriteEnable  = in  Bool()
    val dmaWriteData        = in  Bits(32 bits)

    // sources of data
    val romData             = in  Bits(8 bits)
    val gtiaData            = in  Bits(8 bits)
    val cacheGtiaData       = in  Bits(8 bits)
    val pokeyData           = in  Bits(8 bits)
    val cachePokeyData      = in  Bits(8 bits)
    val anticData           = in  Bits(8 bits)
    val cacheAnticData      = in  Bits(8 bits)
    val piaData             = in  Bits(8 bits)
    val ramData             = in  Bits(16 bits)
    val pbiData             = in  Bits(8 bits)

    // pbi external access
    val pbiTakeover         = in  Bool()
    val pbiRelease          = in  Bool()

    // completion flags
    val ramRequestComplete  = in  Bool()
    val romRequestComplete  = in  Bool()
    val pbiRequestComplete  = in  Bool()

    // configuration
    val portb               = in  Bits(8 bits)
    val resetN              = in  Bool()
    val romInRam            = in  Bool()
    val atari800mode        = in  Bool()
    val cartSelect          = in  Bits(6 bits)
    val ramSelect           = in  Bits(3 bits)
    val cartRd5             = in  Bool()
    val pbiMpdN             = in  Bool()

    // outputs
    val memoryData          = out Bits(32 bits)
    val memoryReadyAntic    = out Bool()
    val memoryReadyDma      = out Bool()
    val memoryReadyCpu      = out Bool()

    val gtiaWrEnable        = out Bool()
    val pokeyWrEnable       = out Bool()
    val anticWrEnable       = out Bool()
    val piaWrEnable         = out Bool()
    val piaRdEnable         = out Bool()
    val ramWrEnable         = out Bool()
    val romWrEnable         = out Bool()
    val pbiWrEnable         = out Bool()
    val d6WrEnable          = out Bool()

    val romAddr             = out Bits(22 bits)
    val ramAddr             = out Bits(19 bits)
    val pbiAddr             = out Bits(16 bits)

    val ramRequest          = out Bool()
    val romRequest          = out Bool()
    val pbiRequest          = out Bool()

    val cartTrig3Out        = out Bool()

    val width8bitAccess     = out Bool()
    val width16bitAccess    = out Bool()
    val width32bitAccess    = out Bool()

    val sdramAddr           = out Bits(23 bits)
    val sdramReadEn         = out Bool()
    val sdramWriteEn        = out Bool()
    val sdramRequest        = out Bool()
    val sdramRequestComplete = in  Bool()
    val sdramData           = in  Bits(32 bits)

    val writeData           = out Bits(32 bits)

    val freezerEnable       = in  Bool()
    val freezerActivate     = in  Bool()
    val freezerStateOut     = out Bits(3 bits)

    val stateRegOut         = out Bits(2 bits)
  }

  // state constants
  val STATE_IDLE         = B"00"
  val STATE_WAITING_CPU  = B"01"
  val STATE_WAITING_DMA  = B"10"
  val STATE_WAITING_ANTIC = B"11"

  // registers
  val addrReg            = Reg(Bits(24 bits)) init B(0, 24 bits)
  val stateReg           = Reg(Bits(2 bits)) init STATE_IDLE
  val width8bitReg       = Reg(Bool()) init False
  val width16bitReg      = Reg(Bool()) init False
  val width32bitReg      = Reg(Bool()) init False
  val writeEnableReg     = Reg(Bool()) init False
  val writeEnableFreezerReg = Reg(Bool()) init False
  val dataWriteReg       = Reg(Bits(32 bits)) init B(0, 32 bits)
  val fetchWaitReg       = Reg(Bits(9 bits)) init B(0, 9 bits)
  val cpuFetchRealReg    = Reg(Bool()) init False
  val anticFetchRealReg  = Reg(Bool()) init False
  val pbiCycleReg        = Reg(Bool()) init False
  val lastBusReg         = Reg(Bits(8 bits)) init B(0, 8 bits)

  // next signals
  val addrNext            = Bits(24 bits)
  val stateNext           = Bits(2 bits)
  val width8bitNext       = Bool()
  val width16bitNext      = Bool()
  val width32bitNext      = Bool()
  val writeEnableNext     = Bool()
  val writeEnableFreezerNext = Bool()
  val dataWriteNext       = Bits(32 bits)
  val fetchWaitNext       = Bits(9 bits)
  val cpuFetchRealNext    = Bool()
  val anticFetchRealNext  = Bool()
  val pbiCycleNext        = Bool()
  val lastBusNext         = Bits(8 bits)

  // internal signals
  val requestComplete     = Bool()
  val notifyAntic         = Bool()
  val notifyDma           = Bool()
  val notifyCpu           = Bool()
  val startRequest        = Bool()
  val ramChipSelect       = Bool()
  val sdramChipSelect     = Bool()

  val extendedAccessAddr  = Bool()
  val extendedAccessCpuOrAntic = Bool()
  val extendedAccessAntic = Bool()
  val extendedAccessCpu   = Bool()
  val extendedAccessEither = Bool()
  val extendedSelfTest    = Bool()
  val extendedBank        = Bits(9 bits)
  val ramC000             = Bool()
  val hasRam              = Bool()
  val sdramOnlyBank       = Bool()

  val fetchPriority       = Bits(3 bits)

  // cart signals
  val emuCartEnable       = Bool()
  val emuCartCctlN        = Bool()
  val emuCartRw           = Bool()
  val emuCartS4N          = Bool()
  val emuCartS5N          = Bool()
  val emuCartAddress      = Bits(21 bits)
  val emuCartAddressEnable = Bool()
  val emuCartCctlDout     = Bits(8 bits)
  val emuCartCctlDoutEnable = Bool()
  val emuCartRd4          = Bool()
  val emuCartRd5          = Bool()

  // freezer signals
  val freezerDisableAtari = Bool()
  val freezerAccessType   = Bits(2 bits)
  val freezerAccessAddress = Bits(17 bits)
  val freezerDout         = Bits(8 bits)
  val freezerRequest      = Bool()
  val freezerRequestComplete = Bool()
  val freezerActivateN    = Bool()

  val memoryDataInt       = Bits(32 bits)

  // SDRAM address constants
  val sdramCartAddr       = Bits(23 bits)
  val sdramBasicRomAddr   = Bits(23 bits)
  val sdramOsRomAddr      = Bits(23 bits)
  val sdramFreezerRamAddr = Bits(23 bits)
  val sdramFreezerRomAddr = Bits(23 bits)

  val pbiTakeoverAdj      = Bool()

  val atariClkEnable      = Bool()

  // register update
  when(~io.resetN) {
    addrReg            := B(0, 24 bits)
    stateReg           := STATE_IDLE
    width8bitReg       := False
    width16bitReg      := False
    width32bitReg      := False
    writeEnableReg     := False
    writeEnableFreezerReg := False
    dataWriteReg       := B(0, 32 bits)
    fetchWaitReg       := B(0, 9 bits)
    cpuFetchRealReg    := False
    anticFetchRealReg  := False
    pbiCycleReg        := False
    lastBusReg         := B(0, 8 bits)
  } otherwise {
    addrReg            := addrNext
    stateReg           := stateNext
    width8bitReg       := width8bitNext
    width16bitReg      := width16bitNext
    width32bitReg      := width32bitNext
    writeEnableReg     := writeEnableNext
    writeEnableFreezerReg := writeEnableFreezerNext
    dataWriteReg       := dataWriteNext
    fetchWaitReg       := fetchWaitNext
    cpuFetchRealReg    := cpuFetchRealNext
    anticFetchRealReg  := anticFetchRealNext
    pbiCycleReg        := pbiCycleNext
    lastBusReg         := lastBusNext
  }

  atariClkEnable := notifyCpu | notifyAntic

  // float bus
  lastBusNext := lastBusReg
  when(atariClkEnable) {
    when(writeEnableNext) {
      lastBusNext := dataWriteNext(7 downto 0)
    } otherwise {
      lastBusNext := memoryDataInt(7 downto 0)
    }
  }

  // emulated cart
  val emuCart = new CartLogic()
  emuCart.io.clkEnable  := atariClkEnable
  emuCart.io.cartMode   := io.cartSelect
  emuCart.io.a          := addrNext(12 downto 0)
  emuCart.io.cctlN      := emuCartCctlN
  emuCart.io.dIn        := dataWriteNext(7 downto 0)
  emuCart.io.rw         := emuCartRw
  emuCart.io.s4N        := emuCartS4N
  emuCart.io.s5N        := emuCartS5N
  emuCartAddress        := emuCart.io.cartAddress
  emuCartAddressEnable  := emuCart.io.cartAddressEnable
  emuCartCctlDout       := emuCart.io.cctlDout
  emuCartCctlDoutEnable := emuCart.io.cctlDoutEnable
  emuCartRd4            := emuCart.io.rd4
  emuCartRd5            := emuCart.io.rd5

  emuCartEnable := (io.cartSelect =/= B"000000")

  // freezer
  freezerActivateN := ~(io.freezerEnable & io.freezerActivate)
  val freezer = new FreezerLogic()
  freezer.io.clkEnable  := atariClkEnable
  freezer.io.cpuCycle   := notifyCpu
  freezer.io.a          := addrNext(15 downto 0)
  freezer.io.dIn        := dataWriteNext(7 downto 0)
  freezer.io.rw         := ~writeEnableFreezerNext
  freezer.io.resetN     := io.resetN
  freezer.io.activateN  := freezerActivateN
  freezer.io.dualpokeyN := False
  freezerDisableAtari   := freezer.io.disableAtari
  freezerAccessType     := freezer.io.accessType
  freezerAccessAddress  := freezer.io.accessAddress
  freezerDout           := freezer.io.dOut
  freezer.io.request    := freezerRequest
  freezerRequestComplete := freezer.io.requestComplete
  io.freezerStateOut    := freezer.io.stateOut

  io.cartTrig3Out := io.cartRd5 | emuCartRd5

  // pbi takeover adjusted for freezer
  pbiTakeoverAdj := Mux(~io.freezerEnable | ~freezerDisableAtari, io.pbiTakeover, False)

  // state machine
  fetchPriority := io.anticFetch ## io.dmaFetch ## io.cpuFetch

  startRequest        := False
  io.pbiRequest       := False
  notifyAntic         := False
  notifyCpu           := False
  notifyDma           := False
  stateNext           := stateReg
  fetchWaitNext       := B(fetchWaitReg.asUInt + 1)
  pbiCycleNext        := pbiCycleReg
  addrNext            := addrReg
  dataWriteNext       := dataWriteReg
  width8bitNext       := width8bitReg
  width16bitNext      := width16bitReg
  width32bitNext      := width32bitReg
  writeEnableNext     := writeEnableReg
  writeEnableFreezerNext := writeEnableFreezerReg
  io.pbiWrEnable      := False
  anticFetchRealNext  := anticFetchRealReg
  cpuFetchRealNext    := cpuFetchRealReg

  switch(stateReg) {
    is(STATE_IDLE) {
      fetchWaitNext       := B(0, 9 bits)
      writeEnableNext     := False
      writeEnableFreezerNext := False
      width8bitNext       := False
      width16bitNext      := False
      width32bitNext      := False
      dataWriteNext       := B(0, 32 bits)
      addrNext            := io.dmaAddr(23 downto 16) ## io.cpuAddr

      switch(fetchPriority) {
        is(B"100", B"101", B"110", B"111") { // antic wins
          startRequest    := ~pbiTakeoverAdj
          io.pbiRequest   := pbiTakeoverAdj
          pbiCycleNext    := pbiTakeoverAdj
          addrNext        := B(0, 8 bits) ## io.anticAddr
          width8bitNext   := True
          when(~pbiTakeoverAdj & requestComplete) {
            notifyAntic   := True
          } otherwise {
            stateNext     := STATE_WAITING_ANTIC
          }
          anticFetchRealNext := True
          cpuFetchRealNext   := False
        }
        is(B"010", B"011") { // DMA wins
          startRequest    := True
          addrNext        := io.dmaAddr
          dataWriteNext   := io.dmaWriteData
          width8bitNext   := io.dma8bitWriteEnable | (io.dmaReadEnable & (io.dmaAddr(0) | io.dmaAddr(1)))
          width16bitNext  := io.dma16bitWriteEnable
          width32bitNext  := io.dma32bitWriteEnable | (io.dmaReadEnable & ~(io.dmaAddr(0) | io.dmaAddr(1)))
          writeEnableNext := ~io.dmaReadEnable
          writeEnableFreezerNext := ~io.dmaReadEnable
          when(requestComplete) {
            notifyDma     := True
          } otherwise {
            stateNext     := STATE_WAITING_DMA
          }
        }
        is(B"001") { // 6502 wins
          startRequest    := ~pbiTakeoverAdj
          io.pbiRequest   := pbiTakeoverAdj
          pbiCycleNext    := pbiTakeoverAdj
          addrNext        := B(0, 8 bits) ## io.cpuAddr
          dataWriteNext(7 downto 0) := io.cpuWriteData
          width8bitNext   := True
          writeEnableNext := ~io.cpuWriteN & ~pbiTakeoverAdj
          writeEnableFreezerNext := ~io.cpuWriteN
          io.pbiWrEnable  := ~io.cpuWriteN & pbiTakeoverAdj
          when(~pbiTakeoverAdj & requestComplete) {
            notifyCpu     := True
          } otherwise {
            stateNext     := STATE_WAITING_CPU
          }
          cpuFetchRealNext   := True
          anticFetchRealNext := False
        }
        is(B"000") {
          // no requests
        }
      }
    }
    is(STATE_WAITING_ANTIC) {
      notifyAntic := requestComplete
      when(io.pbiRelease | requestComplete) {
        stateNext    := STATE_IDLE
        pbiCycleNext := False
      }
    }
    is(STATE_WAITING_DMA) {
      notifyDma := requestComplete
      when(requestComplete) {
        stateNext := STATE_IDLE
      }
    }
    is(STATE_WAITING_CPU) {
      notifyCpu := requestComplete
      when(io.pbiRelease | requestComplete) {
        stateNext    := STATE_IDLE
        pbiCycleNext := False
      }
    }
  }

  // outputs
  io.memoryReadyAntic := notifyAntic
  io.memoryReadyDma   := notifyDma
  io.memoryReadyCpu   := notifyCpu
  io.ramRequest       := ramChipSelect
  io.sdramRequest     := sdramChipSelect
  io.sdramReadEn      := ~writeEnableNext
  io.width8bitAccess  := width8bitNext
  io.width16bitAccess := width16bitNext
  io.width32bitAccess := width32bitNext
  io.writeData        := dataWriteNext

  // extended access logic
  extendedAccessAddr     := addrNext(14) & ~addrNext(15)
  extendedAccessAntic    := extendedAccessAddr & anticFetchRealNext & ~io.portb(5)
  extendedAccessCpu      := extendedAccessAddr & cpuFetchRealNext & ~io.portb(4)
  extendedAccessCpuOrAntic := extendedAccessAntic | extendedAccessCpu
  extendedAccessEither   := extendedAccessAddr & ~io.portb(4)

  // sdram_only_bank computation
  if (INTERNAL_RAM == 0) {
    sdramOnlyBank := True
  } else {
    val sdramStartBank = Math.max(0, Math.ceil(Math.log(INTERNAL_RAM.toDouble) / Math.log(2.0) - 14.0).toInt)
    sdramOnlyBank := extendedBank(8 downto sdramStartBank).orR
  }

  // extended bank computation
  extendedBank   := B"0000000" ## addrNext(15 downto 14)
  extendedSelfTest := True
  ramC000        := False
  hasRam         := True

  when(io.atari800mode) {
    extendedSelfTest := False
    ramC000 := True
    switch(io.ramSelect) {
      is(B"000") { // 8k
        hasRam := ~(addrNext(15) | addrNext(14) | addrNext(13))
      }
      is(B"001") { // 16k
        hasRam := ~(addrNext(15) | addrNext(14))
      }
      is(B"010") { // 32k
        hasRam := ~addrNext(15)
      }
      is(B"011") { // 48k
        hasRam := ~addrNext(15) | ~addrNext(14)
      }
      is(B"100") { // 52k
        // yes we have 64k here, but its hidden
      }
    }
  } otherwise {
    ramC000 := ~io.portb(0)
    switch(io.ramSelect) {
      is(B"000") {
        // default 64k
      }
      is(B"001") { // 128k
        when(extendedAccessCpuOrAntic) {
          extendedBank(2 downto 0) := True ## io.portb(3 downto 2)
        }
      }
      is(B"010") { // 320k compy shop
        when(extendedAccessCpuOrAntic) {
          extendedBank(4 downto 0) := True ## io.portb(7 downto 6) ## io.portb(3 downto 2)
          extendedSelfTest := False
        }
      }
      is(B"011") { // 320k rambo
        when(extendedAccessEither) {
          extendedBank(4 downto 0) := True ## io.portb(6 downto 5) ## io.portb(3 downto 2)
        }
      }
      is(B"100") { // 576k compy shop
        when(extendedAccessCpuOrAntic) {
          extendedBank(5 downto 0) := True ## io.portb(7 downto 6) ## io.portb(3 downto 1)
          extendedSelfTest := False
        }
      }
      is(B"101") { // 576k rambo
        when(extendedAccessEither) {
          extendedBank(5 downto 0) := True ## io.portb(6 downto 5) ## io.portb(3 downto 1)
        }
      }
      is(B"110") { // 1088k rambo
        when(extendedAccessEither) {
          extendedBank(5 downto 0) := io.portb(7 downto 5) ## io.portb(3 downto 1)
          extendedBank(6) := ~(io.portb(7 downto 5) ## io.portb(3)).orR
          extendedSelfTest := False
        }
      }
      is(B"111") { // 4MB
        when(extendedAccessAddr) {
          extendedBank(7 downto 0) := io.portb
          extendedBank(8) := ~io.portb(7 downto 2).orR
          extendedSelfTest := io.portb(6 downto 4).andR
        }
      }
    }
  }

  // SDRAM memory map
  if (LOW_MEMORY == 0) {
    sdramFreezerRamAddr := B"100100" ## freezerAccessAddress
    sdramFreezerRomAddr := B"1001010" ## freezerAccessAddress(15 downto 0)
    sdramCartAddr       := B(1, 1 bits) ## emuCartAddress(20) ## ~emuCartAddress(20) ## emuCartAddress(19 downto 0)
    sdramBasicRomAddr   := B"111" ## B"000000" ## B(0, 14 bits)
    sdramOsRomAddr      := B"111" ## B"000001" ## B(0, 14 bits)
  } else if (LOW_MEMORY == 1) {
    sdramCartAddr       := B"0000" ## B(1, 1 bits) ## emuCartAddress(17 downto 0)
    sdramBasicRomAddr   := B"000" ## B"01101000000000000000"
    sdramOsRomAddr      := B"000" ## B"01101100000000000000"
    sdramFreezerRamAddr := B"000" ## B"001" ## freezerAccessAddress
    sdramFreezerRomAddr := B"000" ## B"0111" ## freezerAccessAddress(15 downto 0)
  } else { // LOW_MEMORY == 2
    sdramCartAddr       := B"0000" ## B"01" ## emuCartAddress(16 downto 0)
    sdramBasicRomAddr   := B"0000" ## B"0111000000000000000"
    sdramOsRomAddr      := B"0000" ## B"0111100000000000000"
    sdramFreezerRamAddr := B(0, 23 bits) // not defined for low_memory=2
    sdramFreezerRomAddr := B(0, 23 bits) // not defined for low_memory=2
  }

  // main address decode process
  memoryDataInt     := B"11111111111111111111111111111111"
  io.romAddr        := B(0, 22 bits)
  io.ramAddr        := addrNext(18 downto 0)
  io.sdramAddr      := addrNext(22 downto 0)
  io.pbiAddr        := addrNext(15 downto 0)
  requestComplete   := False
  io.gtiaWrEnable   := False
  io.pokeyWrEnable  := False
  io.anticWrEnable  := False
  io.piaWrEnable    := False
  io.piaRdEnable    := False
  io.d6WrEnable     := False
  io.romWrEnable    := False
  io.ramWrEnable    := writeEnableNext
  io.sdramWriteEn   := writeEnableNext
  emuCartS4N        := True
  emuCartS5N        := True
  emuCartCctlN      := True
  emuCartRw         := ~writeEnableNext
  io.romRequest     := False
  freezerRequest    := False
  ramChipSelect     := False
  sdramChipSelect   := False

  when(~addrNext(23 downto 18).orR) {
    // Atari address space (bits 23:18 == 0)
    io.sdramAddr(13 downto 0) := addrNext(13 downto 0)
    io.sdramAddr(22 downto 14) := extendedBank
    io.ramAddr(13 downto 0) := addrNext(13 downto 0)
    io.ramAddr(18 downto 14) := extendedBank(4 downto 0)

    when(hasRam) {
      when(sdramOnlyBank) {
        memoryDataInt(7 downto 0) := io.sdramData(7 downto 0)
        sdramChipSelect := startRequest
        requestComplete := io.sdramRequestComplete
      } otherwise {
        memoryDataInt(7 downto 0) := io.ramData(7 downto 0)
        ramChipSelect := startRequest
        requestComplete := io.ramRequestComplete
      }
    } otherwise {
      memoryDataInt(7 downto 0) := lastBusReg
      requestComplete := True
    }

    if (SYSTEM == 0) {
      switch(addrNext(15 downto 8)) {
        // GTIA
        is(B"xD0") {
          io.gtiaWrEnable := writeEnableNext
          memoryDataInt(7 downto 0) := io.gtiaData
          memoryDataInt(15 downto 8) := io.cacheGtiaData
          requestComplete := True
          sdramChipSelect := False
          ramChipSelect := False
        }
        // D1xx - float bus
        is(B"xD1") {
          sdramChipSelect := False
          ramChipSelect := False
          requestComplete := True
          memoryDataInt(7 downto 0) := lastBusReg
        }
        // POKEY
        is(B"xD2") {
          io.pokeyWrEnable := writeEnableNext
          memoryDataInt(7 downto 0) := io.pokeyData
          memoryDataInt(15 downto 8) := io.cachePokeyData
          requestComplete := True
          sdramChipSelect := False
          ramChipSelect := False
        }
        // PIA
        is(B"xD3") {
          io.piaWrEnable := writeEnableNext
          io.piaRdEnable := True
          memoryDataInt(7 downto 0) := io.piaData
          requestComplete := True
          sdramChipSelect := False
          ramChipSelect := False
        }
        // ANTIC
        is(B"xD4") {
          io.anticWrEnable := writeEnableNext
          memoryDataInt(7 downto 0) := io.anticData
          memoryDataInt(15 downto 8) := io.cacheAnticData
          requestComplete := True
          sdramChipSelect := False
          ramChipSelect := False
        }
        // CART CONFIG D5xx
        is(B"xD5") {
          sdramChipSelect := False
          ramChipSelect := False
          requestComplete := True
          memoryDataInt(7 downto 0) := lastBusReg

          when(emuCartEnable) {
            emuCartCctlN := False
            when(writeEnableNext) {
              requestComplete := True
            } otherwise {
              when(emuCartCctlDoutEnable) {
                memoryDataInt(7 downto 0) := emuCartCctlDout
              } otherwise {
                memoryDataInt(7 downto 0) := B"xFF"
              }
              requestComplete := True
            }
          }
        }
        // D6xx
        is(B"xD6") {
          io.d6WrEnable := writeEnableNext
          memoryDataInt(7 downto 0) := lastBusReg
          sdramChipSelect := False
          ramChipSelect := False
          requestComplete := True
        }
        // SELF TEST ROM 0x5000-0x57ff
        is(B"x50", B"x51", B"x52", B"x53", B"x54", B"x55", B"x56", B"x57") {
          when(~io.portb(7) & io.portb(0) & extendedSelfTest) {
            sdramChipSelect := False
            ramChipSelect := False
            when(io.romInRam) {
              memoryDataInt(7 downto 0) := io.sdramData(7 downto 0)
            } otherwise {
              memoryDataInt(7 downto 0) := io.romData
            }
            when(writeEnableNext) {
              requestComplete := True
            } otherwise {
              when(io.romInRam) {
                requestComplete := io.sdramRequestComplete
                sdramChipSelect := startRequest
              } otherwise {
                requestComplete := io.romRequestComplete
                io.romRequest := startRequest
              }
            }
            io.sdramAddr := sdramOsRomAddr
            io.sdramAddr(13 downto 0) := B"010" ## addrNext(10 downto 0)
            io.romAddr := B"000000" ## B"00" ## B"010" ## addrNext(10 downto 0)
          }
        }
        // 0x80 cart (8xxx-9xxx)
        is(B"x80", B"x81", B"x82", B"x83", B"x84", B"x85", B"x86", B"x87",
           B"x88", B"x89", B"x8A", B"x8B", B"x8C", B"x8D", B"x8E", B"x8F",
           B"x90", B"x91", B"x92", B"x93", B"x94", B"x95", B"x96", B"x97",
           B"x98", B"x99", B"x9A", B"x9B", B"x9C", B"x9D", B"x9E", B"x9F") {
          when(emuCartEnable & emuCartRd4) {
            emuCartS4N := False
            when(emuCartAddressEnable) {
              io.sdramAddr := sdramCartAddr
              memoryDataInt(7 downto 0) := io.sdramData(7 downto 0)
              requestComplete := io.sdramRequestComplete
              sdramChipSelect := startRequest
              ramChipSelect := False
            } otherwise {
              memoryDataInt(7 downto 0) := B"xFF"
              requestComplete := True
              sdramChipSelect := False
              ramChipSelect := False
            }
          }
        }
        // 0xa0 cart / BASIC ROM (Axxx-Bxxx)
        is(B"xA0", B"xA1", B"xA2", B"xA3", B"xA4", B"xA5", B"xA6", B"xA7",
           B"xA8", B"xA9", B"xAA", B"xAB", B"xAC", B"xAD", B"xAE", B"xAF",
           B"xB0", B"xB1", B"xB2", B"xB3", B"xB4", B"xB5", B"xB6", B"xB7",
           B"xB8", B"xB9", B"xBA", B"xBB", B"xBC", B"xBD", B"xBE", B"xBF") {
          when(emuCartEnable & emuCartRd5) {
            emuCartS5N := False
            when(emuCartAddressEnable) {
              io.sdramAddr := sdramCartAddr
              memoryDataInt(7 downto 0) := io.sdramData(7 downto 0)
              requestComplete := io.sdramRequestComplete
              sdramChipSelect := startRequest
              ramChipSelect := False
            } otherwise {
              memoryDataInt(7 downto 0) := B"xFF"
              requestComplete := True
              sdramChipSelect := False
              ramChipSelect := False
            }
          } otherwise {
            when(~io.atari800mode & ~io.portb(1)) {
              sdramChipSelect := False
              ramChipSelect := False
              when(io.romInRam) {
                memoryDataInt(7 downto 0) := io.sdramData(7 downto 0)
              } otherwise {
                memoryDataInt(7 downto 0) := io.romData
              }
              when(writeEnableNext) {
                requestComplete := True
              } otherwise {
                when(io.romInRam) {
                  requestComplete := io.sdramRequestComplete
                  sdramChipSelect := startRequest
                } otherwise {
                  requestComplete := io.romRequestComplete
                  io.romRequest := startRequest
                }
              }
              io.romAddr := B"000000" ## B"110" ## addrNext(12 downto 0)
              io.sdramAddr := sdramBasicRomAddr
              io.sdramAddr(12 downto 0) := addrNext(12 downto 0)
            }
          }
        }
        // OS ROM D8xx-DFxx (math pack)
        is(B"xD8", B"xD9", B"xDA", B"xDB", B"xDC", B"xDD", B"xDE", B"xDF") {
          when(io.atari800mode | (io.portb(0) & io.pbiMpdN)) {
            sdramChipSelect := False
            ramChipSelect := False
            when(io.romInRam) {
              memoryDataInt(7 downto 0) := io.sdramData(7 downto 0)
            } otherwise {
              memoryDataInt(7 downto 0) := io.romData
            }
            when(writeEnableNext) {
              requestComplete := True
            } otherwise {
              when(io.romInRam) {
                requestComplete := io.sdramRequestComplete
                sdramChipSelect := startRequest
              } otherwise {
                requestComplete := io.romRequestComplete
                io.romRequest := startRequest
              }
            }
            io.romAddr := B"000000" ## B"00" ## addrNext(13 downto 0)
            io.sdramAddr := sdramOsRomAddr
            io.sdramAddr(13 downto 0) := addrNext(13 downto 0)
          }
        }
        // OS ROM C0xx-CFxx
        is(B"xC0", B"xC1", B"xC2", B"xC3", B"xC4", B"xC5", B"xC6", B"xC7",
           B"xC8", B"xC9", B"xCA", B"xCB", B"xCC", B"xCD", B"xCE", B"xCF") {
          when(~ramC000) {
            sdramChipSelect := False
            ramChipSelect := False
            when(io.romInRam) {
              memoryDataInt(7 downto 0) := io.sdramData(7 downto 0)
            } otherwise {
              memoryDataInt(7 downto 0) := io.romData
            }
            when(writeEnableNext) {
              requestComplete := True
            } otherwise {
              when(io.romInRam) {
                requestComplete := io.sdramRequestComplete
                sdramChipSelect := startRequest
              } otherwise {
                requestComplete := io.romRequestComplete
                io.romRequest := startRequest
              }
            }
            io.romAddr := B"000000" ## B"00" ## addrNext(13 downto 0)
            io.sdramAddr := sdramOsRomAddr
            io.sdramAddr(13 downto 0) := addrNext(13 downto 0)
          }
        }
        // OS ROM E0xx-FFxx
        is(B"xE0", B"xE1", B"xE2", B"xE3", B"xE4", B"xE5", B"xE6", B"xE7",
           B"xE8", B"xE9", B"xEA", B"xEB", B"xEC", B"xED", B"xEE", B"xEF",
           B"xF0", B"xF1", B"xF2", B"xF3", B"xF4", B"xF5", B"xF6", B"xF7",
           B"xF8", B"xF9", B"xFA", B"xFB", B"xFC", B"xFD", B"xFE", B"xFF") {
          when(io.atari800mode | io.portb(0)) {
            sdramChipSelect := False
            ramChipSelect := False
            when(io.romInRam) {
              memoryDataInt(7 downto 0) := io.sdramData(7 downto 0)
            } otherwise {
              memoryDataInt(7 downto 0) := io.romData
            }
            when(writeEnableNext) {
              requestComplete := True
            } otherwise {
              when(io.romInRam) {
                requestComplete := io.sdramRequestComplete
                sdramChipSelect := startRequest
              } otherwise {
                requestComplete := io.romRequestComplete
                io.romRequest := startRequest
              }
            }
            io.romAddr := B"000000" ## B"00" ## addrNext(13 downto 0)
            io.sdramAddr := sdramOsRomAddr
            io.sdramAddr(13 downto 0) := addrNext(13 downto 0)
          }
        }
        default {
          // nop
        }
      }

      // PBI overrides bus
      when(pbiCycleReg) {
        memoryDataInt(7 downto 0) := io.pbiData
        requestComplete := io.pbiRequestComplete
      }

      // Freezer overrides bus
      when(io.freezerEnable & freezerDisableAtari) {
        sdramChipSelect := False
        ramChipSelect := False
        switch(freezerAccessType) {
          is(B"01") { // direct data access
            memoryDataInt(7 downto 0) := freezerDout
            freezerRequest := startRequest
            requestComplete := freezerRequestComplete
          }
          is(B"10") { // freezer ram
            io.sdramAddr := sdramFreezerRamAddr
            memoryDataInt(7 downto 0) := io.sdramData(7 downto 0)
            requestComplete := io.sdramRequestComplete
            sdramChipSelect := startRequest
          }
          is(B"11") { // freezer rom
            io.sdramAddr := sdramFreezerRomAddr
            memoryDataInt(7 downto 0) := io.sdramData(7 downto 0)
            requestComplete := io.sdramRequestComplete
            sdramChipSelect := startRequest
          }
          is(B"00") {
            memoryDataInt(7 downto 0) := B"xFF"
            requestComplete := True
          }
        }
      }
    }

    if (SYSTEM == 10) {
      switch(addrNext(15 downto 8)) {
        // GTIA C0xx-CFxx
        is(B"xC0", B"xC1", B"xC2", B"xC3", B"xC4", B"xC5", B"xC6", B"xC7",
           B"xC8", B"xC9", B"xCA", B"xCB", B"xCC", B"xCD", B"xCE", B"xCF") {
          io.gtiaWrEnable := writeEnableNext
          memoryDataInt(7 downto 0) := io.gtiaData
          memoryDataInt(15 downto 8) := io.cacheGtiaData
          requestComplete := True
          sdramChipSelect := False
          ramChipSelect := False
        }
        // POKEY E8xx-EFxx
        is(B"xE8", B"xE9", B"xEA", B"xEB", B"xEC", B"xED", B"xEE", B"xEF") {
          io.pokeyWrEnable := writeEnableNext
          memoryDataInt(7 downto 0) := io.pokeyData
          memoryDataInt(15 downto 8) := io.cachePokeyData
          requestComplete := True
          sdramChipSelect := False
          ramChipSelect := False
        }
        // ANTIC D4xx
        is(B"xD4") {
          io.anticWrEnable := writeEnableNext
          memoryDataInt(7 downto 0) := io.anticData
          memoryDataInt(15 downto 8) := io.cacheAnticData
          requestComplete := True
          sdramChipSelect := False
          ramChipSelect := False
        }
        // CART 40xx-7Fxx
        is(B"x40", B"x41", B"x42", B"x43", B"x44", B"x45", B"x46", B"x47",
           B"x48", B"x49", B"x4A", B"x4B", B"x4C", B"x4D", B"x4E", B"x4F",
           B"x50", B"x51", B"x52", B"x53", B"x54", B"x55", B"x56", B"x57",
           B"x58", B"x59", B"x5A", B"x5B", B"x5C", B"x5D", B"x5E", B"x5F",
           B"x60", B"x61", B"x62", B"x63", B"x64", B"x65", B"x66", B"x67",
           B"x68", B"x69", B"x6A", B"x6B", B"x6C", B"x6D", B"x6E", B"x6F",
           B"x70", B"x71", B"x72", B"x73", B"x74", B"x75", B"x76", B"x77",
           B"x78", B"x79", B"x7A", B"x7B", B"x7C", B"x7D", B"x7E", B"x7F") {
          when(writeEnableNext) {
            sdramChipSelect := False
            ramChipSelect := False
            memoryDataInt(7 downto 0) := B"xFF"
            requestComplete := True
          }
        }
        // CART 80xx-BFxx
        is(B"x80", B"x81", B"x82", B"x83", B"x84", B"x85", B"x86", B"x87",
           B"x88", B"x89", B"x8A", B"x8B", B"x8C", B"x8D", B"x8E", B"x8F",
           B"x90", B"x91", B"x92", B"x93", B"x94", B"x95", B"x96", B"x97",
           B"x98", B"x99", B"x9A", B"x9B", B"x9C", B"x9D", B"x9E", B"x9F",
           B"xA0", B"xA1", B"xA2", B"xA3", B"xA4", B"xA5", B"xA6", B"xA7",
           B"xA8", B"xA9", B"xAA", B"xAB", B"xAC", B"xAD", B"xAE", B"xAF",
           B"xB0", B"xB1", B"xB2", B"xB3", B"xB4", B"xB5", B"xB6", B"xB7",
           B"xB8", B"xB9", B"xBA", B"xBB", B"xBC", B"xBD", B"xBE", B"xBF") {
          when(writeEnableNext) {
            sdramChipSelect := False
            ramChipSelect := False
            memoryDataInt(7 downto 0) := B"xFF"
            requestComplete := True
          }
        }
        // OS ROM F0xx-FFxx
        is(B"xF0", B"xF1", B"xF2", B"xF3", B"xF4", B"xF5", B"xF6", B"xF7",
           B"xF8", B"xF9", B"xFA", B"xFB", B"xFC", B"xFD", B"xFE", B"xFF") {
          sdramChipSelect := False
          ramChipSelect := False
          when(io.romInRam) {
            memoryDataInt(7 downto 0) := io.sdramData(7 downto 0)
          } otherwise {
            memoryDataInt(7 downto 0) := io.romData
          }
          when(writeEnableNext) {
            requestComplete := True
          } otherwise {
            when(io.romInRam) {
              requestComplete := io.sdramRequestComplete
              sdramChipSelect := startRequest
            } otherwise {
              requestComplete := io.romRequestComplete
              io.romRequest := startRequest
            }
          }
          io.romAddr := B"000000" ## B"0000" ## addrNext(11 downto 0)
          io.sdramAddr := sdramOsRomAddr
          io.sdramAddr(11 downto 0) := addrNext(11 downto 0)
        }
        // misc D0-D3, D5-E7 -> 0x00
        is(B"xD0", B"xD1", B"xD2", B"xD3",
           B"xD5", B"xD6", B"xD7", B"xD8", B"xD9", B"xDA", B"xDB", B"xDC", B"xDD", B"xDE", B"xDF",
           B"xE0", B"xE1", B"xE2", B"xE3", B"xE4", B"xE5", B"xE6", B"xE7") {
          memoryDataInt(7 downto 0) := B"x00"
          requestComplete := True
        }
        default {
          // nop
        }
      }
    }
  } otherwise {
    // DMA / external access (addr_next(23:18) != 0)
    sdramChipSelect := False
    ramChipSelect := False
    switch(addrNext(23 downto 21)) {
      is(B"000") {
        // internal area for zpu, never happens
      }
      is(B"001") { // sram 512K
        memoryDataInt(15 downto 0) := io.ramData
        ramChipSelect := startRequest
        requestComplete := io.ramRequestComplete
        io.ramAddr := addrNext(18 downto 0)
      }
      is(B"010", B"011") { // flash rom 4MB
        requestComplete := io.romRequestComplete
        memoryDataInt(7 downto 0) := io.romData
        io.romRequest := startRequest
        io.romAddr := addrNext(21 downto 0)
        io.romWrEnable := writeEnableNext
      }
      is(B"100", B"101", B"110", B"111") { // sdram 8MB
        memoryDataInt := io.sdramData
        sdramChipSelect := startRequest
        requestComplete := io.sdramRequestComplete
        io.sdramAddr := addrNext(22 downto 0)
      }
    }
  }

  io.stateRegOut := stateReg
  io.memoryData := memoryDataInt
}
