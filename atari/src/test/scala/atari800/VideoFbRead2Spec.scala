package atari800

import org.scalatest.funsuite.AnyFunSuite
import spinal.core._
import spinal.core.sim._

// Repeatable unit tests for the dual-clock framebuffer read/scaler.
//
// Two variants of the SDRAM read model are exercised:
//   * idleHigh: rdComplete idles HIGH, drops during a transaction (the original
//     hand-written mock).
//   * idleLow : rdComplete idles LOW and pulses HIGH for one cycle on completion
//     — this is how the real SdramArbiter3 port C actually behaves. VideoFbRead2
//     was previously only tested against idleHigh, so the real protocol was never
//     covered in sim. Both must pass.
class VideoFbRead2Spec extends AnyFunSuite {

  val SRC_H = 4; val STRIDE_LOG2 = 9; val FB_BASE = 0x400
  val VA = 12

  private def compile(srcW: Int, ha: Int, srcH: Int = SRC_H, fetchBytes: Int = 4) =
    SimConfig.withConfig(SpinalConfig()).compile(
      new VideoFbRead2(
        srcW = srcW, srcH = srcH, strideLog2 = STRIDE_LOG2, fbBase = FB_BASE,
        hActive = ha, hFront = 2, hSync = 4, hBack = 48,
        vActive = VA, vFront = 2, vSync = 2, vBack = 4, addrWidth = 25,
        fetchBytes = fetchBytes))

  // srcW=8: power-of-two width. srcW=12: non-power-of-two, like the real
  // 384-wide framebuffer — catches cache addressing that only works for
  // power-of-two line widths (bank ## srcX concat = bank*2^n + srcX).
  private lazy val compiledPow2    = compile(srcW = 8,  ha = 16)
  private lazy val compiledNonPow2 = compile(srcW = 12, ha = 24)
  // 2.4x vertical (srcH=5, VA=12): alternating span-2/span-3 rows, like the
  // real 288->720 (2.5x). Span-2 rows leave no slack for a once-per-line kick.
  private lazy val compiled24x     = compile(srcW = 8,  ha = 16, srcH = 5)
  // the real configuration: 256-bit wide fetches (srcW multiple of 32)
  private lazy val compiledWide    = compile(srcW = 32, ha = 64, fetchBytes = 32)

  private def fb(srcW: Int, srcH: Int)(addr: Long): Int = {
    val rel = (addr - FB_BASE).toInt
    val y = rel >> STRIDE_LOG2; val x = rel & ((1 << STRIDE_LOG2) - 1)
    if (y >= 0 && y < srcH && x >= 0 && x < srcW) (y * 16 + x) & 0xFF else 0
  }

  // 32-bit little-endian read at a 4-aligned byte address, as the real
  // SdramStatemachine returns it (DATA_OUT[7:0] = byte at addr).
  private def fb32(srcW: Int, srcH: Int)(addr: Long): Long =
    (0 until 4).map(k => fb(srcW, srcH)(addr + k).toLong << (8 * k)).sum

  // Runs a capture over `frames` consecutive frames and returns the total
  // error count against the expected upscale (every frame must be correct —
  // late bank swaps show up as wrong rows, the sim analogue of the vertical
  // jitter seen on hardware).
  // enableAfterTicks > 0 models the hardware reset skew: the sys domain
  // (arbiter/SDRAM) comes out of reset long after the BOOT-reset pixel/fetch
  // domains start running. Requests fired in that window would be swallowed.
  // latency = fetch-clock cycles per SDRAM transaction (10 ~= real hardware).
  private def runScaler(idleLow: Boolean, enableAfterTicks: Int = 0,
                        latency: Int = 3, frames: Int = 1,
                        srcW: Int = 8, ha: Int = 16, srcH: Int = SRC_H,
                        latencySpread: Int = 0,
                        midDisableTicks: Int = 0,
                        wide: Boolean = false): Int = {
    val fb32v = fb32(srcW, srcH) _
    val fbv   = fb(srcW, srcH) _
    def fbWide(addr: Long): BigInt =
      (0 until 32).map(k => BigInt(fbv(addr + k)) << (8 * k)).sum
    def serve(addr: Long): BigInt = if (wide) fbWide(addr) else BigInt(fb32v(addr))
    val dutC  = if (wide) compiledWide
                else if (srcH != SRC_H) compiled24x
                else if (srcW == 8) compiledPow2 else compiledNonPow2
    val rng   = new scala.util.Random(42)
    def lat() = if (latencySpread == 0) latency else latency + rng.nextInt(latencySpread)
    var errors = -1
    dutC.doSim(if (idleLow) "idleLow" else "idleHigh", seed = 1) { dut =>
      val pixCd   = ClockDomain(dut.io.clkPixel)
      val fetchCd = ClockDomain(dut.io.clkFetch)
      pixCd.forkStimulus(10)
      fetchCd.forkStimulus(13)

      dut.io.enable #= (enableAfterTicks == 0)
      dut.io.rdComplete #= !idleLow
      dut.io.rdData #= 0
      dut.io.readBuf #= 0

      if (enableAfterTicks > 0) {
        // While "in reset", any read request would be lost on hardware — so
        // require that none are issued at all.
        for (_ <- 0 until enableAfterTicks) {
          fetchCd.waitSampling()
          assert(!dut.io.rdReq.toBoolean, "rdReq fired while sys domain still in reset")
        }
        dut.io.enable #= true
      }

      var dropInFlight = false   // set while "console reset" holds the sys domain

      if (midDisableTicks > 0) fork {
        // Console reset mid-stream: run a while, then disable + drop whatever
        // transaction was in flight (the arbiter forgets it), then re-enable.
        fetchCd.waitSampling(300)
        dut.io.enable #= false
        dropInFlight = true
        fetchCd.waitSampling(midDisableTicks)
        dropInFlight = false
        dut.io.enable #= true
      }

      // SDRAM read model on the fetch clock.
      var busy = 0; var addrLatch = 0L
      fetchCd.onSamplings {
        if (dropInFlight && busy > 0) { busy = 0; dut.io.rdComplete #= !idleLow }
        if (sys.env.contains("FB_TRACE")) {
          val ul = dut.fetch.unload.toInt
          if (ul != 0 && dut.fetch.ySnap.toInt == 4)
            println(f"WR y=${dut.fetch.ySnap.toInt} b=${dut.fetch.bSnap.toInt} fx=${dut.fetch.fx.toInt} ul=$ul data=${dut.fetch.dataR.toLong}%08x")
          if (dut.io.rdReq.toBoolean && (dut.io.rdAddr.toLong - 0x400) >> 9 == 4)
            println(f"RD addr=${dut.io.rdAddr.toLong}%x (row4)")
        }
        if (idleLow) {
          // idle LOW, pulse HIGH one cycle on completion (arbiter port C behaviour)
          if (busy == 0 && dut.io.rdReq.toBoolean) { addrLatch = dut.io.rdAddr.toLong; busy = lat(); dut.io.rdComplete #= false }
          else if (busy > 1) { busy -= 1; dut.io.rdComplete #= false }
          else if (busy == 1) { busy = 0; dut.io.rdData #= serve(addrLatch); dut.io.rdComplete #= true }
          else { dut.io.rdComplete #= false }
        } else {
          // idle HIGH, drop during the transaction (original mock)
          if (busy == 0 && dut.io.rdReq.toBoolean) { addrLatch = dut.io.rdAddr.toLong; busy = lat(); dut.io.rdComplete #= false }
          else if (busy > 0) { busy -= 1; if (busy == 0) { dut.io.rdData #= serve(addrLatch); dut.io.rdComplete #= true } }
        }
      }

      val grid = Array.fill(VA, ha)(-1)
      var errs = 0; var framesChecked = 0
      var col = 0; var row = -1; var prevVs = false; var prevDe = false
      var framesSeen = 0; var capturing = false; var ticks = 0
      val maxTicks = 400000 * frames
      while (framesChecked < frames && ticks < maxTicks) {
        pixCd.waitSampling(); ticks += 1
        val vs = dut.io.vs.toBoolean; val de = dut.io.de.toBoolean
        if (vs && !prevVs) {
          framesSeen += 1
          if (capturing) {
            // frame finished: check it
            var frameErrs = 0
            for (oy <- 0 until VA; ox <- 0 until ha) {
              val exp = ((oy * srcH / VA) * 16 + (ox * srcW / ha)) & 0xFF
              if (grid(oy)(ox) != exp) { errs += 1; frameErrs += 1 }
            }
            if (frameErrs > 0 && sys.env.contains("FB_DEBUG")) {
              println(s"frame $framesChecked: $frameErrs errs")
              for (oy <- 0 until VA) {
                val got = grid(oy)(0) match { case -1 => "??"; case v => (v >> 4).toString }
                val exp = oy * srcH / VA
                if (grid(oy)(0) != ((exp*16) & 0xFF)) println(s"  row $oy: got srcRow $got exp $exp")
              }
            }
            framesChecked += 1
            for (r <- grid; c <- r.indices) r(c) = -1
          }
          if (framesSeen >= 2 && framesChecked < frames) { capturing = true; row = -1 }
        }
        if (capturing && sys.env.contains("FB_TRACE") && framesChecked == 0) {
          if (de && !prevDe) {
            val db = dut.pix.dispBank.toInt
            val tags = (0 until 4).map(i => s"${if (dut.pix.bankOk(i).toBoolean) dut.pix.bankY(i).toInt.toString else "-"}").mkString(",")
            println(s"line row=${row+1} disp=B$db tags=[$tags] fY=${dut.pix.fY.toInt} wrB=${dut.pix.wrB.toInt}")
          }
        }
        if (capturing) {
          if (de && !prevDe) { row += 1; col = 0 }
          if (de && row >= 0 && row < VA && col < ha) { grid(row)(col) = dut.io.pix.toInt; col += 1 }
        }
        prevVs = vs; prevDe = de
      }

      assert(framesChecked == frames, s"capture timed out — fetch likely stalled (checked $framesChecked/$frames frames)")
      errors = errs
    }
    errors
  }

  test("dual-clock nearest-neighbour upscale (idle-high complete)") {
    assert(runScaler(idleLow = false) == 0)
  }

  test("dual-clock upscale with arbiter-style idle-low pulse complete") {
    assert(runScaler(idleLow = true) == 0)
  }

  test("no requests before enable; full recovery after late enable (HW reset skew)") {
    assert(runScaler(idleLow = true, enableAfterTicks = 3000) == 0)
  }

  test("stable frames with realistic SDRAM latency (jitter regression)") {
    // latency 10 makes per-line refetching impossible within a line-time —
    // the pre-fix design produced late bank swaps (wrong rows / jitter) here.
    assert(runScaler(idleLow = true, latency = 10, frames = 3) == 0)
  }

  test("no stray lines when a fetch takes longer than one line-time") {
    // latency 25 => line fetch spans >1 but <2 output lines. With only
    // one line of prefetch lookahead the swap misses lineEnd at every source
    // row transition and single output lines show the previous row — the
    // horizontal-line artifact seen on hardware. Two-line lookahead fixes it.
    assert(runScaler(idleLow = true, latency = 25, frames = 3) == 0)
  }

  test("stable under randomised SDRAM contention spikes") {
    // Latency varies 8..68 per transaction (seeded): occasional line fetches
    // exceed a 2-line budget, as Atari + write traffic cause on hardware.
    // Needs a prefetch cushion deeper than one row to ride the spikes out.
    assert(runScaler(idleLow = true, latency = 8, latencySpread = 60, frames = 4) == 0)
  }

  test("sustained 2.4x load with alternating span-2 rows") {
    // Non-uniform spans (3,2,3,2,2) at fetch ~1.4 line-times: a prefetch that
    // can only start once per output line delivers rows no faster than they
    // are consumed, the ring cushion never builds, and the display runs
    // chronically a row behind (measured on HW: ~half of lines wrong).
    assert(runScaler(idleLow = true, latency = 30, frames = 4, srcH = 5) == 0)
  }

  test("recovers after mid-stream disable (console reset drops in-flight read)") {
    assert(runScaler(idleLow = true, latency = 10, frames = 6, midDisableTicks = 400) == 0)
  }

  test("256-bit wide fetches (the real hardware configuration)") {
    assert(runScaler(idleLow = true, latency = 6, frames = 2,
                     srcW = 32, ha = 64, wide = true) == 0)
  }

  test("non-power-of-two srcW (cache bank addressing, like the real 384)") {
    // bank ## srcX addressing implies bank stride 2^log2Up(srcW); the cache
    // must be sized for that stride or non-power-of-two widths (like the
    // hardware's 384) read past the end of the memory in the upper bank.
    assert(runScaler(idleLow = true, latency = 10, frames = 3, srcW = 12, ha = 24) == 0)
  }
}
