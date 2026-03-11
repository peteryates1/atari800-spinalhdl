package atari800

import spinal.core._

class FreezerLogic extends Component {
  val io = new Bundle {
    val clkEnable       = in  Bool()
    val cpuCycle        = in  Bool()
    val a               = in  Bits(16 bits)
    val dIn             = in  Bits(8 bits)
    val rw              = in  Bool()
    val resetN          = in  Bool()
    val activateN       = in  Bool()
    val dualpokeyN      = in  Bool()

    val disableAtari    = out Bool()
    val accessType      = out Bits(2 bits)
    val accessAddress   = out Bits(17 bits)
    val dOut            = out Bits(8 bits)
    val request         = in  Bool()
    val requestComplete = out Bool()
    val stateOut        = out Bits(3 bits)
  }

  val ACCESS_TYPE_NONE = B"00"
  val ACCESS_TYPE_DATA = B"01"
  val ACCESS_TYPE_RAM  = B"10"
  val ACCESS_TYPE_ROM  = B"11"

  val STATE_DISABLED           = B"000"
  val STATE_HALF_ENABLED       = B"001"
  val STATE_STARTUP            = B"010"
  val STATE_ENABLED            = B"100"
  val STATE_TEMPORARY_DISABLED = B"101"

  val FREEZER_DEF_ROM_BANK = B"100"
  val FREEZER_DEF_RAM_BANK = B"11111"

  // registers
  val stateReg             = Reg(Bits(3 bits)) init STATE_DISABLED
  val ramBankReg           = Reg(Bits(5 bits)) init B(0, 5 bits)
  val romBankReg           = Reg(Bits(3 bits)) init FREEZER_DEF_ROM_BANK
  val vectorA2Reg          = Reg(Bool())
  val useStatusAsRamAddrReg = Reg(Bool()) init False
  val bramRequestCompleteReg = Reg(Bool()) init False

  io.stateOut := stateReg

  val vectorAccess = (io.a(15 downto 3) === B"1111111111111") & io.rw

  // state machine
  when(~io.resetN) {
    stateReg := STATE_DISABLED
  } otherwise {
    when(io.clkEnable & io.cpuCycle) {
      switch(stateReg) {
        is(STATE_DISABLED) {
          when(vectorAccess & ~io.activateN & ~io.a(0)) {
            stateReg := STATE_HALF_ENABLED
            vectorA2Reg := io.a(2)
          }
        }
        is(STATE_HALF_ENABLED) {
          when(vectorAccess & ~io.activateN & io.a(0)) {
            stateReg := STATE_STARTUP
          } otherwise {
            stateReg := STATE_DISABLED
          }
        }
        is(STATE_STARTUP) {
          when(io.a(15 downto 4) === B"xD72") {
            stateReg := STATE_ENABLED
          }
        }
        is(STATE_ENABLED) {
          when(io.a(15 downto 4) === B"xD70") {
            when(io.rw) {
              stateReg := STATE_DISABLED
            } otherwise {
              stateReg := STATE_TEMPORARY_DISABLED
            }
          }
        }
        is(STATE_TEMPORARY_DISABLED) {
          when(io.a(15 downto 4) === B"xD70") {
            when(io.rw) {
              stateReg := STATE_DISABLED
            } otherwise {
              stateReg := STATE_ENABLED
            }
          }
        }
        default {
          stateReg := STATE_DISABLED
        }
      }
    }
  }

  // set_status_ram_address
  when(io.clkEnable) {
    when(stateReg === STATE_DISABLED) {
      useStatusAsRamAddrReg := False
    } otherwise {
      when(io.a(15 downto 4) === B"xD71") {
        useStatusAsRamAddrReg := io.rw
      }
    }
  }

  // bank select
  when(io.clkEnable) {
    when(stateReg === STATE_DISABLED) {
      ramBankReg := B(0, 5 bits)
      romBankReg := FREEZER_DEF_ROM_BANK
    } otherwise {
      // D740-D77F
      when(io.a(15 downto 6) === B"1101011101") {
        romBankReg := io.a(2 downto 0)
      }
      // D780-D79F
      when(io.a(15 downto 5) === B"11010111100") {
        ramBankReg := io.a(4 downto 0)
      }
    }
  }

  // mem_output record as individual signals
  val outAdr          = Bits(17 bits)
  val outRamAccess    = Bool()
  val outRomAccess    = Bool()
  val outDisableAtari = Bool()
  val outDout         = Bits(8 bits)
  val outDoutEnable   = Bool()
  val outShadowEnable = Bool()

  outAdr          := B(0, 17 bits)
  outRamAccess    := False
  outRomAccess    := False
  outDisableAtari := False
  outDout         := B"xFF"
  outDoutEnable   := False
  outShadowEnable := False

  // access_freezer process
  switch(stateReg) {
    is(STATE_DISABLED, STATE_HALF_ENABLED) {
      // shadow writes to D0xx, D2xx, D3xx, D4xx
      when(~io.rw) {
        switch(io.a(15 downto 8)) {
          is(B"xD0", B"xD2", B"xD3", B"xD4") {
            outShadowEnable := True
            outAdr(16 downto 8) := FREEZER_DEF_RAM_BANK ## io.a(11 downto 8)
            // GTIA/D000 needs 32 bytes, others 16 bytes
            // in dualpokey mode also shadow D2xx with 32 bytes
            when((io.a(10 downto 8) === B"000") | (~io.dualpokeyN & (io.a(10 downto 8) === B"010"))) {
              outAdr(4 downto 0) := io.a(4 downto 0)
            } otherwise {
              outAdr(3 downto 0) := io.a(3 downto 0)
            }
          }
        }
      }
      when((stateReg === STATE_HALF_ENABLED) & vectorAccess & io.a(0)) {
        outDout := B"x21"
        outDoutEnable := True
        outDisableAtari := True
      }
    }
    is(STATE_STARTUP, STATE_ENABLED) {
      when((stateReg === STATE_STARTUP) & vectorAccess & io.a(0)) {
        outDout := B"x20"
        outDoutEnable := True
        outDisableAtari := True
      }
      // 0000-1FFF: RAM
      when(io.a(15 downto 13) === B"000") {
        outRamAccess := True
        outDisableAtari := True
        when(~io.a(12)) {
          outAdr(16 downto 12) := FREEZER_DEF_RAM_BANK
        } otherwise {
          outAdr(16 downto 12) := ramBankReg
        }
        outAdr(11 downto 0) := io.a(11 downto 0)
        when(useStatusAsRamAddrReg) {
          outAdr(4) := vectorA2Reg
          outAdr(5) := ~io.dualpokeyN
        }
      }
      // 2000-3FFF: switched ROM bank
      when(io.a(15 downto 13) === B"001") {
        when(io.rw) {
          outRomAccess := True
        }
        outDisableAtari := True
        outAdr := B"0" ## romBankReg ## io.a(12 downto 0)
      }
      // D7xx freezer control
      when(io.a(15 downto 8) === B"xD7") {
        outDisableAtari := True
      }
    }
    is(STATE_TEMPORARY_DISABLED) {
      when(io.a(15 downto 8) === B"xD7") {
        outDisableAtari := True
      }
    }
  }

  // memory_glue process
  val bramAdr     = Bits(7 bits)
  val bramWe      = Bool()
  val bramRequest = Bool()

  // block ram (instantiated early so bramDataOut is available below)
  val freezerBram = new GenericRamInfer(ADDRESS_WIDTH = 7, SPACE = 128, DATA_WIDTH = 8)
  freezerBram.io.address := bramAdr
  freezerBram.io.data    := io.dIn
  freezerBram.io.we      := bramWe
  val bramDataOut = freezerBram.io.q

  io.disableAtari    := outDisableAtari
  io.accessType      := ACCESS_TYPE_NONE
  io.accessAddress   := outAdr
  io.requestComplete := False
  io.dOut            := B"xFF"
  bramAdr     := B(0, 7 bits)
  bramWe      := False
  bramRequest := False

  when(outShadowEnable) {
    bramAdr := outAdr(9) ## (outAdr(8) | outAdr(10)) ## outAdr(4 downto 0)
    bramWe := True
  } elsewhen(outDoutEnable) {
    io.accessType := ACCESS_TYPE_DATA
    io.dOut := outDout
    io.requestComplete := io.request
  } elsewhen(outRomAccess) {
    io.accessType := ACCESS_TYPE_ROM
  } elsewhen(outRamAccess) {
    io.accessType := ACCESS_TYPE_RAM

    // map shadow ram access to blockram
    when((outAdr(16 downto 12) === FREEZER_DEF_RAM_BANK) & (outAdr(7 downto 5) === B"000")) {
      switch(outAdr(11 downto 8)) {
        is(B"x0", B"x2", B"x3", B"x4") {
          io.accessType := ACCESS_TYPE_DATA
          bramAdr := outAdr(9) ## (outAdr(8) | outAdr(10)) ## outAdr(4 downto 0)
          bramWe := io.request & ~io.rw
          bramRequest := io.request
          io.requestComplete := bramRequestCompleteReg
          io.dOut := bramDataOut
        }
      }
    }
  }

  // bram_request_complete register
  when(~io.resetN) {
    bramRequestCompleteReg := False
  } otherwise {
    bramRequestCompleteReg := bramRequest
  }

}
