package retro.common.scaler
import retro.common.util._
import retro.common.video._
import retro.common.scaler._
import retro.common.sdram._

import spinal.core._
import spinal.core.sim._

// Stage 2 integration sim: drive a source frame into the write path, let it flow
// through the arbiter into MockSdram, then capture a read-side output frame and
// verify the write->SDRAM->read->upscale round-trip.
// Run: sbt "atari/runMain retro.common.scaler.FbPipelineSimTb"
object FbPipelineSimTb extends App {
  val SRC_W = 8; val SRC_H = 4; val HA = 16; val VA = 12

  SimConfig.withConfig(SpinalConfig(defaultClockDomainFrequency = FixedFrequency(74.25 MHz)))
    .compile(new FbPipeline(srcW = SRC_W, srcH = SRC_H, hActive = HA, vActive = VA))
    .doSim("fb_pipeline", seed = 1) { dut =>
      dut.clockDomain.forkStimulus(period = 10)
      dut.io.pixStrobe #= false; dut.io.colour #= 0
      dut.io.hsyncIn #= false; dut.io.vsyncIn #= false; dut.io.blank #= true
      dut.clockDomain.waitSampling(5)

      def pulse(sig: Bool): Unit = { sig #= true; dut.clockDomain.waitSampling(2); sig #= false; dut.clockDomain.waitSampling(2) }
      def pix(colour: Int, blank: Boolean): Unit = {
        dut.io.blank #= blank; dut.io.colour #= colour
        dut.io.pixStrobe #= true; dut.clockDomain.waitSampling()
        dut.io.pixStrobe #= false; dut.clockDomain.waitSampling(2)
      }

      // --- drive one source frame: fb[y][x] = (y*16 + x) & 0xFF ---
      pulse(dut.io.vsyncIn)
      for (y <- 0 until SRC_H) {
        pulse(dut.io.hsyncIn)
        for (x <- 0 until SRC_W) pix((y * 16 + x) & 0xFF, blank = false)
        pix(0xEE, blank = true)
      }
      dut.io.blank #= true
      dut.clockDomain.waitSampling(800)   // let write drain to SDRAM and reads settle

      // --- capture one read output frame ---
      val grid = Array.fill(VA, HA)(-1)
      var col = 0; var row = -1
      var prevVs = false; var prevDe = false; var framesSeen = 0
      var capturing = false; var captured = false; var ticks = 0
      while (!captured && ticks < 300000) {
        dut.clockDomain.waitSampling(); ticks += 1
        val vs = dut.io.vs.toBoolean; val de = dut.io.de.toBoolean
        if (vs && !prevVs) { framesSeen += 1; if (framesSeen == 1) { capturing = true; row = -1 } else if (capturing) captured = true }
        if (capturing && !captured) {
          if (de && !prevDe) { row += 1; col = 0 }
          if (de && row >= 0 && row < VA && col < HA) { grid(row)(col) = dut.io.pix.toInt; col += 1 }
        }
        prevVs = vs; prevDe = de
      }

      var errors = 0
      for (oy <- 0 until VA; ox <- 0 until HA) {
        val exp = ((oy * SRC_H / VA) * 16 + (ox * SRC_W / HA)) & 0xFF
        if (grid(oy)(ox) != exp) { errors += 1; if (errors <= 12) println(f"out[$oy%2d][$ox%2d]=${grid(oy)(ox)}%3d exp $exp%3d") }
      }
      println("captured grid:")
      for (oy <- 0 until VA) println(grid(oy).map(v => f"$v%3d").mkString(" "))
      println(s"overflow=${dut.io.overflow.toBoolean} errors=$errors ticks=$ticks")
      if (errors == 0) println("FB PIPELINE SIM: PASS") else { println("FB PIPELINE SIM: FAIL"); simFailure("pipeline mismatch") }
    }
}
