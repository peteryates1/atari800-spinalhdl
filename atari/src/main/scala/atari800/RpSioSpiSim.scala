package atari800

import spinal.core._
import spinal.core.sim._
import scala.collection.mutable.ArrayBuffer

// Verifies the SIO-over-SPI path the RP2040 firmware relies on: RpAtariKeyboard
// (SPI slave) driving the SioBridge register bus via the 'Q' (write) and 'S'
// (read) commands, wired exactly as Atari800Rp2040HdmiLgTop wires them.
//
//   1. Inject an SIO command frame (D1: read-sector) on the SIO serial pins.
//   2. Over SPI: read RX_STATUS ('S' 5) -> expect count 5.
//   3. Read RX_DATA ('S' 1) x5 -> expect the frame bytes with cmd-byte indices.
//   4. Push an ACK ('Q' 2) + enable TX ('Q' 0) -> expect 0x41 back on sioRxd.
//
// Run: sbt "atari/runMain atari800.RpSioSpiSim"
class SioSpiTestDut extends Component {
  val io = new Bundle {
    val spiSck  = in  Bool()
    val spiMosi = in  Bool()
    val spiCsN  = in  Bool()
    val spiMiso = out Bool()
    val sioCommand  = in  Bool()
    val sioTxd      = in  Bool()
    val sioClockout = in  Bool()
    val sioRxd      = out Bool()
  }
  val kbd       = new RpAtariKeyboard
  val sioBridge = new SioBridge

  kbd.io.spiSck  := io.spiSck
  kbd.io.spiMosi := io.spiMosi
  kbd.io.spiCsN  := io.spiCsN
  io.spiMiso     := kbd.io.spiMiso

  sioBridge.io.sioCommand  := io.sioCommand
  sioBridge.io.sioTxd      := io.sioTxd
  sioBridge.io.sioClockout := io.sioClockout
  io.sioRxd                := sioBridge.io.sioRxd

  sioBridge.bus.addr   := kbd.io.sioAddr
  sioBridge.bus.rd     := kbd.io.sioRd
  sioBridge.bus.wr     := kbd.io.sioWr
  sioBridge.bus.wrData := kbd.io.sioWrData.resize(32)
  kbd.io.sioRdData     := sioBridge.bus.rdData

  // Tie off the keyboard's unrelated inputs.
  kbd.io.keyboardScan := 0
  kbd.io.ldRdData     := 0
  kbd.io.ldComplete   := False
  kbd.io.meterLate    := 0
  kbd.io.meterDrop    := 0
  kbd.io.bbMinX := 0; kbd.io.bbMaxX := 0; kbd.io.bbMinY := 0; kbd.io.bbMaxY := 0
  kbd.io.aMaxWait := 0
}

object RpSioSpiSim extends App {
  val compiled = SimConfig.workspacePath("simWorkspace").compile(new SioSpiTestDut)

  var passed = 0; var failed = 0
  def check(name: String, cond: Boolean, msg: String = ""): Unit = {
    if (cond) { println(s"  PASS: $name"); passed += 1 }
    else { println(s"  FAIL: $name ${if (msg.nonEmpty) s"($msg)" else ""}"); failed += 1 }
  }

  val H = 8   // SPI half-bit period, in sys cycles (SPI << sys)

  compiled.doSim("test") { dut =>
    implicit val cd = dut.clockDomain
    cd.forkStimulus(10)

    // idle levels
    dut.io.spiSck  #= false
    dut.io.spiMosi #= false
    dut.io.spiCsN  #= true
    dut.io.sioCommand  #= true
    dut.io.sioTxd      #= true
    dut.io.sioClockout #= false
    cd.waitSampling(20)

    // ---- SPI master (mode 0, MSB first) ----
    def spiFrame(tx: Seq[Int]): Seq[Int] = {
      dut.io.spiCsN #= false
      cd.waitSampling(H)
      val rx = ArrayBuffer[Int]()
      for (b <- tx) {
        var rb = 0
        for (bit <- 7 to 0 by -1) {
          dut.io.spiMosi #= ((b >> bit) & 1) != 0
          cd.waitSampling(H)
          dut.io.spiSck #= true          // rising: slave samples MOSI
          cd.waitSampling(H)
          if (dut.io.spiMiso.toBoolean) rb |= (1 << bit)   // mode 0: sample on rising
          dut.io.spiSck #= false         // falling: slave shifts next MISO bit
        }
        rx += rb
      }
      cd.waitSampling(H)
      dut.io.spiCsN #= true
      cd.waitSampling(H * 2)
      rx.toSeq
    }

    def sioWrite(addr: Int, data: Int): Unit =
      spiFrame(Seq(0x51, addr & 0x0F, data & 0xFF, (data >> 8) & 0xFF))
    def sioRead(addr: Int): Int = {
      spiFrame(Seq(0x53, addr & 0x0F))
      val rx = spiFrame(Seq.fill(27)(0))
      check(s"status frame sync byte (addr $addr)", rx(0) == 0xA5, f"rx0=0x${rx(0)}%02x")
      (rx(25) & 0xFF) | ((rx(26) & 0xFF) << 8)
    }

    // ---- SIO serial injection (from SioBridgeSim) ----
    def toggleClockout(hp: Int): Unit = {
      dut.io.sioClockout #= true;  cd.waitSampling(hp)
      dut.io.sioClockout #= false; cd.waitSampling(hp)
    }
    def sendSioByte(byte: Int, bc: Int = 10): Unit = {
      dut.io.sioTxd #= false; toggleClockout(bc)                 // start
      for (i <- 0 until 8) { dut.io.sioTxd #= ((byte >> i) & 1) != 0; toggleClockout(bc) }
      dut.io.sioTxd #= true; toggleClockout(bc)                  // stop
    }
    def receiveSioByte(baudDiv: Int = 2951): Int = {
      val bit = baudDiv + 1
      var to = bit * 12
      while (dut.io.sioRxd.toBoolean && to > 0) { cd.waitSampling(); to -= 1 }
      if (to <= 0) return -1
      cd.waitSampling(bit + bit / 2)
      var v = 0
      for (i <- 0 until 8) { if (dut.io.sioRxd.toBoolean) v |= (1 << i); if (i < 7) cd.waitSampling(bit) }
      cd.waitSampling(bit)
      v
    }

    // === 1. inject a D1: read-sector command frame ===
    val frame = Seq(0x31, 0x52, 0x01, 0x00, 0x84)   // dev, cmd, aux1, aux2, cksum
    dut.io.sioCommand #= false                       // COMMAND asserted (active low)
    cd.waitSampling(20)
    for (b <- frame) sendSioByte(b)
    cd.waitSampling(20)
    dut.io.sioCommand #= true                         // COMMAND released
    cd.waitSampling(50)

    // === 2. RX_STATUS count == 5 ===
    val rxStat = sioRead(5)
    val count = (rxStat >> 2) & 0x1F
    check("RX_STATUS count == 5", count == 5, s"count=$count (rxStat=0x${rxStat.toHexString})")

    // === 3. pop 5 RX_DATA bytes, verify data + cmd-byte index ===
    for (i <- 0 until 5) {
      val v = sioRead(1)
      val data = v & 0xFF
      val idx  = (v >> 8) & 0xFF
      check(s"RX byte $i data", data == frame(i), f"got 0x$data%02x want 0x${frame(i)}%02x")
      check(s"RX byte $i index", idx == i, s"got $idx")
    }

    // === 4. push ACK and enable TX, expect 0x41 on sioRxd ===
    sioWrite(2, 0x41)     // TX_DATA <- ACK
    sioWrite(0, 0x01)     // CTRL: TX_ENABLE
    val got = receiveSioByte()
    check("sioRxd serialized ACK 0x41", got == 0x41, f"got 0x$got%02x")
    sioWrite(0, 0x00)     // TX_ENABLE off

    println(s"=== Results: $passed passed, $failed failed ===")
    if (failed == 0) println("ALL TESTS PASSED") else { println("TESTS FAILED"); simFailure("failed") }
  }
}
