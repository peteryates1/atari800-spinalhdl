package atari800

import spinal.core._
import jop.io.HasBusIo

// SIO UART bridge: hardware serializer/deserializer for Atari SIO bus.
// Allows JOP to monitor command frames and inject drive responses.
//
// RX path: Deserializes SIO_TXD (Atari→peripheral) using SIO_CLOCKOUT falling edge.
// TX path: Serializes onto SIO_RXD (peripheral→Atari) using internal baud generator.
// Architecture matches MiST Atari800XL sio_handler.vhdl (S2P/P2S state machines).
//
// Register map (4-bit address, 32-bit data):
//   0  R: STATUS — b0=COMMAND(live,active-low), b1=RX_READY, b2=TX_BUSY,
//                  b3=FRAME_ERR(sticky), b4=CMD_EDGE(sticky). Reading clears b3,b4.
//      W: CTRL  — b0=TX_ENABLE (gate TX output onto SIO_RXD; else idles high)
//   1  R: RX_DATA — [7:0]=received byte (auto-pops RX FIFO), [15:8]=cmd byte index
//   2  W: TX_DATA — [7:0]=byte to transmit (pushes TX FIFO)
//   3  R: TX_STATUS — b0=EMPTY, b1=FULL, [9:2]=count
//   4  W: BAUD_DIV — [15:0]=TX baud divisor (default 2951 → 19200 baud @ 56.67 MHz)
//   5  R: RX_STATUS — b0=EMPTY, b1=FULL, [9:2]=count
class SioBridge extends Component with HasBusIo {
  val bus = new Bundle {
    val addr   = in  UInt(4 bits)
    val rd     = in  Bool()
    val wr     = in  Bool()
    val wrData = in  Bits(32 bits)
    val rdData = out Bits(32 bits)
    val cmdInterrupt = out Bool()
  }

  val io = new Bundle {
    val sioCommand  = in  Bool()  // PIA CB2_OUT: low during command frame
    val sioTxd      = in  Bool()  // POKEY serial out (Atari → us)
    val sioClockout = in  Bool()  // POKEY serout clock toggle
    val sioRxd      = out Bool()  // Our TX → POKEY sioIn (Atari RX)
  }

  // ===== Input registration (same clock domain, 1 stage) =====
  val cmdSync      = RegNext(io.sioCommand, True)
  val cmdPrev      = RegNext(cmdSync, True)
  val txdReg       = RegNext(io.sioTxd, True)
  val clockoutPrev = RegNext(io.sioClockout, False)

  // Edge detection
  val cmdFalling  = cmdPrev && !cmdSync                // command frame start
  val cmdRising   = !cmdPrev && cmdSync                // command frame end
  val rxClkEnable = clockoutPrev && !io.sioClockout    // SIO_CLOCKOUT falling edge

  // ===== COMMAND tracking =====
  val cmdEdge = Reg(Bool()) init False
  when(cmdRising) { cmdEdge := True }
  // cleared on STATUS read — see register section below

  val cmdByteCount = Reg(UInt(8 bits)) init 0
  when(cmdFalling) { cmdByteCount := 0 }

  // ===== RX FIFO (16 entries × 16 bits: [15:8]=cmdByteIndex, [7:0]=data) =====
  val rxMem   = Mem(Bits(16 bits), 16)
  val rxWrPtr = Reg(UInt(4 bits)) init 0
  val rxRdPtr = Reg(UInt(4 bits)) init 0
  val rxCount = Reg(UInt(5 bits)) init 0
  def rxEmpty = rxCount === 0
  def rxFull  = rxCount === 16

  val rxPush     = Bool();  rxPush := False
  val rxPushData = Bits(16 bits); rxPushData := 0
  val rxPop      = Bool();  rxPop := False

  val rxReadData = rxMem.readAsync(rxRdPtr)

  // Latch RX data on bus.rd to prevent JOP ioRdPending re-sampling corruption.
  // During re-sampling, rxRdPtr has already advanced; the latch holds the correct value.
  val rxReadLatch = Reg(Bits(16 bits)) init 0
  when(bus.rd && bus.addr === 1) {
    rxReadLatch := rxReadData
  }

  // Interrupt: one-cycle pulse when command frame ends and RX FIFO has data
  bus.cmdInterrupt := cmdRising && !rxEmpty

  // ===== TX FIFO (16 entries × 8 bits) =====
  val txMem   = Mem(Bits(8 bits), 16)
  val txWrPtr = Reg(UInt(4 bits)) init 0
  val txRdPtr = Reg(UInt(4 bits)) init 0
  val txCount = Reg(UInt(5 bits)) init 0
  def txEmpty = txCount === 0
  def txFull  = txCount === 16

  val txPush     = Bool();  txPush := False
  val txPushData = Bits(8 bits); txPushData := 0
  val txPop      = Bool();  txPop := False

  val txReadData = txMem.readAsync(txRdPtr)

  // ===== S2P Deserializer (SIO_TXD → RX FIFO) =====
  // Sample on SIO_CLOCKOUT falling edge, matching MiST sio_handler.vhdl
  object S2pState extends SpinalEnum {
    val WAIT, BIT0, BIT1, BIT2, BIT3, BIT4, BIT5, BIT6, BIT7, STOP = newElement()
  }
  val s2pState   = Reg(S2pState()) init S2pState.WAIT
  val s2pShift   = Reg(Bits(7 bits)) init B"1111111"
  val framingErr = Reg(Bool()) init False

  when(rxClkEnable) {
    // Shift in from MSB: after 7 shifts, s2pShift holds bits [7:1]
    s2pShift := txdReg ## s2pShift(6 downto 1)

    switch(s2pState) {
      is(S2pState.WAIT) {
        when(!txdReg) { s2pState := S2pState.BIT0 }
      }
      is(S2pState.BIT0) { s2pState := S2pState.BIT1 }
      is(S2pState.BIT1) { s2pState := S2pState.BIT2 }
      is(S2pState.BIT2) { s2pState := S2pState.BIT3 }
      is(S2pState.BIT3) { s2pState := S2pState.BIT4 }
      is(S2pState.BIT4) { s2pState := S2pState.BIT5 }
      is(S2pState.BIT5) { s2pState := S2pState.BIT6 }
      is(S2pState.BIT6) { s2pState := S2pState.BIT7 }
      is(S2pState.BIT7) {
        // Full byte: bit7 in txdReg, bits[6:0] in s2pShift
        rxPush := True
        rxPushData := cmdByteCount.asBits.resize(8) ## (txdReg ## s2pShift)
        when(!cmdSync) { cmdByteCount := cmdByteCount + 1 }
        s2pState := S2pState.STOP
      }
      is(S2pState.STOP) {
        when(!txdReg) { framingErr := True }
        s2pState := S2pState.WAIT
      }
    }
  }

  // ===== P2S Serializer (TX FIFO → SIO_RXD) =====
  val txEnableReg = Reg(Bool()) init False
  val baudDiv     = Reg(UInt(16 bits)) init 2951
  val baudCtr     = Reg(UInt(16 bits)) init 2951

  val txTick = Bool()
  txTick := False
  baudCtr := baudCtr - 1
  when(baudCtr === 0) {
    baudCtr := baudDiv
    txTick := True
  }

  object P2sState extends SpinalEnum {
    val WAIT, BIT0, BIT1, BIT2, BIT3, BIT4, BIT5, BIT6, BIT7, STOP = newElement()
  }
  val p2sState = Reg(P2sState()) init P2sState.WAIT
  val p2sShift = Reg(Bits(8 bits)) init B"11111111"
  val p2sOut   = Reg(Bool()) init True

  when(txTick) {
    switch(p2sState) {
      is(P2sState.WAIT) {
        p2sOut := True
        when(txEnableReg && !txEmpty) {
          p2sShift := txReadData
          txPop := True
          p2sOut := False                // start bit
          p2sState := P2sState.BIT0
        }
      }
      // Data bits: LSB first, shift right with 1-fill
      is(P2sState.BIT0) { p2sOut := p2sShift(0); p2sShift := B"1" ## p2sShift(7 downto 1); p2sState := P2sState.BIT1 }
      is(P2sState.BIT1) { p2sOut := p2sShift(0); p2sShift := B"1" ## p2sShift(7 downto 1); p2sState := P2sState.BIT2 }
      is(P2sState.BIT2) { p2sOut := p2sShift(0); p2sShift := B"1" ## p2sShift(7 downto 1); p2sState := P2sState.BIT3 }
      is(P2sState.BIT3) { p2sOut := p2sShift(0); p2sShift := B"1" ## p2sShift(7 downto 1); p2sState := P2sState.BIT4 }
      is(P2sState.BIT4) { p2sOut := p2sShift(0); p2sShift := B"1" ## p2sShift(7 downto 1); p2sState := P2sState.BIT5 }
      is(P2sState.BIT5) { p2sOut := p2sShift(0); p2sShift := B"1" ## p2sShift(7 downto 1); p2sState := P2sState.BIT6 }
      is(P2sState.BIT6) { p2sOut := p2sShift(0); p2sShift := B"1" ## p2sShift(7 downto 1); p2sState := P2sState.BIT7 }
      is(P2sState.BIT7) { p2sOut := p2sShift(0); p2sState := P2sState.STOP }
      is(P2sState.STOP) { p2sOut := True; p2sState := P2sState.WAIT }
    }
  }

  io.sioRxd := Mux(txEnableReg, p2sOut, True)

  // ===== Register interface =====
  when(bus.wr) {
    switch(bus.addr) {
      is(0x0) { txEnableReg := bus.wrData(0) }
      is(0x2) { txPush := True; txPushData := bus.wrData(7 downto 0) }
      is(0x4) { baudDiv := bus.wrData(15 downto 0).asUInt }
    }
  }

  bus.rdData := B(0, 32 bits)
  switch(bus.addr) {
    is(0x0) {
      bus.rdData := B(0, 27 bits) ## cmdEdge ## framingErr ##
                    (p2sState =/= P2sState.WAIT).asBits ##
                    (!rxEmpty).asBits ## (!cmdSync).asBits
      when(bus.rd) { cmdEdge := False; framingErr := False }
    }
    is(0x1) {
      bus.rdData := B(0, 16 bits) ## Mux(bus.rd, rxReadData, rxReadLatch)
      when(bus.rd) { rxPop := True }
    }
    is(0x3) {
      bus.rdData := B(0, 22 bits) ## txCount.asBits.resize(8) ## txFull.asBits ## txEmpty.asBits
    }
    is(0x5) {
      bus.rdData := B(0, 22 bits) ## rxCount.asBits.resize(8) ## rxFull.asBits ## rxEmpty.asBits
    }
  }

  // ===== FIFO updates (single point of truth for pointer/count changes) =====
  val rxDoPush = rxPush && !rxFull
  val rxDoPop  = rxPop  && !rxEmpty
  when(rxDoPush) { rxMem.write(rxWrPtr, rxPushData); rxWrPtr := rxWrPtr + 1 }
  when(rxDoPop)  { rxRdPtr := rxRdPtr + 1 }
  when(rxDoPush && !rxDoPop) { rxCount := rxCount + 1 }
  when(!rxDoPush && rxDoPop) { rxCount := rxCount - 1 }

  val txDoPush = txPush && !txFull
  val txDoPop  = txPop  && !txEmpty
  when(txDoPush) { txMem.write(txWrPtr, txPushData); txWrPtr := txWrPtr + 1 }
  when(txDoPop)  { txRdPtr := txRdPtr + 1 }
  when(txDoPush && !txDoPop) { txCount := txCount + 1 }
  when(!txDoPush && txDoPop) { txCount := txCount - 1 }

  // HasBusIo implementation
  override def busAddr: UInt   = bus.addr
  override def busRd: Bool     = bus.rd
  override def busWr: Bool     = bus.wr
  override def busWrData: Bits = bus.wrData
  override def busRdData: Bits = bus.rdData
  override def busExternalIo: Option[Bundle] = Some(io)
  override def busInterrupts: Seq[Bool] = Seq(bus.cmdInterrupt)
}
