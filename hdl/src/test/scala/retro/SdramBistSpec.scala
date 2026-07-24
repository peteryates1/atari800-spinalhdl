package retro.tests
import retro.common.util._
import retro.common.video._
import retro.common.scaler._
import retro.common.sdram._
import retro.link._
import retro.machines.atari._

import org.scalatest.funsuite.AnyFunSuite
import spinal.core._
import spinal.core.sim._
import spinal.lib._

// BIST engine + reporter against the toggle-faithful MockSdram.
// Fault injection is structural: a mock smaller than the walked address range
// aliases high addresses onto low ones - exactly the failure mode a broken
// address line or wrong geometry produces on real silicon. The BIST must
// catch it; a clean mock must pass.
class BistSimHarness(memBits: Int, walkMax: Int, sweepWords: Int) extends Component {
  val io = new Bundle {
    val spiSck  = in  Bool()
    val spiMosi = in  Bool()
    val spiCsN  = in  Bool()
    val spiMiso = out Bool()
    val state   = out Bits(2 bits)
    val errCnt  = out UInt(16 bits)
    val firstAddr = out Bits(25 bits)
    val firstPhase = out UInt(3 bits)
  }
  val mock = new MockSdram(latency = 4, memBits = memBits)
  val bist = new SdramBistEngine(
    addrWidth = 25, walkMax = walkMax, sweepWords = sweepWords, retWaitBits = 7)
  bist.io.ready := True
  mock.io.REQUEST         := bist.io.request
  mock.io.WRITE_EN        := bist.io.writeEn
  mock.io.READ_EN         := bist.io.readEn
  mock.io.ADDRESS_IN      := bist.io.addr
  mock.io.DATA_IN         := bist.io.dataOut
  mock.io.LONGWORD_ACCESS := True
  mock.io.WORD_ACCESS     := False
  mock.io.BYTE_ACCESS     := False
  mock.io.REFRESH         := False
  bist.io.dataIn   := mock.io.DATA_OUT
  bist.io.complete := mock.io.COMPLETE

  val rpt = new BistSpiReporter
  rpt.io.spiSck := io.spiSck; rpt.io.spiMosi := io.spiMosi; rpt.io.spiCsN := io.spiCsN
  io.spiMiso := rpt.io.spiMiso
  rpt.io.state := bist.io.state; rpt.io.phase := bist.io.phase
  rpt.io.progress := bist.io.progress; rpt.io.errCnt := bist.io.errCnt
  rpt.io.firstAddr := bist.io.firstAddr; rpt.io.firstGot := bist.io.firstGot
  rpt.io.firstExp := bist.io.firstExp; rpt.io.firstPhase := bist.io.firstPhase
  bist.io.restart := rpt.io.restart

  io.state := bist.io.state
  io.errCnt := bist.io.errCnt
  io.firstAddr := bist.io.firstAddr
  io.firstPhase := bist.io.firstPhase
}

// Wide transactions through SdramArbiter3 port B - the exact framebuffer
// write/read path. The direct-controller wide BIST passed on hardware while
// the through-arbiter path corrupted (only word 0 of each group written), so
// this seam needs its own coverage.
class ArbWideSimHarness(memBits: Int, sweepWords: Int) extends Component {
  val io = new Bundle {
    val state   = out Bits(2 bits)
    val errCnt  = out UInt(16 bits)
    val firstAddr = out Bits(25 bits)
    val firstGot  = out Bits(32 bits)
    val firstExp  = out Bits(32 bits)
    val firstPhase = out UInt(3 bits)
  }
  val arb  = new SdramArbiter3
  val mock = new MockSdram(latency = 4, memBits = memBits)
  val bist = new SdramBistEngine(
    addrWidth = 24, walkMax = 15, sweepWords = sweepWords, retWaitBits = 7,
    wideMode = true)
  bist.io.ready := True
  bist.io.restart := False

  arb.io.a.request := False; arb.io.a.readEnable := False; arb.io.a.writeEnable := False
  arb.io.a.addr := 0; arb.io.a.dataIn := 0
  arb.io.a.byteAccess := False; arb.io.a.wordAccess := False; arb.io.a.longwordAccess := False
  arb.io.a.refresh := False
  arb.io.c.request := False; arb.io.c.readEnable := False; arb.io.c.writeEnable := False
  arb.io.c.addr := 0; arb.io.c.dataIn := 0
  arb.io.c.byteAccess := False; arb.io.c.wordAccess := False; arb.io.c.longwordAccess := False
  arb.io.c.wideAccess := False
  arb.io.d.request := False; arb.io.d.readEnable := False; arb.io.d.writeEnable := False
  arb.io.d.addr := 0; arb.io.d.dataIn := 0
  arb.io.d.byteAccess := False; arb.io.d.wordAccess := False; arb.io.d.longwordAccess := False

  arb.io.b.request     := bist.io.request
  arb.io.b.readEnable  := bist.io.readEn
  arb.io.b.writeEnable := bist.io.writeEn
  arb.io.b.addr        := bist.io.addr(23 downto 0)
  arb.io.b.dataIn      := bist.io.dataOut
  arb.io.b.byteAccess  := False
  arb.io.b.wordAccess  := False
  arb.io.b.longwordAccess := !bist.io.wideAcc
  arb.io.b.wideAccess  := bist.io.wideAcc
  arb.io.b.wideIn      := bist.io.wideOut
  bist.io.dataIn := arb.io.b.dataOut
  bist.io.wideIn := arb.io.b.wideOut
  val bBusy = RegInit(False) setWhen (bist.io.request) clearWhen (arb.io.b.complete)
  bist.io.complete := !bBusy || arb.io.b.complete

  mock.io.REQUEST := arb.io.sdram.request;  arb.io.sdram.complete := mock.io.COMPLETE
  mock.io.READ_EN := arb.io.sdram.readEnable; mock.io.WRITE_EN := arb.io.sdram.writeEnable
  mock.io.ADDRESS_IN := arb.io.sdram.addr; mock.io.DATA_IN := arb.io.sdram.dataIn
  arb.io.sdram.dataOut := mock.io.DATA_OUT
  mock.io.BYTE_ACCESS := arb.io.sdram.byteAccess; mock.io.WORD_ACCESS := arb.io.sdram.wordAccess
  mock.io.LONGWORD_ACCESS := arb.io.sdram.longwordAccess; mock.io.REFRESH := arb.io.sdram.refresh
  mock.io.WIDE_ACCESS := arb.io.sdram.wideAccess; mock.io.WIDE_IN := arb.io.sdram.wideIn
  arb.io.sdram.wideOut := mock.io.WIDE_OUT

  io.state := bist.io.state; io.errCnt := bist.io.errCnt
  io.firstAddr := bist.io.firstAddr; io.firstGot := bist.io.firstGot
  io.firstExp := bist.io.firstExp; io.firstPhase := bist.io.firstPhase
}

// bare arbiter+mock: manual wide write then read on port B
class ArbWideBareHarness extends Component {
  val io = new Bundle {
    val req      = in  Bool()
    val we       = in  Bool()
    val addr     = in  Bits(24 bits)
    val wideIn   = in  Bits(256 bits)
    val wideOut  = out Bits(256 bits)
    val complete = out Bool()
  }
  val arb  = new SdramArbiter3
  val mock = new MockSdram(latency = 4, memBits = 16)
  arb.io.a.request := False; arb.io.a.readEnable := False; arb.io.a.writeEnable := False
  arb.io.a.addr := 0; arb.io.a.dataIn := 0
  arb.io.a.byteAccess := False; arb.io.a.wordAccess := False; arb.io.a.longwordAccess := False
  arb.io.a.refresh := False
  arb.io.c.request := False; arb.io.c.readEnable := False; arb.io.c.writeEnable := False
  arb.io.c.addr := 0; arb.io.c.dataIn := 0
  arb.io.c.byteAccess := False; arb.io.c.wordAccess := False; arb.io.c.longwordAccess := False
  arb.io.c.wideAccess := False
  arb.io.d.request := False; arb.io.d.readEnable := False; arb.io.d.writeEnable := False
  arb.io.d.addr := 0; arb.io.d.dataIn := 0
  arb.io.d.byteAccess := False; arb.io.d.wordAccess := False; arb.io.d.longwordAccess := False

  arb.io.b.request := io.req
  arb.io.b.readEnable := !io.we; arb.io.b.writeEnable := io.we
  arb.io.b.addr := io.addr; arb.io.b.dataIn := 0
  arb.io.b.byteAccess := False; arb.io.b.wordAccess := False; arb.io.b.longwordAccess := False
  arb.io.b.wideAccess := True; arb.io.b.wideIn := io.wideIn
  io.wideOut := arb.io.b.wideOut
  io.complete := arb.io.b.complete

  mock.io.REQUEST := arb.io.sdram.request;  arb.io.sdram.complete := mock.io.COMPLETE
  mock.io.READ_EN := arb.io.sdram.readEnable; mock.io.WRITE_EN := arb.io.sdram.writeEnable
  mock.io.ADDRESS_IN := arb.io.sdram.addr; mock.io.DATA_IN := arb.io.sdram.dataIn
  arb.io.sdram.dataOut := mock.io.DATA_OUT
  mock.io.BYTE_ACCESS := arb.io.sdram.byteAccess; mock.io.WORD_ACCESS := arb.io.sdram.wordAccess
  mock.io.LONGWORD_ACCESS := arb.io.sdram.longwordAccess; mock.io.REFRESH := arb.io.sdram.refresh
  mock.io.WIDE_ACCESS := arb.io.sdram.wideAccess; mock.io.WIDE_IN := arb.io.sdram.wideIn
  arb.io.sdram.wideOut := mock.io.WIDE_OUT
}

class SdramBistSpec extends AnyFunSuite {

  test("bare wide write+read via arbiter port B round-trips") {
    SimConfig.compile(new ArbWideBareHarness).doSim(seed = 5) { dut =>
      dut.clockDomain.forkStimulus(10)
      dut.io.req #= false; dut.io.we #= false; dut.io.addr #= 0; dut.io.wideIn #= 0
      dut.clockDomain.waitSampling(10)
      val pattern = (0 until 32).map(k => BigInt(0xC0 + k) << (8 * k)).sum
      def txn(we: Boolean, addr: Int, data: BigInt): Unit = {
        dut.io.we #= we; dut.io.addr #= addr; dut.io.wideIn #= data
        dut.io.req #= true; dut.clockDomain.waitSampling(); dut.io.req #= false
        var seen = false; var n = 0
        while (n < 200 && !(seen && dut.io.complete.toBoolean)) {
          if (!dut.io.complete.toBoolean) seen = true
          dut.clockDomain.waitSampling(); n += 1
        }
        dut.clockDomain.waitSampling(2)
      }
      txn(we = true, 0x40, pattern)
      txn(we = false, 0x40, 0)
      val got = dut.io.wideOut.toBigInt
      if (got != pattern) {
        println(f"exp: ${pattern}%064x")
        println(f"got: ${got}%064x")
      }
      assert(got == pattern, "wide round-trip mismatch (bytes printed above)")
    }
  }


  private def runUntilDone(dut: BistSimHarness, maxCycles: Int): Unit = {
    var n = 0
    while (dut.io.state.toInt == 0 && n < maxCycles) {
      dut.clockDomain.waitSampling(200); n += 200
    }
    assert(dut.io.state.toInt != 0, s"BIST not done after $maxCycles cycles")
  }

  private def spiReadStatus(dut: BistSimHarness, cmd: Int = 0): Seq[Int] = {
    val cd = dut.clockDomain
    val out = scala.collection.mutable.ArrayBuffer[Int]()
    dut.io.spiCsN #= false; cd.waitSampling(8)
    for (i <- 0 until 23) {
      val b = if (i == 0) cmd else 0
      var acc = 0
      for (bit <- 7 to 0 by -1) {
        dut.io.spiMosi #= ((b >> bit) & 1) == 1
        cd.waitSampling(8)
        acc = (acc << 1) | (if (dut.io.spiMiso.toBoolean) 1 else 0)
        dut.io.spiSck #= true; cd.waitSampling(8); dut.io.spiSck #= false
      }
      out += acc
    }
    cd.waitSampling(8); dut.io.spiCsN #= true; cd.waitSampling(8)
    out.toSeq
  }

  test("wide transactions through arbiter port B pass") {
    SimConfig.compile(new ArbWideSimHarness(memBits = 16, sweepWords = 512))
      .doSim(seed = 7) { dut =>
        dut.clockDomain.forkStimulus(10)
        var n = 0
        while (dut.io.state.toInt == 0 && n < 3000000) {
          dut.clockDomain.waitSampling(200); n += 200
        }
        assert(dut.io.state.toInt == 1,
          f"wide-via-arbiter: state=${dut.io.state.toInt} errs=${dut.io.errCnt.toInt} " +
          f"first phase=${dut.io.firstPhase.toInt} addr=${dut.io.firstAddr.toLong}%06x " +
          f"got=${dut.io.firstGot.toLong}%08x exp=${dut.io.firstExp.toLong}%08x")
      }
  }

  test("clean memory passes all phases") {
    SimConfig.compile(new BistSimHarness(memBits = 16, walkMax = 15, sweepWords = 256))
      .doSim(seed = 7) { dut =>
        dut.clockDomain.forkStimulus(10)
        dut.io.spiSck #= false; dut.io.spiMosi #= false; dut.io.spiCsN #= true
        runUntilDone(dut, 2000000)
        assert(dut.io.state.toInt == 1, s"expected PASS, errCnt=${dut.io.errCnt.toInt} " +
          f"firstAddr=${dut.io.firstAddr.toLong}%06x phase=${dut.io.firstPhase.toInt}")
        // status block over SPI
        val st = spiReadStatus(dut)
        assert(st(0) == 0xB5 && st(22) == 0x5A, s"framing: $st")
        assert(st(1) == 1, s"state byte should be PASS: $st")
        assert(st(7) == 0 && st(8) == 0, s"errCnt should be 0: $st")
      }
  }

  test("aliasing (mock smaller than walked range) is detected and located") {
    // 64 KB mock, walk up to bit 18: addresses 0x10000+ alias onto low ones.
    SimConfig.compile(new BistSimHarness(memBits = 16, walkMax = 18, sweepWords = 64))
      .doSim(seed = 7) { dut =>
        dut.clockDomain.forkStimulus(10)
        dut.io.spiSck #= false; dut.io.spiMosi #= false; dut.io.spiCsN #= true
        runUntilDone(dut, 2000000)
        assert(dut.io.state.toInt == 2, "aliasing must FAIL the BIST")
        assert(dut.io.firstPhase.toInt == 1, "first failure should be in the WALK phase")
        // first mismatch read back in the walk is the first aliased slot:
        // addr 0 was overwritten by the write to 0x10000
        assert(dut.io.firstAddr.toLong == 0x0L,
          f"first fail at ${dut.io.firstAddr.toLong}%06x, expected 000000 (addr 0 clobbered)")
      }
  }

  test("restart command reruns the test") {
    // sweep long enough that the rerun outlasts the SPI frame that starts it
    SimConfig.compile(new BistSimHarness(memBits = 16, walkMax = 15, sweepWords = 2048))
      .doSim(seed = 7) { dut =>
        dut.clockDomain.forkStimulus(10)
        dut.io.spiSck #= false; dut.io.spiMosi #= false; dut.io.spiCsN #= true
        runUntilDone(dut, 2000000)
        assert(dut.io.state.toInt == 1)
        spiReadStatus(dut, cmd = 0xA0)          // restart
        dut.clockDomain.waitSampling(50)
        assert(dut.io.state.toInt == 0, "restart should put BIST back to running")
        runUntilDone(dut, 2000000)
        assert(dut.io.state.toInt == 1, "second run should pass again")
      }
  }
}
