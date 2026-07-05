package atari800

import spinal.core._
import spinal.lib._
import spinal.lib.fsm._

// Full-range SDRAM built-in self test. Talks the SdramStatemachine client
// protocol (1-cycle REQUEST pulse, COMPLETE idles high, busy-seen handshake)
// so the same engine runs against the real controller on hardware and against
// MockSdram in simulation. 32-bit accesses with address-derived data: every
// location holds a value unique to its address, so ANY aliasing between two
// addresses (broken/misrouted address bit, wrong row/column geometry) is a
// guaranteed mismatch, not a lucky pass.
//
// Phases: 1 WALK      - unique data at byte address 0 and every power of two
//                       (4..1<<walkMax); classic address-line test
//         2 SWEEP A   - all words, data = byteAddr ^ 0x55555555
//         3 SWEEP B   - all words, data = byteAddr ^ 0xAAAAAAAA
//         4 RETENTION - pause ~1.3 s (2^retWaitBits cycles), then re-read
//                       sweep B: catches broken auto-refresh
class SdramBistEngine(
  addrWidth   : Int    = 26,        // width of the controller's ADDRESS_IN
  walkMax     : Int    = 24,        // highest byte-address bit walked
  sweepWords  : BigInt = BigInt(1) << 23,  // 32-bit words per sweep (8M = 32 MB)
  retWaitBits : Int    = 27,        // 2^27 cycles @ 100 MHz = 1.34 s
  byteMode    : Boolean = false     // byte accesses, data replicated x4 - the
                                    // supervisor loader's exact transaction mix
) extends Component {
  val io = new Bundle {
    val ready    = in  Bool()                 // controller reset_client_n
    val request  = out Bool()
    val writeEn  = out Bool()
    val readEn   = out Bool()
    val addr     = out Bits(addrWidth bits)   // byte address
    val dataOut  = out Bits(32 bits)
    val dataIn   = in  Bits(32 bits)
    val complete = in  Bool()
    // status
    val state     = out Bits(2 bits)          // 0 running, 1 pass, 2 fail
    val phase     = out UInt(3 bits)
    val progress  = out UInt(25 bits)         // current byte address
    val errCnt    = out UInt(16 bits)
    val firstAddr = out Bits(25 bits)
    val firstGot  = out Bits(32 bits)
    val firstExp  = out Bits(32 bits)
    val firstPhase = out UInt(3 bits)
    val restart   = in  Bool()                // pulse: rerun from scratch
  }

  val walkSlots = walkMax - 1                 // slot 0 -> addr 0, slot k -> 1<<(k+1)

  val phase    = Reg(UInt(3 bits)) init 1
  val reading  = Reg(Bool()) init False       // false: write pass, true: read pass
  val idx      = Reg(UInt(25 bits)) init 0    // walk slot or word index
  val busySeen = Reg(Bool()) init False
  val doneReg  = Reg(Bool()) init False
  val failReg  = Reg(Bool()) init False

  val errCnt     = Reg(UInt(16 bits)) init 0
  val firstAddr  = Reg(Bits(25 bits)) init 0
  val firstGot   = Reg(Bits(32 bits)) init 0
  val firstExp   = Reg(Bits(32 bits)) init 0
  val firstPhase = Reg(UInt(3 bits)) init 0
  val retWait    = Reg(UInt(retWaitBits bits)) init 0

  // Current byte address for this transaction
  val walkAddr = UInt(25 bits)
  walkAddr := 0
  when(idx =/= 0) {
    walkAddr := (U(1, 25 bits) |<< (idx(4 downto 0) + 1)).resized
  }
  val sweepAddr = (if (byteMode) idx else (idx << 2)).resize(25)
  val byteAddr  = UInt(25 bits)
  byteAddr := Mux(phase === 1, walkAddr, sweepAddr)

  // Expected/write data, unique per address within each phase
  val expData = Bits(32 bits)
  expData := (byteAddr.asBits.resize(32) ^ B(0x55555555L, 32 bits))
  when(phase === 1) { expData := B(0xB1570000L, 32 bits) | idx.asBits.resize(32) }
  when(phase === 3 || phase === 4) {
    expData := (byteAddr.asBits.resize(32) ^ B(0xAAAAAAAAL, 32 bits))
  }

  val lastIdx = UInt(25 bits)
  lastIdx := (sweepWords - 1)
  when(phase === 1) { lastIdx := walkSlots }

  // byte mode: 8-bit address-hash payload, replicated on all lanes like the
  // loader; compare only the addressed byte on readback
  // phase 4 re-reads what phase 3 wrote - hash with the WRITING phase
  val hashPhase = Mux(phase === 4, U(3, 3 bits), phase)
  val expByte = (byteAddr(7 downto 0) ^ byteAddr(15 downto 8) ^
                 byteAddr(22 downto 16).resize(8) ^
                 (hashPhase.resize(8) |<< 5)).asBits

  val issueReq = False
  io.request := issueReq
  io.writeEn := !reading && phase =/= 4
  io.readEn  := reading || phase === 4
  io.addr    := byteAddr.asBits.resize(addrWidth)
  io.dataOut := (if (byteMode) expByte ## expByte ## expByte ## expByte else expData)

  val fsm = new StateMachine {
    val Wait     = new State with EntryPoint  // controller not ready yet
    val Issue    = new State
    val Complete = new State
    val Pause    = new State                  // retention delay
    val Done     = new State

    Wait.whenIsActive { when(io.ready) { goto(Issue) } }

    Issue.whenIsActive {
      issueReq := True
      busySeen := False
      goto(Complete)
    }

    Complete.whenIsActive {
      when(!io.complete) { busySeen := True }
      when(busySeen && io.complete) {
        when((reading || phase === 4) &&
             (if (byteMode) io.dataIn(7 downto 0) =/= expByte else io.dataIn =/= expData)) {
          when(errCnt =/= errCnt.maxValue) { errCnt := errCnt + 1 }
          when(errCnt === 0) {
            firstAddr  := byteAddr.asBits
            firstGot   := io.dataIn
            firstExp   := (if (byteMode) expByte.resize(32) else expData)
            firstPhase := phase
          }
        }
        when(idx =/= lastIdx) {
          idx := idx + 1
          goto(Issue)
        } otherwise {
          idx := 0
          when(!reading && phase =/= 4) {          // write pass done -> read pass
            reading := True
            goto(Issue)
          } elsewhen (phase === 3 && reading) {    // sweep B read done -> retention
            phase   := 4
            retWait := 0
            goto(Pause)
          } elsewhen (phase === 4) {               // retention re-read done
            doneReg := True
            failReg := errCnt =/= 0
            goto(Done)
          } otherwise {                            // next phase
            phase   := phase + 1
            reading := False
            goto(Issue)
          }
        }
      }
    }

    Pause.whenIsActive {
      retWait := retWait + 1
      when(retWait === retWait.maxValue) { goto(Issue) }
    }

    Done.whenIsActive {
      when(io.restart) {
        phase := 1; reading := False; idx := 0
        errCnt := 0; doneReg := False; failReg := False
        goto(Issue)
      }
    }
  }

  io.state      := Mux(doneReg, Mux(failReg, B"10", B"01"), B"00")
  io.phase      := phase
  io.progress   := byteAddr
  io.errCnt     := errCnt
  io.firstAddr  := firstAddr
  io.firstGot   := firstGot
  io.firstExp   := firstExp
  io.firstPhase := firstPhase
}

// Minimal SPI mode-0 slave reporting BIST status to the RP2040 supervisor.
// Any CS-low frame streams the status block on MISO (snapshotted at CS fall
// so multi-byte fields are coherent). MOSI byte 0 == 0xA0 restarts the test.
//
// MISO bytes: 0 magic 0xB5 | 1 state | 2 phase | 3..6 progress LE |
//             7..8 errCnt LE | 9..12 firstAddr LE | 13..16 firstGot LE |
//             17..20 firstExp LE | 21 firstPhase | 22 sentinel 0x5A
class BistSpiReporter extends Component {
  val io = new Bundle {
    val spiSck  = in  Bool()
    val spiMosi = in  Bool()
    val spiCsN  = in  Bool()
    val spiMiso = out Bool()
    val state      = in Bits(2 bits)
    val phase      = in UInt(3 bits)
    val progress   = in UInt(25 bits)
    val errCnt     = in UInt(16 bits)
    val firstAddr  = in Bits(25 bits)
    val firstGot   = in Bits(32 bits)
    val firstExp   = in Bits(32 bits)
    val firstPhase = in UInt(3 bits)
    val restart    = out Bool()
  }

  val sck  = BufferCC(io.spiSck,  False, bufferDepth = 3)
  val mosi = BufferCC(io.spiMosi, False, bufferDepth = 3)
  val csN  = BufferCC(io.spiCsN,  True,  bufferDepth = 3)
  val sckPrev = RegNext(sck) init False
  val csPrev  = RegNext(csN) init True
  val sckRise = sck && !sckPrev
  val sckFall = !sck && sckPrev
  val csFall  = !csN && csPrev

  val bitCnt  = Reg(UInt(3 bits)) init 0
  val shiftIn = Reg(Bits(8 bits)) init 0
  val byteIdx = Reg(UInt(5 bits)) init 0
  val restartReq = Reg(Bool()) init False
  restartReq := False
  io.restart := restartReq

  val snap = Reg(Vec(Bits(8 bits), 23))
  when(csFall) {
    bitCnt := 0; byteIdx := 0
    snap(0)  := B(0xB5, 8 bits)
    snap(1)  := io.state.resize(8)
    snap(2)  := io.phase.asBits.resize(8)
    snap(3)  := io.progress.asBits(7 downto 0)
    snap(4)  := io.progress.asBits(15 downto 8)
    snap(5)  := io.progress.asBits(23 downto 16)
    snap(6)  := io.progress.asBits.resize(32)(31 downto 24)
    snap(7)  := io.errCnt.asBits(7 downto 0)
    snap(8)  := io.errCnt.asBits(15 downto 8)
    snap(9)  := io.firstAddr(7 downto 0)
    snap(10) := io.firstAddr(15 downto 8)
    snap(11) := io.firstAddr(23 downto 16)
    snap(12) := io.firstAddr.resize(32)(31 downto 24)
    snap(13) := io.firstGot(7 downto 0)
    snap(14) := io.firstGot(15 downto 8)
    snap(15) := io.firstGot(23 downto 16)
    snap(16) := io.firstGot(31 downto 24)
    snap(17) := io.firstExp(7 downto 0)
    snap(18) := io.firstExp(15 downto 8)
    snap(19) := io.firstExp(23 downto 16)
    snap(20) := io.firstExp(31 downto 24)
    snap(21) := io.firstPhase.asBits.resize(8)
    snap(22) := B(0x5A, 8 bits)
  }

  when(!csN && sckRise) {
    shiftIn := shiftIn(6 downto 0) ## mosi
    bitCnt  := bitCnt + 1
    when(bitCnt === 7) {
      when(byteIdx === 0 && (shiftIn(6 downto 0) ## mosi) === 0xA0) { restartReq := True }
      when(byteIdx =/= byteIdx.maxValue) { byteIdx := byteIdx + 1 }
    }
  }

  val shiftOut = Reg(Bits(8 bits)) init 0
  when(csFall) { shiftOut := B(0xB5, 8 bits) }
  when(!csN && sckFall) {
    when(bitCnt === 0) {
      shiftOut := snap(Mux(byteIdx < 23, byteIdx, U(22, 5 bits)).resize(5))
    } otherwise {
      shiftOut := shiftOut(6 downto 0) ## B"0"
    }
  }
  io.spiMiso := shiftOut(7)
}
