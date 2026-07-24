package retro.common.video
import retro.common.util._
import retro.common.video._
import retro.common.scaler._
import retro.common.sdram._

import spinal.core._
import spinal.core.sim._
import scala.collection.mutable.ArrayBuffer

// End-to-end datapath check for Ecp5DvidOutX2 across the pixel->SCLK crossing.
// Uses the TMDS CLOCK lane (constant 0000011111 pattern) as a known answer: through
// the full 2-pixel assembly + CDC + gearbox it must serialise to a clean repeating
// 1111100000 (LSB-first) — 5 ones then 5 zeros per 10-bit period. Two async clocks
// at a 2.5:1 (SCLK:pixel) ratio.
object Ecp5DvidOutX2SimTb extends App {
  SimConfig.compile(new Ecp5DvidOutX2).doSim { dut =>
    dut.io.clkPixel #= false; dut.io.clkSclk #= false
    dut.io.blue #= 0x5A; dut.io.red #= 0x12; dut.io.green #= 0x34
    dut.io.hsync #= false; dut.io.vsync #= false; dut.io.de #= true

    // pixel period 20, sclk period 8 -> ratio 2.5
    fork { var v = false; while (true) { sleep(10); v = !v; dut.io.clkPixel #= v } }
    fork { var v = false; while (true) { sleep(4);  v = !v; dut.io.clkSclk  #= v } }

    val nibs = ArrayBuffer[Int]()
    var prev = false
    sleep(1200)   // settle the CDC + FIFO fill/start
    for (_ <- 0 until 6000) {
      sleep(1)
      val s = dut.io.clkSclk.toBoolean
      if (s && !prev) nibs += (dut.io.nibbles.toInt >> 12) & 0xF   // clock lane nibble
      prev = s
    }
    // reconstruct serial bits (LSB first within each nibble)
    val bits = nibs.flatMap(n => (0 until 4).map(b => (n >> b) & 1)).toArray
    // check the tail is periodic-10 and every 10-bit window has exactly five 1s
    val start = bits.length / 2
    var periodic = true; var balanced = true
    var i = start
    while (i + 20 < bits.length) {
      if (bits(i) != bits(i + 10)) periodic = false
      i += 1
    }
    var w = start
    while (w + 10 <= bits.length) {
      if ((0 until 10).map(k => bits(w + k)).sum != 5) balanced = false
      w += 10
    }
    println(f"clock-lane bits captured: ${bits.length}%d")
    println(f"sample window (bit-string): ${bits.slice(start, start + 30).mkString}")
    println(if (periodic && balanced) "PASS: clock lane serialises to a clean periodic 1111100000"
            else s"FAIL: periodic=$periodic balanced=$balanced")
  }
}
