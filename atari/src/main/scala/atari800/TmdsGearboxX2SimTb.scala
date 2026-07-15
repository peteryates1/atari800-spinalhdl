package atari800

import spinal.core._
import spinal.core.sim._

// Verify the ODDRX2F 10:1 gearbox: feed a stream of 20-bit 2-pixel words (one
// load pulse per 5-SCLK window) and check the 5 emitted nibbles reconstruct the
// word LSB-first (i.e. nibble c == word[c*4 +: 4]) — the TMDS serialisation order.
object TmdsGearboxX2SimTb extends App {
  SimConfig.compile(new TmdsGearboxX2).doSim { dut =>
    dut.clockDomain.forkStimulus(10)
    dut.io.load #= false
    dut.io.word #= 0
    dut.clockDomain.waitSampling(2)

    val rng = new scala.util.Random(1)
    var fails = 0
    val N = 300
    for (w <- 0 until N) {
      val word = BigInt(20, rng)
      val nibbles = Array.fill(5)(0)
      for (cyc <- 0 until 5) {
        dut.io.load #= (cyc == 0)
        dut.io.word #= (if (cyc == 0) word else BigInt(0))
        sleep(1)                                   // let combinational settle
        nibbles(cyc) = dut.io.nibble.toInt         // read this cycle's nibble
        dut.clockDomain.waitSampling()             // advance one SCLK
      }
      var recon = BigInt(0)
      for (i <- 0 until 5) recon |= BigInt(nibbles(i)) << (i * 4)
      if (recon != word) {
        fails += 1
        if (fails <= 6)
          println(f"FAIL w$w%d: word=0x$word%05x recon=0x$recon%05x nibbles=${nibbles.map(n => f"$n%x").mkString(" ")}")
      }
    }
    println(if (fails == 0) s"PASS: all $N windows serialise LSB-first correctly"
            else s"FAIL: $fails/$N windows mismatched")
  }
}
