package atari800

import spinal.core._

class Pbi6502 extends Component {
  val io = new Bundle {
    // FPGA side
    val enable179Early = in  Bool()
    val request        = in  Bool()
    val addrIn         = in  Bits(16 bits)
    val dataIn         = in  Bits(8 bits)
    val writeIn        = in  Bool()
    val portb          = in  Bits(8 bits)
    val disable        = in  Bool()
    val anticRefresh   = in  Bool()

    val snoopDataIn    = in  Bits(8 bits)
    val snoopDataReady = in  Bool()

    val takeover       = out Bool()
    val release        = out Bool()
    val externalAccess = out Bool()
    val dataOut        = out Bits(8 bits)
    val complete       = out Bool()
    val mpdN           = out Bool()

    val debug      = out Bits(25 bits)
    val debugReady = out Bool()

    // 6502 side
    val busDataIn   = in  Bits(8 bits)
    val busPhi1     = out Bool()
    val busPhi2     = out Bool()
    val busAddrOut  = out Bits(16 bits)
    val busAddrOe   = out Bool()
    val busDataOut  = out Bits(8 bits)
    val busDataOe   = out Bool()
    val busWriteN   = out Bool()

    val busS4N      = out Bool()
    val busS5N      = out Bool()
    val busCctlN    = out Bool()
    val busD1xxN    = out Bool()

    val busRefreshOe  = out Bool()
    val busControlOe  = out Bool()
    val busCasinhN    = out Bool()
    val busCasinhOe   = out Bool()
    val busCasN       = out Bool()
    val busRasN       = out Bool()

    val busRd4      = in  Bool()
    val busRd5      = in  Bool()
    val pbiMpdN     = in  Bool()
    val pbiRefN     = in  Bool()
    val pbiExtselN  = in  Bool()
  }

  // registers
  val mpdNReg            = Reg(Bool()) init True
  val requestReg         = Reg(Bool()) init False
  val stateReg           = Reg(Bits(5 bits)) init B(0, 5 bits)
  val addrReg            = Reg(Bits(16 bits)) init B(0, 16 bits)
  val addrOeReg          = Reg(Bool()) init False
  val dataReg            = Reg(Bits(8 bits)) init B(0, 8 bits)
  val outputDataReg      = Reg(Bits(8 bits)) init B(0, 8 bits)
  val snoopDataReg       = Reg(Bits(8 bits)) init B(0, 8 bits)
  val dataOeReg          = Reg(Bool()) init False
  val dataReadReg        = Reg(Bits(8 bits)) init B(0, 8 bits)
  val phi1Reg            = Reg(Bool()) init True
  val phi2Reg            = Reg(Bool()) init False
  val writeNReg          = Reg(Bool()) init True
  val controlOeReg       = Reg(Bool()) init False
  val refreshReg         = Reg(Bool()) init False
  val refreshOeReg       = Reg(Bool()) init False
  val refreshCountReg    = Reg(Bits(8 bits)) init B(0, 8 bits)
  val anticRefreshReg    = Reg(Bool()) init False
  val rasNReg            = Reg(Bool()) init False
  val casNReg            = Reg(Bool()) init False
  val casinhNReg         = Reg(Bool()) init True
  val casinhOeReg        = Reg(Bool()) init False
  val addrStableReg      = Reg(Bool()) init False
  val externalReadReg    = Reg(Bool()) init False
  val externalWriteOnlyReg = Reg(Bool()) init False
  val takeoverReg        = Reg(Bool()) init True
  val releaseReg         = Reg(Bool()) init False
  val completeReg        = Reg(Bool()) init False
  val debugReg           = Reg(Bits(25 bits)) init B(0, 25 bits)

  // next signals
  val mpdNNext            = Bool()
  val requestNext         = Bool()
  val stateNext           = Bits(5 bits)
  val addrNext            = Bits(16 bits)
  val addrOeNext          = Bool()
  val dataNext            = Bits(8 bits)
  val outputDataNext      = Bits(8 bits)
  val snoopDataNext       = Bits(8 bits)
  val dataOeNext          = Bool()
  val dataReadNext        = Bits(8 bits)
  val phi1Next            = Bool()
  val phi2Next            = Bool()
  val writeNNext          = Bool()
  val controlOeNext       = Bool()
  val refreshNext         = Bool()
  val refreshOeNext       = Bool()
  val refreshCountNext    = Bits(8 bits)
  val rasNNext            = Bool()
  val casNNext            = Bool()
  val casinhNNext         = Bool()
  val casinhOeNext        = Bool()
  val addrStableNext      = Bool()
  val externalReadNext    = Bool()
  val externalWriteOnlyNext = Bool()
  val takeoverNext        = Bool()
  val releaseNext         = Bool()
  val completeNext        = Bool()
  val debugNext           = Bits(25 bits)

  val clearRequest = Bool()
  val clearSnoop   = Bool()
  val incrementRefreshCount = Bool()

  // register update
  mpdNReg            := mpdNNext
  requestReg         := requestNext
  stateReg           := stateNext
  addrReg            := addrNext
  addrOeReg          := addrOeNext
  dataReg            := dataNext
  outputDataReg      := outputDataNext
  snoopDataReg       := snoopDataNext
  dataOeReg          := dataOeNext
  dataReadReg        := dataReadNext
  phi1Reg            := phi1Next
  phi2Reg            := phi2Next
  writeNReg          := writeNNext
  controlOeReg       := controlOeNext
  refreshReg         := refreshNext
  refreshOeReg       := refreshOeNext
  refreshCountReg    := refreshCountNext
  anticRefreshReg    := io.anticRefresh
  rasNReg            := rasNNext
  casNReg            := casNNext
  casinhNReg         := casinhNNext
  casinhOeReg        := casinhOeNext
  addrStableReg      := addrStableNext
  externalReadReg    := externalReadNext
  externalWriteOnlyReg := externalWriteOnlyNext
  takeoverReg        := takeoverNext
  releaseReg         := releaseNext
  completeReg        := completeNext
  debugReg           := debugNext

  // MMU instance
  val mmu1 = new Mmu()
  mmu1.io.addr  := addrReg(15 downto 11)
  mmu1.io.refN  := io.pbiRefN
  mmu1.io.rd4   := io.busRd4
  mmu1.io.rd5   := io.busRd5
  mmu1.io.mpdN  := io.pbiMpdN
  mmu1.io.ren   := io.portb(0)
  mmu1.io.beN   := io.portb(1)
  mmu1.io.mapN  := io.portb(7)

  val mmuS4N    = mmu1.io.s4N
  val mmuS5N    = mmu1.io.s5N
  val mmuBasic  = mmu1.io.basic
  val mmuIO     = mmu1.io.ioOut
  val mmuOS     = mmu1.io.os
  val mmuCasinh = mmu1.io.ci

  // CCTL_N decode
  val mmuCctlN = Bool()
  mmuCctlN := True
  when(mmuIO & (addrReg(10 downto 8) === B"101")) {
    mmuCctlN := False
  }

  // D1XX_N decode
  val mmuD1xxN = Bool()
  mmuD1xxN := True
  when(mmuIO & (addrReg(10 downto 8) === B"001")) {
    mmuD1xxN := False
  }

  // EXTIO decode
  val mmuExtio = Bool()
  mmuExtio := False
  when(mmuIO & ((addrReg(9 downto 8) === B"01") | (addrReg(10 downto 9) === B"11"))) {
    mmuExtio := True
  }

  // snap the request
  addrNext    := addrReg
  dataNext    := dataReg
  writeNNext  := writeNReg
  requestNext := requestReg
  incrementRefreshCount := False
  refreshNext := refreshReg

  when(io.request & ~refreshReg) {
    addrNext    := io.addrIn
    dataNext    := io.dataIn
    writeNNext  := ~io.writeIn
    requestNext := True
  } elsewhen(io.anticRefresh & ~anticRefreshReg) {
    addrNext := B"xFF" ## refreshCountReg
    incrementRefreshCount := True
    writeNNext := True
    refreshNext := True
  } elsewhen(clearRequest) {
    addrNext    := B(0, 16 bits)
    dataNext    := B(0, 8 bits)
    writeNNext  := True
    requestNext := False
    refreshNext := False
  }

  // antic refresh counter
  refreshCountNext := refreshCountReg
  when(incrementRefreshCount) {
    refreshCountNext := B(refreshCountReg.asUInt + 1)
  }

  // snap the snoop
  snoopDataNext := snoopDataReg
  when(io.snoopDataReady) {
    snoopDataNext := io.snoopDataIn
  } elsewhen(clearSnoop) {
    snoopDataNext := B(0, 8 bits)
  }

  // mux for selecting bus data output
  outputDataNext := snoopDataReg
  when(~writeNReg) {
    outputDataNext := dataReg
  }

  // next state logic
  stateNext       := stateReg
  phi1Next        := phi1Reg
  phi2Next        := phi2Reg
  addrOeNext      := addrOeReg
  dataOeNext      := dataOeReg
  dataReadNext    := dataReadReg
  controlOeNext   := controlOeReg
  refreshOeNext   := refreshOeReg
  mpdNNext        := mpdNReg
  rasNNext        := rasNReg
  casNNext        := casNReg
  casinhNNext     := casinhNReg
  casinhOeNext    := casinhOeReg
  completeNext    := False
  releaseNext     := False
  takeoverNext    := takeoverReg
  clearRequest    := False
  clearSnoop      := False
  addrStableNext  := addrStableReg
  externalReadNext      := externalReadReg
  externalWriteOnlyNext := externalWriteOnlyReg
  debugNext       := debugReg
  io.debugReady   := False

  stateNext := B(stateReg.asUInt + 1)

  when(io.enable179Early) {
    stateNext := B(0, 5 bits) // re-sync
  }

  switch(stateReg) {
    is(B"0" ## B"x0") {
      rasNNext := True
      casNNext := True
    }
    is(B"0" ## B"x2") {
      addrOeNext   := ~io.disable
      refreshOeNext := refreshReg
      controlOeNext := ~io.disable
    }
    is(B"0" ## B"x5") {
      takeoverNext  := False
      addrStableNext := True
    }
    is(B"0" ## B"xA") {
      casinhNNext := ~mmuCasinh
      casinhOeNext := True
    }
    is(B"0" ## B"xB") {
      rasNNext := False
    }
    is(B"0" ## B"xC") {
      phi1Next := False
    }
    is(B"0" ## B"xD") {
      phi2Next := True
    }
    is(B"0" ## B"xF") {
      val externalReadTmp = writeNReg & ((~io.pbiExtselN & casinhNReg) | mmuExtio | ~io.pbiRefN | ~mmuS4N | ~mmuS5N) & ~io.disable
      externalReadNext := externalReadTmp

      val externalWriteOnlyTmp = ~writeNReg & ((~io.pbiExtselN & casinhNReg) | ~io.pbiRefN) & ~io.disable
      externalWriteOnlyNext := externalWriteOnlyTmp

      releaseNext  := ~externalReadTmp & ~externalWriteOnlyTmp & requestReg
      completeNext := externalWriteOnlyTmp
      mpdNNext     := io.pbiMpdN | io.disable
    }
    is(B"1" ## B"x0") {
      casNNext := ~writeNReg | ~casinhNReg
    }
    is(B"1" ## B"x3") {
      when(~externalReadReg | ~writeNReg) {
        dataOeNext := ~io.disable
      }
    }
    is(B"1" ## B"x5") {
      casNNext := ~casinhNReg
    }
    is(B"1" ## B"x7") {
      casinhOeNext := False
    }
    is(B"1" ## B"xC") {
      completeNext := externalReadReg
      dataReadNext := io.busDataIn
      debugNext(15 downto 0) := addrReg
      debugNext(24) := writeNReg
      debugNext(23 downto 16) := io.busDataIn
      phi2Next := False
    }
    is(B"1" ## B"xD") {
      io.debugReady := ~io.disable
      externalReadNext := False
      phi1Next     := True
      addrOeNext   := False
      refreshOeNext := False
      controlOeNext := False
      dataOeNext   := False
      mpdNNext     := True
      clearRequest := True
      clearSnoop   := True
      takeoverNext := ~io.disable
    }
  }

  // outputs
  io.busPhi1     := phi1Reg
  io.busPhi2     := phi2Reg
  io.busAddrOut  := addrReg
  io.busAddrOe   := addrOeReg
  io.busDataOut  := outputDataReg
  io.busDataOe   := dataOeReg
  io.busWriteN   := writeNReg
  io.busControlOe := controlOeReg
  io.busRefreshOe := refreshOeReg

  io.busS4N   := mmuS4N
  io.busS5N   := mmuS5N
  io.busCctlN := mmuCctlN
  io.busD1xxN := mmuD1xxN

  io.busCasN  := casNReg | ~io.pbiExtselN
  io.busRasN  := rasNReg

  io.busCasinhN  := casinhNReg
  io.busCasinhOe := casinhOeReg

  io.takeover       := takeoverReg
  io.release        := releaseReg
  io.externalAccess := externalReadReg
  io.dataOut        := dataReadReg
  io.complete       := completeReg

  io.mpdN := mpdNReg
  io.debug := debugReg
}
