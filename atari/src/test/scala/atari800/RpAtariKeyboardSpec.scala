package atari800

import org.scalatest.funsuite.AnyFunSuite
import spinal.core._
import spinal.core.sim._

// Unit tests for the RP2040 -> Atari SPI keyboard bridge: drives real mode-0
// SPI frames (as the supervisor firmware will) and checks the Atari matrix
// response, console keys, control register, and atomic commit semantics.
class RpAtariKeyboardSpec extends AnyFunSuite {

  private val compiled = SimConfig.withConfig(SpinalConfig()).compile(new RpAtariKeyboard)

  // SPI mode 0 master, ~ sysclk/16 bit rate
  private def spiFrame(dut: RpAtariKeyboard, bytes: Seq[Int]): Unit = {
    val cd = dut.clockDomain
    dut.io.spiCsN #= false; cd.waitSampling(8)
    for (b <- bytes) {
      for (bit <- 7 to 0 by -1) {
        dut.io.spiMosi #= ((b >> bit) & 1) == 1
        cd.waitSampling(8)
        dut.io.spiSck #= true
        cd.waitSampling(8)
        dut.io.spiSck #= false
      }
    }
    cd.waitSampling(8)
    dut.io.spiCsN #= true; cd.waitSampling(8)
  }

  private def hidReport(mods: Int, keys: Int*): Seq[Int] =
    Seq(0x4B, mods, 0x00) ++ keys.padTo(6, 0)

  private def scanResponse(dut: RpAtariKeyboard, scan: Int): Int = {
    dut.io.keyboardScan #= scan
    dut.clockDomain.waitSampling(2)
    dut.io.keyboardResponse.toInt
  }

  private def withDut(body: RpAtariKeyboard => Unit): Unit =
    compiled.doSim(seed = 1) { dut =>
      dut.clockDomain.forkStimulus(10)
      dut.io.spiSck #= false; dut.io.spiMosi #= false; dut.io.spiCsN #= true
      dut.io.keyboardScan #= 0
      dut.clockDomain.waitSampling(10)
      body(dut)
    }

  test("HID 'a' maps to matrix 63 and answers the right scan") {
    withDut { dut =>
      spiFrame(dut, hidReport(0x00, 0x04))       // 'a', no modifiers
      // matrix position 63 is addressed when POKEY scans ~63 = 0
      assert((scanResponse(dut, 0) & 1) == 0, "response(0) low at scan of 'a'")
      assert((scanResponse(dut, 5) & 1) == 1, "other scan codes stay idle")
      assert(dut.io.frameCount.toInt == 1)
    }
  }

  test("shift and ctrl report on response(1) by scan region") {
    withDut { dut =>
      spiFrame(dut, hidReport(0x02, 0x04))       // LShift + 'a'
      assert((scanResponse(dut, 0x20) & 2) == 0, "shift on scan 10xxxx")
      assert((scanResponse(dut, 0x30) & 2) == 2, "ctrl region idle")
      spiFrame(dut, hidReport(0x01))             // LCtrl only
      assert((scanResponse(dut, 0x30) & 2) == 0, "ctrl on scan 11xxxx")
      assert((scanResponse(dut, 0x20) & 2) == 2, "shift region idle")
    }
  }

  test("key release clears the matrix") {
    withDut { dut =>
      spiFrame(dut, hidReport(0x00, 0x04))
      assert((scanResponse(dut, 0) & 1) == 0)
      spiFrame(dut, hidReport(0x00))             // empty report = all released
      assert((scanResponse(dut, 0) & 1) == 1)
    }
  }

  test("F5/F6/F7 drive console keys; control frame drives overrides") {
    withDut { dut =>
      spiFrame(dut, hidReport(0x00, 0x3E, 0x40)) // F5 + F7
      dut.clockDomain.waitSampling(4)
      assert(dut.io.consolStart.toBoolean && !dut.io.consolSelect.toBoolean && dut.io.consolOption.toBoolean)
      spiFrame(dut, Seq(0x43, 0x05))             // control: reset + select
      dut.clockDomain.waitSampling(4)
      assert(dut.io.ctrlReset.toBoolean && !dut.io.ctrlStart.toBoolean && dut.io.ctrlSelect.toBoolean)
      spiFrame(dut, Seq(0x43, 0x00))
      dut.clockDomain.waitSampling(4)
      assert(!dut.io.ctrlReset.toBoolean)
    }
  }

  test("partial frame is not committed") {
    withDut { dut =>
      spiFrame(dut, Seq(0x4B, 0x00, 0x00, 0x04)) // truncated report ('a')
      assert((scanResponse(dut, 0) & 1) == 1, "truncated frame must not commit")
      assert(dut.io.frameCount.toInt == 0)
    }
  }
}
