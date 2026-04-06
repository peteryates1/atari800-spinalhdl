package atari800

import spinal.core._
import spinal.core.sim._

/**
 * Standalone SpinalSim test for SioBridge.
 *
 * Tests S2P deserializer, P2S serializer, COMMAND detection, and interrupt
 * signal using Verilator.
 *
 * Run: sbt "atari/runMain atari800.SioBridgeSim"
 */
object SioBridgeSim extends App {

  val compiled = SimConfig
    .workspacePath("simWorkspace")
    .compile(new SioBridge)

  // ── Bus helpers (matching SdSpiTest pattern) ──

  def ioWrite(dut: SioBridge, addr: Int, data: Long)(implicit cd: ClockDomain): Unit = {
    dut.bus.addr #= addr
    dut.bus.wrData #= data
    dut.bus.wr #= true
    dut.bus.rd #= false
    cd.waitSampling()
    dut.bus.wr #= false
  }

  def ioRead(dut: SioBridge, addr: Int)(implicit cd: ClockDomain): Long = {
    dut.bus.addr #= addr
    dut.bus.rd #= true
    dut.bus.wr #= false
    cd.waitSampling()
    val result = dut.bus.rdData.toLong
    dut.bus.rd #= false
    result
  }

  def initIo(dut: SioBridge): Unit = {
    dut.bus.addr #= 0
    dut.bus.rd #= false
    dut.bus.wr #= false
    dut.bus.wrData #= 0
    dut.io.sioCommand #= true     // idle: command not asserted (active low)
    dut.io.sioTxd #= true         // idle: line high
    dut.io.sioClockout #= false   // idle
  }

  /**
   * Send one SIO byte via clockout + txd.
   * SIO format: start bit (0), 8 data bits LSB-first, stop bit (1).
   * Each bit sampled on clockout falling edge.
   */
  def sendSioByte(dut: SioBridge, byte: Int, bitClocks: Int = 10)(implicit cd: ClockDomain): Unit = {
    // start bit
    dut.io.sioTxd #= false
    toggleClockout(dut, bitClocks)

    // 8 data bits, LSB first
    for (i <- 0 until 8) {
      dut.io.sioTxd #= ((byte >> i) & 1) != 0
      toggleClockout(dut, bitClocks)
    }

    // stop bit
    dut.io.sioTxd #= true
    toggleClockout(dut, bitClocks)
  }

  /** Toggle SIO_CLOCKOUT high then low (falling edge triggers sample). */
  def toggleClockout(dut: SioBridge, halfPeriodClocks: Int)(implicit cd: ClockDomain): Unit = {
    dut.io.sioClockout #= true
    cd.waitSampling(halfPeriodClocks)
    dut.io.sioClockout #= false
    cd.waitSampling(halfPeriodClocks)
  }

  /** Collect serial output from sioRxd, returns received byte or -1 on timeout. */
  def receiveSioByte(dut: SioBridge, baudDiv: Int = 2951)(implicit cd: ClockDomain): Int = {
    // Actual bit period = baudDiv + 1 (counter: baudDiv, baudDiv-1, ..., 1, 0, reload)
    val bitPeriod = baudDiv + 1

    // Wait for start bit (sioRxd goes low)
    var timeout = bitPeriod * 12
    while (dut.io.sioRxd.toBoolean && timeout > 0) {
      cd.waitSampling()
      timeout -= 1
    }
    if (timeout <= 0) return -1

    // Skip to middle of bit 0: 1.5 bit periods from start of start bit
    cd.waitSampling(bitPeriod + bitPeriod / 2)

    var byte = 0
    for (i <- 0 until 8) {
      if (dut.io.sioRxd.toBoolean) byte |= (1 << i)
      if (i < 7) cd.waitSampling(bitPeriod)
    }

    // Wait for stop bit
    cd.waitSampling(bitPeriod)
    byte
  }

  // ── Tests ──

  var passed = 0
  var failed = 0

  def check(name: String, condition: Boolean, msg: String = ""): Unit = {
    if (condition) {
      println(s"  PASS: $name")
      passed += 1
    } else {
      println(s"  FAIL: $name ${if (msg.nonEmpty) s"($msg)" else ""}")
      failed += 1
    }
  }

  // Test 1: S2P — receive a single byte
  println("=== Test 1: S2P receive single byte ===")
  compiled.doSim(seed = 42) { dut =>
    implicit val cd: ClockDomain = dut.clockDomain
    cd.forkStimulus(10)
    SimTimeout(100000)

    initIo(dut)
    cd.waitSampling(10)

    // Assert COMMAND (active low = command frame)
    dut.io.sioCommand #= false
    cd.waitSampling(5)

    // Send 0x31 (D1: device ID)
    sendSioByte(dut, 0x31)
    cd.waitSampling(5)

    // Check RX FIFO non-empty
    val rxStatus = ioRead(dut, 5)  // RX_STATUS
    check("RX FIFO not empty", (rxStatus & 0x01) == 0, s"rxStatus=0x${rxStatus.toHexString}")

    // Read RX_DATA: [7:0]=data, [15:8]=cmdByteIndex
    val rxData = ioRead(dut, 1)
    val dataByte = (rxData & 0xFF).toInt
    val byteIndex = ((rxData >> 8) & 0xFF).toInt
    check("Data byte = 0x31", dataByte == 0x31, f"got 0x$dataByte%02x")
    check("Byte index = 0", byteIndex == 0, s"got $byteIndex")

    // FIFO should be empty now
    val rxStatus2 = ioRead(dut, 5)
    check("RX FIFO empty after read", (rxStatus2 & 0x01) != 0)
  }

  // Test 2: S2P — receive 5-byte command frame + interrupt
  println("\n=== Test 2: 5-byte command frame + interrupt ===")
  compiled.doSim(seed = 42) { dut =>
    implicit val cd: ClockDomain = dut.clockDomain
    cd.forkStimulus(10)
    SimTimeout(200000)

    initIo(dut)
    cd.waitSampling(10)

    // Start command frame
    dut.io.sioCommand #= false
    cd.waitSampling(5)

    // Send command: D1: Read Sector 1
    // deviceId=0x31, cmd=0x52, aux1=0x01, aux2=0x00, checksum
    val frame = Seq(0x31, 0x52, 0x01, 0x00)
    val checksum = {
      var sum = 0
      frame.foreach { b => sum += b; if (sum > 0xFF) sum = (sum & 0xFF) + 1 }
      sum & 0xFF
    }
    val fullFrame = frame :+ checksum

    for (b <- fullFrame) {
      sendSioByte(dut, b)
      cd.waitSampling(5)
    }

    // Deassert COMMAND (rising edge = frame complete)
    // Check for interrupt pulse
    var interruptSeen = false
    for (_ <- 0 until 5) {
      dut.io.sioCommand #= true
      cd.waitSampling()
      if (dut.bus.cmdInterrupt.toBoolean) interruptSeen = true
    }
    check("Interrupt fired on COMMAND rising edge", interruptSeen)

    // Check STATUS: CMD_EDGE (bit 4) should be set
    val status = ioRead(dut, 0)
    check("CMD_EDGE set in STATUS", (status & 0x10) != 0, s"status=0x${status.toHexString}")

    // Drain FIFO — verify all 5 bytes
    for (i <- 0 until 5) {
      val rxData = ioRead(dut, 1)
      val dataByte = (rxData & 0xFF).toInt
      val byteIdx = ((rxData >> 8) & 0xFF).toInt
      check(f"Frame byte $i: data=0x${fullFrame(i)}%02x", dataByte == fullFrame(i),
        f"got 0x$dataByte%02x")
      check(s"Frame byte $i: index=$i", byteIdx == i, s"got $byteIdx")
    }

    // FIFO should be empty
    val rxStatus = ioRead(dut, 5)
    check("RX FIFO empty after drain", (rxStatus & 0x01) != 0)
  }

  // Test 3: P2S — transmit a byte
  println("\n=== Test 3: P2S transmit byte ===")
  compiled.doSim(seed = 42) { dut =>
    implicit val cd: ClockDomain = dut.clockDomain
    cd.forkStimulus(10)
    val testBaudDiv = 10
    // baudCtr inits at 2951, need ~3000 cycles to wrap + 110 for byte = ~40000 ns
    SimTimeout(100000)

    initIo(dut)
    cd.waitSampling(10)

    // sioRxd should be high (idle) when TX disabled
    check("sioRxd idle high (TX disabled)", dut.io.sioRxd.toBoolean)

    // Set baud divider first, then wait for initial baudCtr to expire
    ioWrite(dut, 4, testBaudDiv)
    cd.waitSampling(3000)  // wait for default baudCtr (2951) to reach 0 and reload

    // Enable TX
    ioWrite(dut, 0, 1)  // CTRL: txEnableReg = true
    cd.waitSampling(2)

    // sioRxd should still be high (idle, nothing in TX FIFO)
    check("sioRxd idle high (TX enabled, empty FIFO)", dut.io.sioRxd.toBoolean)

    // Write ACK byte (0x41) to TX_DATA
    ioWrite(dut, 2, 0x41)

    // Receive the byte from sioRxd
    val received = receiveSioByte(dut, testBaudDiv)
    check("P2S transmitted 0x41", received == 0x41, f"got 0x$received%02x")
  }

  // Test 4: Interrupt does NOT fire with empty RX FIFO
  println("\n=== Test 4: No interrupt when RX FIFO empty ===")
  compiled.doSim(seed = 42) { dut =>
    implicit val cd: ClockDomain = dut.clockDomain
    cd.forkStimulus(10)
    SimTimeout(50000)

    initIo(dut)
    cd.waitSampling(10)

    // Assert then deassert COMMAND with no data sent
    dut.io.sioCommand #= false
    cd.waitSampling(10)

    var interruptSeen = false
    for (_ <- 0 until 5) {
      dut.io.sioCommand #= true
      cd.waitSampling()
      if (dut.bus.cmdInterrupt.toBoolean) interruptSeen = true
    }
    check("No interrupt when FIFO empty", !interruptSeen)
  }

  // ── Summary ──
  println(s"\n=== Results: $passed passed, $failed failed ===")
  if (failed > 0) {
    println("SOME TESTS FAILED")
    System.exit(1)
  } else {
    println("ALL TESTS PASSED")
  }
}
