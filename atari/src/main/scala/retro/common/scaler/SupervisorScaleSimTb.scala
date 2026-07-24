package retro.common.scaler
import retro.common.util._
import retro.common.video._
import retro.common.scaler._
import retro.common.sdram._

import spinal.core._
import spinal.core.sim._

// Demonstrates why integer scaling fixes the supervisor-text shimmer. Renders a
// 1-px vertical-stripe source pattern (…#.#.#…) through VideoFbRead2 and prints
// the run-lengths of the upscaled output row:
//   * non-integer ratio  -> stripes are 2 or 3 px wide (uneven) => sub-pixel
//     wobble, which shimmers on HW.
//   * integer ratio (x4) -> every stripe is exactly N px (even) => crisp.
// The DDA math is deterministic, so this confirms the 320->1280 (x4) supervisor
// scaler produces geometrically-exact, wobble-free output.
// Run: sbt "atari/runMain retro.common.scaler.SupervisorScaleSimTb"
object SupervisorScaleSimTb extends App {
  def runCase(name: String, srcW: Int, hActive: Int): Boolean = {
    val SRC_H = 4; val STRIDE_LOG2 = 9; val FB_BASE = 0x400; val VA = 12
    var interiorEven = false
    SimConfig.compile(new VideoFbRead2(
      srcW = srcW, srcH = SRC_H, strideLog2 = STRIDE_LOG2, fbBase = FB_BASE,
      hActive = hActive, hFront = 2, hSync = 4, hBack = 16,
      vActive = VA, vFront = 2, vSync = 2, vBack = 4, addrWidth = 25))
      .doSim(s"scale_$name", seed = 1) { dut =>
        // source: 1-px vertical stripes (even x -> white, odd x -> black)
        def fb(addr: Long): Int = {
          val rel = (addr - FB_BASE).toInt
          val y = rel >> STRIDE_LOG2; val x = rel & ((1 << STRIDE_LOG2) - 1)
          if (y >= 0 && y < SRC_H && x >= 0 && x < srcW) (if ((x & 1) == 0) 255 else 0) else 0
        }
        val pixCd = ClockDomain(dut.io.clkPixel); val fetchCd = ClockDomain(dut.io.clkFetch)
        pixCd.forkStimulus(10); fetchCd.forkStimulus(13)
        dut.io.enable #= true; dut.io.rdComplete #= true; dut.io.rdData #= 0
        var busy = 0; var addrLatch = 0L
        fetchCd.onSamplings {          // 256-bit (32-byte) wide reads
          if (busy == 0 && dut.io.rdReq.toBoolean) { addrLatch = dut.io.rdAddr.toLong; busy = 2; dut.io.rdComplete #= false }
          else if (busy > 0) { busy -= 1; if (busy == 0) {
            val wide = (0 until 32).map(k => BigInt(fb(addrLatch + k)) << (8 * k)).reduce(_ | _)
            dut.io.rdData #= wide; dut.io.rdComplete #= true } }
        }
        val rowPix = Array.fill(hActive)(-1)
        var col = 0; var row = -1; var prevVs = false; var prevDe = false
        var frames = 0; var capturing = false; var done = false; var ticks = 0
        while (!done && ticks < 400000) {
          pixCd.waitSampling(); ticks += 1
          val vs = dut.io.vs.toBoolean; val de = dut.io.de.toBoolean
          if (vs && !prevVs) { frames += 1; if (frames == 2) capturing = true }
          if (capturing) {
            if (de && !prevDe) { row += 1; col = 0 }
            if (de && row == 0 && col < hActive) { rowPix(col) = dut.io.pix.toInt; col += 1 }
            if (row >= 1) done = true
          }
          prevVs = vs; prevDe = de
        }
        val runs = scala.collection.mutable.ArrayBuffer[Int]()
        var i = 0
        while (i < hActive) { var j = i; while (j < hActive && rowPix(j) == rowPix(i)) j += 1; runs += (j - i); i = j }
        val interior = runs.drop(1).dropRight(1)
        interiorEven = interior.nonEmpty && interior.forall(_ == interior.head)
        val pat = rowPix.map(v => if (v > 127) '#' else '.').mkString
        println(f"[$name%-16s] srcW=$srcW hActive=$hActive ratio=${hActive.toDouble / srcW}%.3f")
        println(s"[$name] output: $pat")
        println(s"[$name] runs  : ${runs.mkString(",")}")
        println(s"[$name] interior stripes: ${if (interiorEven) "EVEN (crisp)" else "UNEVEN (wobble)"}")
      }
    interiorEven
  }

  val nonInt = runCase("noninteger_2.5x", srcW = 32, hActive = 80)   // 2.5x -> uneven
  val integer = runCase("integer_4x",     srcW = 32, hActive = 128)  // 4x   -> even
  println("=" * 60)
  println(s"non-integer even? $nonInt (expect false)   integer even? $integer (expect true)")
  if (!nonInt && integer) println("SUPERVISOR SCALE SIM: PASS (integer scaling is wobble-free)")
  else { println("SUPERVISOR SCALE SIM: unexpected"); simFailure("scale demo mismatch") }
}
