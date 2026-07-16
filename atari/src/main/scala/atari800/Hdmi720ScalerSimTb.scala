package atari800

import spinal.core._
import spinal.core.sim._
import scala.collection.mutable.ArrayBuffer

// Dual-clock SpinalSim for Hdmi720Scaler. Tiny raster params so a frame is ~544 pixel
// clocks. Drives a synthetic Atari-like input (strobed pixels, hsync/line, vsync/frame)
// on the sys clock with a CONSTANT colour per input line (line L -> colour L+1); checks
// the 720p-domain output reproduces the 3x-vertical line sequence 1,1,1,2,2,2,3,3,3,4,4,4.
// That one check exercises write addressing, read addressing (vc/3, hx/3), the dual-clock
// Mem round-trip, genlock (vsync reset) and the horizontal active width together.
//
// The clocks are plain Bool inputs, so we drive them by hand: one fork OWNS clkSys and
// advances the input stimulus on its rising edges; another OWNS clkPixel and samples the
// outputs on its rising edges. Rate: sys 14 ns, pixel 10 ns; input frame padded to ~= one
// output frame so genlock lands near the top each frame.
object Hdmi720ScalerSimTb {
  // tiny raster: hTotal=32, vTotal=17 -> 544 px/frame
  val P = Map(
    "hA" -> 24, "hF" -> 2, "hS" -> 3, "hB" -> 3,   // hTotal 32
    "vA" -> 12, "vF" -> 1, "vS" -> 2, "vB" -> 2,   // vTotal 17
    "inA" -> 6, "hSc" -> 3, "vSc" -> 3)            // outW=18, hBorder=3, 4 input lines
  val nInLines = P("vA") / P("vSc")                // 4
  val expected = (0 until P("vA")).map(i => i / P("vSc") + 1).toVector  // 1,1,1,2,2,2,3,3,3,4,4,4

  def main(args: Array[String]): Unit = {
    SimConfig.compile(new Hdmi720Scaler(
      nLines = 8, lineMax = 8, inActive = P("inA"), hScale = P("hSc"), vScale = P("vSc"),
      hActive = P("hA"), hFront = P("hF"), hSync = P("hS"), hBack = P("hB"),
      vActive = P("vA"), vFront = P("vF"), vSync = P("vS"), vBack = P("vB")
    )).doSim("scaler") { dut =>
      dut.io.clkSys #= false; dut.io.clkPixel #= false
      dut.io.inStrobe #= false; dut.io.inHsync #= false; dut.io.inVsync #= false
      dut.io.inR #= 0; dut.io.inG #= 0; dut.io.inB #= 0
      sleep(1)

      // ---- fork: clkPixel generator + output monitor (collect per-frame line colours) ----
      val frames = ArrayBuffer[Vector[Int]]()
      fork {
        var prevDe = false; var prevVs = false
        var curColor = 0; var mism = false
        val lines = ArrayBuffer[Int]()
        while (true) {
          dut.io.clkPixel #= false; sleep(5)
          dut.io.clkPixel #= true;  sleep(5)
          val de = dut.io.outDe.toBoolean
          val vs = dut.io.outVsync.toBoolean
          val col = (dut.io.outR.toInt >> 4) & 0xf
          if (vs && !prevVs) {                 // frame boundary
            if (prevDe) { lines += (if (mism) -1 else curColor) }
            frames += lines.toVector
            lines.clear()
          }
          if (de && !prevDe) { curColor = 0; mism = false }   // line start
          if (de) { if (col != 0) { if (curColor != 0 && curColor != col) mism = true; curColor = col } }
          if (!de && prevDe) { lines += (if (mism) -1 else curColor) }  // line end
          prevDe = de; prevVs = vs
        }
      }

      // ---- fork: clkSys generator + input stimulus ----
      def sysCycle(setup: => Unit): Unit = {
        dut.io.clkSys #= false; setup; sleep(7)
        dut.io.clkSys #= true;  sleep(7)
      }
      val padCycles = 330   // pad vblank so an input frame ~= one output frame (~388 sys cyc)
      fork {
        while (true) {
          sysCycle { dut.io.inVsync #= true;  dut.io.inStrobe #= false; dut.io.inHsync #= false }
          sysCycle { dut.io.inVsync #= false }
          for (l <- 0 until nInLines) {
            val c = l + 1
            for (_ <- 0 until P("inA")) {
              sysCycle { dut.io.inR #= c << 4; dut.io.inG #= c << 4; dut.io.inB #= c << 4; dut.io.inStrobe #= false }
              sysCycle { dut.io.inStrobe #= true }        // rising edge = one pixel (data held)
            }
            sysCycle { dut.io.inHsync #= true;  dut.io.inStrobe #= false }
            sysCycle { dut.io.inHsync #= false }
          }
          for (_ <- 0 until padCycles) sysCycle { dut.io.inStrobe #= false; dut.io.inHsync #= false }
        }
      }

      sleep(60000)   // ~11 output frames

      // ---- evaluate: find a well-formed frame (>=9 active lines) and check the sequence ----
      val good = frames.drop(2).find(_.length >= 9)
      println(s"[TB] collected ${frames.length} frames, line-counts=${frames.map(_.length).mkString(",")}")
      good match {
        case None => throw new Exception(s"no frame with >=9 active lines: ${frames.map(_.length).mkString(",")}")
        case Some(seq) =>
          println(s"[TB] checking frame lines = ${seq.mkString(",")}")
          for (i <- seq.indices) {
            assert(seq(i) == expected(i), s"line $i: got ${seq(i)}, expected ${expected(i)} (full ${seq.mkString(",")})")
          }
          println(s"[TB] PASS: ${seq.length} active lines match 3x-vertical sequence ${expected.take(seq.length).mkString(",")}")
      }
    }
  }
}
