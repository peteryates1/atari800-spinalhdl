package atari800

import spinal.core._
import spinal.lib.fsm._

// Pico Bus Interface
// Provides register-mapped access from a Raspberry Pi Pico to the FPGA
// Handles: DMA to SDRAM, cartridge type, pot values, cart reader, config
class PicoBus extends Component {
  val io = new Bundle {
    // Pico-side bus (accent tristate handled via read/write/writeEn)
    val picoDataRead  = in  Bits(8 bits)   // data from Pico (active during WR)
    val picoDataWrite = out Bits(8 bits)   // data to Pico (active during RD)
    val picoDataOe    = out Bool()         // output enable (active when FPGA drives)
    val picoAddr  = in  Bits(3 bits)
    val picoWrN   = in  Bool()
    val picoRdN   = in  Bool()
    val picoIrqN  = out Bool()

    // DMA interface to SDRAM
    val dmaFetch     = out Bool()
    val dmaAddr      = out Bits(24 bits)
    val dmaWriteData = out Bits(32 bits)
    val dmaWrite8    = out Bool()
    val dmaWrite16   = out Bool()
    val dmaWrite32   = out Bool()
    val dmaReadEn    = out Bool()
    val dmaMemReady  = in  Bool()
    val dmaMemData   = in  Bits(32 bits)

    // Cartridge emulation
    val cartSelect   = out Bits(6 bits)

    // Paddle outputs
    val paddle0 = out SInt(8 bits)
    val paddle1 = out SInt(8 bits)
    val paddle2 = out SInt(8 bits)
    val paddle3 = out SInt(8 bits)

    // Physical cartridge bus
    val cartAddrOut = out Bits(13 bits)
    val cartS4N     = out Bool()
    val cartS5N     = out Bool()
    val cartCctlN   = out Bool()
    val cartDataIn  = in  Bits(8 bits)

    // Config
    val pal        = out Bool()
    val ramSelect  = out Bits(3 bits)
    val turbo      = out Bool()
    val coldReset  = out Bool()

    // Status
    val pllLocked  = in  Bool()
  }

  // Register addresses
  object Addr {
    val DMA_DATA   = B"000"
    val DMA_ADDR_L = B"001"
    val DMA_ADDR_M = B"010"
    val DMA_ADDR_H = B"011"
    val CONTROL    = B"100"
    val CONFIG     = B"101"
    val CART_ADDR  = B"110"
    val CART_DATA  = B"111"
  }

  // Synchronize async bus signals from Pico (3-stage)
  val wrNSync = Reg(Bits(3 bits)) init B"111"
  val rdNSync = Reg(Bits(3 bits)) init B"111"
  val addrLat  = Reg(Bits(3 bits)) init 0
  val wdataLat = Reg(Bits(8 bits)) init 0

  wrNSync := wrNSync(1 downto 0) ## io.picoWrN
  rdNSync := rdNSync(1 downto 0) ## io.picoRdN

  // Capture address and data on falling edge of WR/RD
  when(wrNSync(2 downto 1) === B"10") {
    addrLat  := io.picoAddr
    wdataLat := io.picoDataRead
  }
  when(rdNSync(2 downto 1) === B"10") {
    addrLat := io.picoAddr
  }

  val wrPulse = wrNSync(2 downto 1) === B"10"
  val rdPulse = rdNSync(2 downto 1) === B"10"

  // DMA address register (auto-incrementing)
  val dmaAddrReg = Reg(UInt(24 bits)) init 0

  // DMA read data buffer
  val dmaReadData = Reg(Bits(8 bits)) init 0
  val dmaBusy     = Reg(Bool()) init False

  // Config registers
  val cartSelectReg = Reg(Bits(6 bits)) init 0
  val configReg     = Reg(Bits(8 bits)) init B"00000001"  // PAL default
  val coldResetReg  = Reg(Bool()) init False

  // Paddle registers
  val paddle0Reg = Reg(SInt(8 bits)) init S(-128, 8 bits)
  val paddle1Reg = Reg(SInt(8 bits)) init S(-128, 8 bits)
  val paddle2Reg = Reg(SInt(8 bits)) init S(-128, 8 bits)
  val paddle3Reg = Reg(SInt(8 bits)) init S(-128, 8 bits)
  val paddleReg  = Reg(Bits(8 bits)) init 0
  val paddleSel  = Reg(Bits(2 bits)) init 0

  // Cart reader registers
  val cartAddrReg = Reg(Bits(13 bits)) init 0
  val cartCtrlReg = Reg(Bits(3 bits)) init B"111"  // S4#=1, S5#=1, CCTL#=1

  // Read data mux
  val readData = Bits(8 bits)
  switch(addrLat) {
    is(Addr.DMA_DATA)   { readData := dmaReadData }
    is(Addr.DMA_ADDR_L) { readData := dmaAddrReg(7 downto 0).asBits }
    is(Addr.DMA_ADDR_M) { readData := dmaAddrReg(15 downto 8).asBits }
    is(Addr.DMA_ADDR_H) { readData := dmaAddrReg(23 downto 16).asBits }
    is(Addr.CONTROL)    { readData := dmaBusy ## B"0" ## cartSelectReg }
    is(Addr.CONFIG)     { readData := B"000000" ## io.pllLocked ## B"0" }
    is(Addr.CART_ADDR)  { readData := B"000" ## cartAddrReg(12 downto 8) }
    is(Addr.CART_DATA)  { readData := io.cartDataIn }
  }

  // Data bus direction: FPGA drives when Pico reads
  io.picoDataWrite := readData
  io.picoDataOe    := ~io.picoRdN

  // DMA output defaults
  io.dmaFetch     := False
  io.dmaWrite8    := False
  io.dmaWrite16   := False
  io.dmaWrite32   := False
  io.dmaReadEn    := False
  io.dmaAddr      := dmaAddrReg.asBits
  io.dmaWriteData := wdataLat ## wdataLat ## wdataLat ## wdataLat

  // DMA state machine and register writes
  val dmaFsm = new StateMachine {
    val idle      = new State with EntryPoint
    val writeWait = new State
    val readWait  = new State

    coldResetReg := False  // pulse

    idle.whenIsActive {
      dmaBusy := False

      when(wrPulse) {
        switch(addrLat) {
          is(Addr.DMA_DATA) {
            // Write byte to SDRAM at current DMA address
            io.dmaFetch  := True
            io.dmaWrite8 := True
            io.dmaAddr   := dmaAddrReg.asBits
            io.dmaWriteData := wdataLat ## wdataLat ## wdataLat ## wdataLat
            dmaBusy := True
            goto(writeWait)
          }
          is(Addr.DMA_ADDR_L) { dmaAddrReg(7 downto 0)   := U(wdataLat) }
          is(Addr.DMA_ADDR_M) { dmaAddrReg(15 downto 8)  := U(wdataLat) }
          is(Addr.DMA_ADDR_H) { dmaAddrReg(23 downto 16) := U(wdataLat) }
          is(Addr.CONTROL) {
            cartSelectReg := wdataLat(5 downto 0)
            when(wdataLat(7)) { coldResetReg := True }
          }
          is(Addr.CONFIG) {
            configReg := wdataLat
            paddleSel := wdataLat(6 downto 5)
          }
          is(Addr.CART_DATA) {
            // Paddle value write: paddle select set by previous CONFIG write bits 6:5
            paddleReg := wdataLat
            switch(paddleSel) {
              is(B"00") { paddle0Reg := wdataLat.asSInt }
              is(B"01") { paddle1Reg := wdataLat.asSInt }
              is(B"10") { paddle2Reg := wdataLat.asSInt }
              is(B"11") { paddle3Reg := wdataLat.asSInt }
            }
          }
          is(Addr.CART_ADDR) {
            cartAddrReg(12 downto 8) := wdataLat(4 downto 0)
            cartCtrlReg := wdataLat(7 downto 5)
          }
          default {}
        }
      } elsewhen (rdPulse && addrLat === Addr.DMA_DATA) {
        // Read byte from SDRAM at current DMA address
        io.dmaFetch  := True
        io.dmaReadEn := True
        io.dmaAddr   := dmaAddrReg.asBits
        dmaBusy := True
        goto(readWait)
      }
    }

    writeWait.whenIsActive {
      io.dmaFetch  := True
      io.dmaWrite8 := True
      io.dmaAddr   := dmaAddrReg.asBits
      io.dmaWriteData := wdataLat ## wdataLat ## wdataLat ## wdataLat
      when(io.dmaMemReady) {
        io.dmaFetch  := False
        io.dmaWrite8 := False
        dmaAddrReg := dmaAddrReg + 1
        goto(idle)
      }
    }

    readWait.whenIsActive {
      io.dmaFetch  := True
      io.dmaReadEn := True
      io.dmaAddr   := dmaAddrReg.asBits
      when(io.dmaMemReady) {
        io.dmaFetch  := False
        io.dmaReadEn := False
        switch(dmaAddrReg(1 downto 0)) {
          is(U"00") { dmaReadData := io.dmaMemData(7 downto 0) }
          is(U"01") { dmaReadData := io.dmaMemData(15 downto 8) }
          is(U"10") { dmaReadData := io.dmaMemData(23 downto 16) }
          default   { dmaReadData := io.dmaMemData(31 downto 24) }
        }
        dmaAddrReg := dmaAddrReg + 1
        goto(idle)
      }
    }
  }

  // Output assignments
  io.cartSelect  := cartSelectReg
  io.cartAddrOut := cartAddrReg
  io.cartS4N     := cartCtrlReg(2)
  io.cartS5N     := cartCtrlReg(1)
  io.cartCctlN   := cartCtrlReg(0)

  io.pal       := configReg(0)
  io.ramSelect := configReg(3 downto 1)
  io.turbo     := configReg(4)
  io.coldReset := coldResetReg

  io.paddle0 := paddle0Reg
  io.paddle1 := paddle1Reg
  io.paddle2 := paddle2Reg
  io.paddle3 := paddle3Reg

  io.picoIrqN := True  // unused for now
}
