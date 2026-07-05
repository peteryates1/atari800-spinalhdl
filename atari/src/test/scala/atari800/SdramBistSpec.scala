package atari800

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

class SdramBistSpec extends AnyFunSuite {

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
