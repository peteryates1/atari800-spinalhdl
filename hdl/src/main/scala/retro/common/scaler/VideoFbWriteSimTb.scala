package retro.common.scaler
import retro.common.util._
import retro.common.video._
import retro.common.scaler._
import retro.common.sdram._

import spinal.core._
import spinal.core.sim._
import scala.collection.mutable

// Simulation for the Stage 2 framebuffer write path (VideoFbWrite).
//
// Drives a small synthetic video frame (a few active lines of a known pattern,
// with blanking and hsync/vsync), models the SDRAM write-request handshake as a
// behavioral sink, and checks every pixel landed at the expected framebuffer
// address with the expected data. Run: sbt "hdl/runMain retro.common.scaler.VideoFbWriteSimTb"
object VideoFbWriteSimTb extends App {
  val W = 8      // active pixels per line (small for sim)
  val H = 4      // active lines per frame
  val STRIDE_LOG2 = 9
  val FB_BASE = 0x1000

  SimConfig.withConfig(SpinalConfig(defaultClockDomainFrequency = FixedFrequency(57.69 MHz)))
    .compile(new VideoFbWrite(
      fbBase = FB_BASE, width = W, strideLog2 = STRIDE_LOG2, height = H,
      addrWidth = 25, fifoDepth = 64
    ))
    .doSim("fb_write", seed = 1) { dut =>
      val mem = mutable.Map[Long, Int]()          // captured framebuffer: addr -> byte
      dut.clockDomain.forkStimulus(period = 10)

      dut.io.enable    #= true
      dut.io.pixStrobe #= false
      dut.io.colour    #= 0
      dut.io.hsync     #= false
      dut.io.vsync     #= false
      dut.io.blank     #= true
      dut.clockDomain.waitSampling(5)

      // --- Behavioral SDRAM write sink: mimic SdramStatemachine handshake ---
      // COMPLETE idles high; on a REQUEST pulse it drops for a few cycles
      // (busy) then returns high, at which point we latch the write.
      dut.io.wrComplete #= true
      var pending = -1
      var busyCnt = 0
      dut.clockDomain.onSamplings {
        if (dut.io.wrReq.toBoolean && pending < 0) {
          // accept a request: capture addr/data (32-bit packed, little-endian)
          pending = dut.io.wrAddr.toLong.toInt
          val addr = dut.io.wrAddr.toLong
          val data = dut.io.wrData.toLong
          for (k <- 0 until 4) mem(addr + k) = ((data >> (8 * k)) & 0xFF).toInt
          busyCnt = 4
          dut.io.wrComplete #= false
        } else if (pending >= 0) {
          if (busyCnt > 0) busyCnt -= 1
          else { dut.io.wrComplete #= true; pending = -1 }
        }
      }

      // --- Drive a synthetic frame: pattern(x,y) = (y*16 + x) & 0xFF ---
      def pix(colour: Int, blank: Boolean): Unit = {
        dut.io.blank     #= blank
        dut.io.colour    #= colour
        dut.io.pixStrobe #= true
        dut.clockDomain.waitSampling()
        dut.io.enable    #= true
      dut.io.pixStrobe #= false
        dut.clockDomain.waitSampling(2)   // gap between pixel strobes
      }
      def pulse(sig: Bool): Unit = {
        sig #= true;  dut.clockDomain.waitSampling(2)
        sig #= false; dut.clockDomain.waitSampling(2)
      }

      // vsync to start frame
      pulse(dut.io.vsync)
      for (y <- 0 until H) {
        pulse(dut.io.hsync)                 // new line
        for (x <- 0 until W) pix((y * 16 + x) & 0xFF, blank = false)
        // a couple of blanked pixels (should be skipped)
        pix(0xEE, blank = true); pix(0xEE, blank = true)
      }
      dut.clockDomain.waitSampling(400)     // let FIFO fully drain to SDRAM

      // --- Verify ---
      var errors = 0
      for (y <- 0 until H; x <- 0 until W) {
        val addr = FB_BASE.toLong + (y.toLong << STRIDE_LOG2) + x
        val exp  = (y * 16 + x) & 0xFF
        mem.get(addr) match {
          case Some(v) if v == exp =>
          case Some(v) => errors += 1; println(f"MISMATCH @0x$addr%x: got 0x$v%02x exp 0x$exp%02x")
          case None    => errors += 1; println(f"MISSING   @0x$addr%x (exp 0x$exp%02x)")
        }
      }
      // blanked pixels must NOT have been written
      val blankAddrs = mem.keys.count(a => (mem(a) == 0xEE))
      if (blankAddrs != 0) { errors += 1; println(s"ERROR: $blankAddrs blanked pixels were written") }

      println(s"frames=${dut.io.frameCount.toInt} overflow=${dut.io.overflow.toBoolean} writes=${mem.size} errors=$errors")
      if (errors == 0 && mem.size == W * H) println("FB WRITE SIM: PASS")  // packed: mem.size counts bytes
      else { println("FB WRITE SIM: FAIL"); simFailure("framebuffer write mismatch") }
    }
}
