package retro.common.sdram
import retro.common.util._
import retro.common.video._
import retro.common.scaler._
import retro.common.sdram._

import spinal.core._
import spinal.core.sim._

// Simulation for the 3-port SDRAM arbiter, driven through Arb3Harness
// (SdramArbiter3 + faithful RTL MockSdram). Verifies each port reads/writes
// correctly through the arbiter, plus a concurrent burst.
// Run: sbt "hdl/runMain retro.common.sdram.SdramArbiter3SimTb"
object SdramArbiter3SimTb extends App {
  SimConfig.withConfig(SpinalConfig(defaultClockDomainFrequency = FixedFrequency(100 MHz)))
    .compile(new Arb3Harness)
    .doSim("arb3", seed = 1) { dut =>
      dut.clockDomain.forkStimulus(period = 10)
      for (p <- Seq(dut.io.a, dut.io.b, dut.io.c)) {
        p.request #= false; p.readEnable #= false; p.writeEnable #= false
        p.addr #= 0; p.dataIn #= 0; p.byteAccess #= true; p.wordAccess #= false; p.longwordAccess #= false
      }
      dut.clockDomain.waitSampling(5)

      def waitHigh(get: => Boolean): Unit = { var g=0; while(!get && g<2000){dut.clockDomain.waitSampling();g+=1} }
      def waitLow (get: => Boolean): Unit = { var g=0; while( get && g<2000){dut.clockDomain.waitSampling();g+=1} }

      // Port A: SdramStatemachine-style COMPLETE (idle high -> low -> high)
      def doA(we: Boolean, addr: Int, data: Int): Int = {
        dut.io.a.addr #= addr; dut.io.a.dataIn #= data
        dut.io.a.writeEnable #= we; dut.io.a.readEnable #= !we
        dut.io.a.request #= true; dut.clockDomain.waitSampling(); dut.io.a.request #= false
        waitLow(dut.io.a.complete.toBoolean); waitHigh(dut.io.a.complete.toBoolean)
        val r = dut.io.a.dataOut.toLong.toInt & 0xFF
        dut.io.a.writeEnable #= false; dut.io.a.readEnable #= false; dut.clockDomain.waitSampling(2); r
      }
      // Ports B/C: single-cycle complete pulse
      def doB(addr: Int, data: Int): Unit = {
        dut.io.b.addr #= addr; dut.io.b.dataIn #= data; dut.io.b.writeEnable #= true
        dut.io.b.request #= true; dut.clockDomain.waitSampling(); dut.io.b.request #= false
        waitHigh(dut.io.b.complete.toBoolean)
        dut.io.b.writeEnable #= false; dut.clockDomain.waitSampling(2)
      }
      def doC(addr: Int): Int = {
        dut.io.c.addr #= addr; dut.io.c.readEnable #= true
        dut.io.c.request #= true; dut.clockDomain.waitSampling(); dut.io.c.request #= false
        waitHigh(dut.io.c.complete.toBoolean)
        val r = dut.io.c.dataOut.toLong.toInt & 0xFF
        dut.io.c.readEnable #= false; dut.clockDomain.waitSampling(2); r
      }

      var errors = 0

      // 1. Port A write then read-back
      for (i <- 0 until 8) doA(we = true, 0x300 + i, 0xA0 + i)
      for (i <- 0 until 8) { val r = doA(we = false, 0x300 + i, 0); if (r != (0xA0 + i)) { errors += 1; println(f"A rd 0x${0x300+i}%x=$r%02x exp ${0xA0+i}%02x") } }

      // 2. Port B writes, read back via Port C (cross-port check)
      for (i <- 0 until 8) doB(0x100 + i, 0xB0 + i)
      for (i <- 0 until 8) { val r = doC(0x100 + i); if (r != (0xB0 + i)) { errors += 1; println(f"C rd 0x${0x100+i}%x=$r%02x exp ${0xB0+i}%02x") } }

      // 3. Concurrent burst: A read, B write, C read fired together
      doA(we = true, 0x400, 0x5A)   // prefill A-region
      doB(0x402, 0x3C)              // prefill C-region via B
      val aT = fork { val r = doA(we=false, 0x400, 0); if (r != 0x5A) { errors += 1; println(f"burst A=$r%02x exp 5a") } }
      val bT = fork { doB(0x401, 0x77) }
      val cT = fork { val r = doC(0x402); if (r != 0x3C) { errors += 1; println(f"burst C=$r%02x exp 3c") } }
      aT.join(); bT.join(); cT.join()
      val vb = doC(0x401); if (vb != 0x77) { errors += 1; println(f"burst B write via C=$vb%02x exp 77") }

      println(s"errors=$errors")
      if (errors == 0) println("ARBITER3 SIM: PASS") else { println("ARBITER3 SIM: FAIL"); simFailure("arbiter mismatch") }
    }
}
