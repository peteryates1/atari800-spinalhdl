package atari800

import spinal.core._
import spinal.core.sim._
import scala.collection.mutable

// Simulation for the Stage 2 read/scaler path (VideoFbRead).
// Pre-fills a framebuffer, models the SDRAM read handshake, captures one output
// frame and checks nearest-neighbour upscale: out[oy][ox] == fb[oy*srcH/vActive][ox*srcW/hActive].
// Run: sbt "atari/runMain atari800.VideoFbReadSimTb"
object VideoFbReadSimTb extends App {
  val SRC_W = 8; val SRC_H = 4
  val STRIDE_LOG2 = 9
  val FB_BASE = 0x400
  val HA = 16; val VA = 12

  SimConfig.withConfig(SpinalConfig(defaultClockDomainFrequency = FixedFrequency(74.25 MHz)))
    .compile(new VideoFbRead(
      srcW = SRC_W, srcH = SRC_H, strideLog2 = STRIDE_LOG2, fbBase = FB_BASE,
      hActive = HA, hFront = 2, hSync = 4, hBack = 48,   // wide back porch: room to fetch a src row
      vActive = VA, vFront = 2, vSync = 2, vBack = 4, addrWidth = 25
    ))
    .doSim("fb_read", seed = 1) { dut =>
      // framebuffer contents: fb[y][x] = (y*16 + x) & 0xFF
      def fb(addr: Long): Int = {
        val rel = (addr - FB_BASE).toInt
        val y = rel >> STRIDE_LOG2
        val x = rel & ((1 << STRIDE_LOG2) - 1)
        if (y >= 0 && y < SRC_H && x >= 0 && x < SRC_W) (y * 16 + x) & 0xFF else 0
      }

      dut.clockDomain.forkStimulus(period = 10)
      dut.io.rdComplete #= true
      dut.io.rdData #= 0

      // SDRAM read model: on rdReq pulse, go busy 1 cycle then return data.
      var busy = 0
      var addrLatch = 0L
      dut.clockDomain.onSamplings {
        if (busy == 0 && dut.io.rdReq.toBoolean) {
          addrLatch = dut.io.rdAddr.toLong
          busy = 2
          dut.io.rdComplete #= false
        } else if (busy > 0) {
          busy -= 1
          if (busy == 0) { dut.io.rdData #= fb(addrLatch); dut.io.rdComplete #= true }
        }
      }

      // capture: wait for a frame boundary, then record one full frame
      val grid = Array.fill(VA, HA)(-1)
      var col = 0; var row = -1
      var prevVs = false; var prevDe = false; var framesSeen = 0
      var capturing = false; var captured = false

      var ticks = 0
      while (!captured && ticks < 200000) {
        dut.clockDomain.waitSampling()
        ticks += 1
        val vs = dut.io.vs.toBoolean
        val de = dut.io.de.toBoolean
        if (vs && !prevVs) { framesSeen += 1; if (framesSeen == 2) { capturing = true; row = -1 } else if (capturing) captured = true }
        if (capturing && !captured) {
          if (de && !prevDe) { row += 1; col = 0 }        // new active line
          if (de && row >= 0 && row < VA && col < HA) { grid(row)(col) = dut.io.pix.toInt; col += 1 }
        }
        prevVs = vs; prevDe = de
      }

      // verify
      var errors = 0
      for (oy <- 0 until VA; ox <- 0 until HA) {
        val exp = fb(FB_BASE.toLong + ((oy * SRC_H / VA).toLong << STRIDE_LOG2) + (ox * SRC_W / HA))
        if (grid(oy)(ox) != exp) { errors += 1; if (errors <= 12) println(f"out[$oy%2d][$ox%2d]=${grid(oy)(ox)}%3d exp $exp%3d") }
      }
      println("captured grid:")
      for (oy <- 0 until VA) println(grid(oy).map(v => f"$v%3d").mkString(" "))
      println(s"errors=$errors ticks=$ticks")
      if (errors == 0) println("FB READ SIM: PASS") else { println("FB READ SIM: FAIL"); simFailure("upscale mismatch") }
    }
}
