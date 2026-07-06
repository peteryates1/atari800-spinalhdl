package atari800

import org.scalatest.funsuite.AnyFunSuite
import spinal.core._
import spinal.core.sim._

// The batching drain: 8 sequential quads must leave as ONE 256-bit write;
// partial batches (line tails, frame ends) must flush correctly as singles.
class VideoFbWriteSpec extends AnyFunSuite {

  private def run(width: Int, lines: Int)(check: (Map[Long, Int], Int, Int) => Unit): Unit = {
    SimConfig.compile(new VideoFbWrite(
      fbBase = 0x100, width = width, strideLog2 = 6, height = 4,
      addrWidth = 25, fifoDepth = 64)).doSim(seed = 3) { dut =>
      dut.clockDomain.forkStimulus(10)
      dut.io.enable #= true
      dut.io.pixStrobe #= false; dut.io.hsync #= false; dut.io.vsync #= false
      dut.io.blank #= false; dut.io.colour #= 0
      dut.io.wrComplete #= false
      dut.io.hStart #= 0; dut.io.vSkip #= 0

      // SDRAM mock with ARBITER-STYLE deferred sampling: the flags/addr/data
      // are read N cycles after the request pulse, at "serve" time - exactly
      // when the real controller snapshots them. A client that only asserts
      // its flags during the request pulse fails here (as it did on HW).
      val mem = scala.collection.mutable.Map[Long, Int]()
      var wides = 0; var singles = 0
      fork { while (true) { dut.clockDomain.waitSampling()
        if (dut.io.wrReq.toBoolean) {
          dut.clockDomain.waitSampling(3)       // arbiter serve delay
          val addr = dut.io.wrAddr.toLong
          if (dut.io.wrWide.toBoolean) {
            val v = dut.io.wrWideData.toBigInt
            for (k <- 0 until 32) mem(addr + k) = ((v >> (8 * k)) & 0xFF).toInt
            wides += 1
          } else {
            val v = dut.io.wrData.toLong
            for (k <- 0 until 4) mem(addr + k) = ((v >> (8 * k)) & 0xFF).toInt
            singles += 1
          }
          dut.clockDomain.waitSampling(4)
          dut.io.wrComplete #= true
          dut.clockDomain.waitSampling()
          dut.io.wrComplete #= false
        } } }

      def pixel(v: Int): Unit = {
        dut.io.colour #= v; dut.io.pixStrobe #= true
        dut.clockDomain.waitSampling()
        dut.io.pixStrobe #= false
        dut.clockDomain.waitSampling(2)
      }
      def hsyncPulse(): Unit = {
        dut.io.hsync #= true; dut.clockDomain.waitSampling(3)
        dut.io.hsync #= false; dut.clockDomain.waitSampling(3)
      }

      hsyncPulse()                             // arm: first hsync starts y=0
      for (y <- 0 until lines) {
        for (x <- 0 until width) pixel((y * 64 + x + 1) & 0xFF)
        hsyncPulse()
      }
      dut.clockDomain.waitSampling(400)        // idle timeout flushes any tail

      for (y <- 0 until lines; x <- 0 until width) {
        val addr = 0x100L + y * 64 + x
        val exp = (y * 64 + x + 1) & 0xFF
        assert(mem.getOrElse(addr, -1) == exp,
          f"byte at $addr%x = ${mem.getOrElse(addr, -1)}%02x, expected $exp%02x " +
          f"(wides=$wides singles=$singles)")
      }
      check(mem.toMap, wides, singles)
    }
  }

  test("full lines drain as wide writes only") {
    // width 32 = exactly one 8-quad batch per line
    run(width = 32, lines = 3) { (_, wides, singles) =>
      assert(wides == 3, s"expected 3 wide writes, got $wides")
      assert(singles == 0, s"expected no singles, got $singles")
    }
  }

  test("partial batches flush correctly as singles") {
    // width 16 = 4 quads per line: never a full batch; every line flushes
    run(width = 16, lines = 3) { (_, wides, singles) =>
      assert(wides == 0, s"expected no wide writes, got $wides")
      assert(singles == 12, s"expected 12 singles, got $singles")
    }
  }
}
