package atari800

import spinal.core._
import spinal.core.sim._
import java.io.{FileOutputStream, BufferedOutputStream, File, FileInputStream}
import scala.collection.mutable.ArrayBuffer

// ATR disk image loader — parses header and provides sector reads
class AtrFile(path: String) {
  val raw: Array[Byte] = {
    val f = new FileInputStream(path)
    try {
      val data = new Array[Byte](f.available())
      f.read(data)
      data
    } finally f.close()
  }

  // ATR header: 16 bytes
  // Bytes 0-1: magic $96 $02
  // Bytes 2-3: paragraphs (16-byte units, low word)
  // Bytes 4-5: sector size
  // Bytes 6-7: paragraphs (high byte at offset 6)
  require(raw.length >= 16, s"ATR file too small: ${raw.length}")
  require((raw(0) & 0xFF) == 0x96 && (raw(1) & 0xFF) == 0x02,
    f"Bad ATR magic: ${raw(0) & 0xFF}%02X ${raw(1) & 0xFF}%02X")

  val sectorSize: Int = (raw(4) & 0xFF) | ((raw(5) & 0xFF) << 8)
  val paragraphs: Int = (raw(2) & 0xFF) | ((raw(3) & 0xFF) << 8) | ((raw(6) & 0xFF) << 16)
  val imageSize: Int = paragraphs * 16
  val totalSectors: Int = if (sectorSize == 128) imageSize / 128
                          else 3 + (imageSize - 384) / sectorSize

  println(f"ATR: $path — ${raw.length} bytes, sector=$sectorSize, " +
    f"paragraphs=$paragraphs ($imageSize bytes), ~$totalSectors sectors")

  // Read sector data. Sector numbers are 1-based.
  // Sectors 1-3 are always 128 bytes. Sectors 4+ use sectorSize.
  def readSector(sector: Int): Array[Byte] = {
    if (sector < 1 || sector > totalSectors) return Array.fill(128)(0.toByte)
    val (offset, size) = if (sector <= 3) {
      (16 + (sector - 1) * 128, 128)
    } else {
      (16 + 3 * 128 + (sector - 4) * sectorSize, sectorSize)
    }
    val buf = new Array[Byte](size)
    val avail = math.min(size, raw.length - offset)
    if (avail > 0) System.arraycopy(raw, offset, buf, 0, avail)
    buf
  }
}

// Behavioral SIO disk drive for simulation.
// Monitors sio_txd (Atari→peripheral) sampled on sio_clockout falling edge.
// Drives sio_rxd (peripheral→Atari) at 19200 baud using internal counter.
class SimSioDiskResponder(atr: AtrFile, clockFreqHz: Double = 56.67e6) {
  val BAUD_RATE = 19200
  val CLOCKS_PER_BIT = (clockFreqHz / BAUD_RATE).toInt  // ~2951

  // Protocol timing (in clock cycles)
  val T2_CLOCKS = (100e-6 * clockFreqHz).toInt    // 100µs: COMMAND↑ → ACK
  val T5_CLOCKS = (300e-6 * clockFreqHz).toInt    // 300µs: ACK complete → COMPLETE
  val T3_CLOCKS = (150e-6 * clockFreqHz).toInt    // 150µs: COMPLETE complete → data

  // RX state (deserializer — samples on clockout falling edge)
  private var lastClockout = false
  private var rxState = 0  // 0=wait-start, 1-8=data bits, 9=stop
  private var rxShift = 0
  private var rxBitCount = 0

  // Command frame tracking
  private var lastCommand = true  // idle high
  private var commandActive = false
  private val cmdBytes = new ArrayBuffer[Int]()

  // TX state (serializer — internal baud generator)
  private val txQueue = new ArrayBuffer[Int]()
  private var txState = 0  // 0=idle, 1=start, 2-9=data bits, 10=stop
  private var txShift = 0
  private var txBitTimer = 0
  private var txOut = true  // idle high

  // Protocol state machine
  private object State extends Enumeration {
    val IDLE, WAIT_CMD_END, WAIT_T2, SEND_ACK, WAIT_T5,
        SEND_COMPLETE, WAIT_T3, SEND_DATA = Value
  }
  private var state = State.IDLE
  private var delayCounter = 0
  private var pendingSector = 0
  private var pendingCmd = 0
  private var dataToSend: Array[Byte] = null
  private var dataIdx = 0

  // Stats
  var sectorsServed = 0
  var commandsReceived = 0
  var nakCount = 0

  // SIO checksum: 8-bit sum with carry folded back
  private def sioChecksum(data: Array[Byte]): Int = {
    var sum = 0
    for (b <- data) {
      sum += (b & 0xFF)
      if (sum > 0xFF) sum = (sum & 0xFF) + 1
    }
    sum & 0xFF
  }

  private def sioChecksum4(b0: Int, b1: Int, b2: Int, b3: Int): Int = {
    var sum = (b0 & 0xFF) + (b1 & 0xFF) + (b2 & 0xFF) + (b3 & 0xFF)
    while (sum > 0xFF) sum = (sum & 0xFF) + 1
    sum & 0xFF
  }

  // Enqueue a byte for transmission
  private def enqueueTx(b: Int): Unit = txQueue += (b & 0xFF)

  // Called every clock cycle. Returns the sio_rxd value.
  def tick(txd: Boolean, command: Boolean, clockout: Boolean): Boolean = {
    // === RX: deserialize on clockout falling edge ===
    val clockFalling = lastClockout && !clockout
    lastClockout = clockout

    if (clockFalling) {
      val bit = if (txd) 1 else 0
      rxState match {
        case 0 => // waiting for start bit (low)
          if (!txd) rxState = 1
        case s if s >= 1 && s <= 8 => // data bits (LSB first)
          rxShift = (rxShift >> 1) | (bit << 7)
          rxState = s + 1
        case 9 => // stop bit
          if (txd) {
            onRxByte(rxShift & 0xFF)
          }
          rxShift = 0
          rxState = 0
        case _ => rxState = 0
      }
    }

    // === COMMAND edge detection ===
    if (!command && lastCommand) {
      // COMMAND falling: start of command frame
      commandActive = true
      cmdBytes.clear()
    }
    if (command && !lastCommand) {
      // COMMAND rising: end of command frame
      commandActive = false
      onCommandFrameEnd()
    }
    lastCommand = command

    // === Protocol state machine ===
    state match {
      case State.WAIT_T2 | State.WAIT_T5 | State.WAIT_T3 =>
        delayCounter -= 1
        if (delayCounter <= 0) {
          state match {
            case State.WAIT_T2 =>
              enqueueTx(0x41) // ACK
              state = State.SEND_ACK
            case State.WAIT_T5 =>
              enqueueTx(0x43) // COMPLETE
              state = State.SEND_COMPLETE
            case State.WAIT_T3 =>
              // Queue all data bytes + checksum
              for (b <- dataToSend) enqueueTx(b & 0xFF)
              enqueueTx(sioChecksum(dataToSend))
              state = State.SEND_DATA
            case _ =>
          }
        }
      case State.SEND_ACK =>
        if (txQueue.isEmpty && txState == 0) {
          delayCounter = T5_CLOCKS
          state = State.WAIT_T5
        }
      case State.SEND_COMPLETE =>
        if (txQueue.isEmpty && txState == 0) {
          delayCounter = T3_CLOCKS
          state = State.WAIT_T3
        }
      case State.SEND_DATA =>
        if (txQueue.isEmpty && txState == 0) {
          state = State.IDLE
        }
      case _ => // IDLE
    }

    // === TX: baud-rate generator and serializer ===
    if (txState == 0) {
      // Idle — check for data to send
      if (txQueue.nonEmpty) {
        txShift = txQueue.remove(0)
        txState = 1  // start bit next tick
        txBitTimer = CLOCKS_PER_BIT
        txOut = false  // start bit
      } else {
        txOut = true  // idle
      }
    } else {
      txBitTimer -= 1
      if (txBitTimer <= 0) {
        txBitTimer = CLOCKS_PER_BIT
        txState match {
          case 1 => // start bit was being sent, now send bit 0
            txOut = (txShift & 1) != 0
            txShift >>= 1
            txState = 2
          case s if s >= 2 && s <= 8 => // data bits 1-7
            txOut = (txShift & 1) != 0
            txShift >>= 1
            txState = s + 1
          case 9 => // last data bit was sent, now stop bit
            txOut = true
            txState = 10
          case 10 => // stop bit done
            txState = 0
            txOut = true
          case _ =>
            txState = 0
            txOut = true
        }
      }
    }

    txOut
  }

  private def onRxByte(b: Int): Unit = {
    if (commandActive) {
      cmdBytes += b
    }
  }

  private def onCommandFrameEnd(): Unit = {
    if (cmdBytes.length < 5) {
      println(f"  SIO: short command frame (${cmdBytes.length} bytes)")
      return
    }
    commandsReceived += 1
    val devId = cmdBytes(0)
    val cmd   = cmdBytes(1)
    val aux1  = cmdBytes(2)
    val aux2  = cmdBytes(3)
    val cksum = cmdBytes(4)
    val expected = sioChecksum4(devId, cmd, aux1, aux2)

    if (commandsReceived <= 20 || commandsReceived % 50 == 0) {
      println(f"  SIO CMD#$commandsReceived: dev=$$${devId}%02X cmd=$$${cmd}%02X " +
        f"aux=$$${aux1}%02X $$${aux2}%02X cksum=$$${cksum}%02X (exp=$$${expected}%02X)")
    }

    if (cksum != expected) {
      println(f"  SIO: BAD CHECKSUM $$${cksum}%02X != $$${expected}%02X — NAK")
      nakCount += 1
      delayCounter = T2_CLOCKS
      state = State.WAIT_T2
      // Actually send NAK instead of ACK
      return
    }

    if (devId != 0x31) {
      // Not D1: — ignore
      return
    }

    cmd match {
      case 0x52 => // Read Sector
        val sector = aux1 | (aux2 << 8)
        pendingSector = sector
        pendingCmd = cmd
        dataToSend = atr.readSector(sector)
        sectorsServed += 1
        if (sectorsServed <= 20 || sectorsServed % 50 == 0) {
          println(f"  SIO: READ sector $sector (${dataToSend.length} bytes)")
        }
        delayCounter = T2_CLOCKS
        state = State.WAIT_T2

      case 0x53 => // Get Status
        // Status bytes: drive status, FDC status, format timeout, unused
        dataToSend = Array[Byte](0x10.toByte, 0xFF.toByte, 0xE0.toByte, 0x00.toByte)
        delayCounter = T2_CLOCKS
        state = State.WAIT_T2

      case 0x3F => // Get Speed Index
        dataToSend = Array[Byte](0x28.toByte) // standard speed ($28 = 40)
        delayCounter = T2_CLOCKS
        state = State.WAIT_T2

      case _ =>
        println(f"  SIO: unknown command $$${cmd}%02X — NAK")
        nakCount += 1
        // Send NAK
        enqueueTx(0x4E)
        state = State.IDLE
    }
  }
}

object Atari800DiskSimTb extends App {
  // ATR file path from command line or default
  val atrPath = if (args.length > 0) args(0) else "roms/disk.atr"
  val atrFile = new AtrFile(atrPath)

  // Derive name for output files
  val diskName = new File(atrPath).getName.replaceAll("\\.[^.]+$", "")
    .replaceAll("[^a-zA-Z0-9]", "_")

  val compiled = SimConfig
    .withConfig(SpinalConfig(
      defaultClockDomainFrequency = FixedFrequency(56.67 MHz),
      defaultConfigForClockDomains = ClockDomainConfig(
        resetKind = ASYNC,
        resetActiveLevel = LOW
      )
    ))
    .workspacePath("sim_workspace")
    .addSimulatorFlag("-Wno-WIDTHEXPAND")
    .addSimulatorFlag("-Wno-WIDTHTRUNC")
    .addSimulatorFlag("--x-initial-edge")
    .addSimulatorFlag("--x-assign 0")
    .compile(new Atari800CoreSim(cartridge_rom = "", internal_ram = 49152))  // 48K BRAM, no SDRAM

  compiled.doSim(s"Atari800_disk_${diskName}", seed = 42) { dut =>
    val sdram = new SdramBehavioral()
    val sio = new SimSioDiskResponder(atrFile)

    val clockPeriod = 17640  // ps (~56.67 MHz)
    dut.clockDomain.forkStimulus(period = clockPeriod)

    // Initialize inputs
    dut.io.reset_btn  #= false
    dut.io.option_btn #= false
    dut.io.select_btn #= false
    dut.io.start_btn  #= false
    dut.io.joy1       #= 0x1F
    dut.io.sdramRequestComplete #= false
    dut.io.sdramDo   #= 0
    dut.io.sio_rxd   #= true  // idle high

    // Reset
    dut.clockDomain.waitRisingEdge(10)
    dut.clockDomain.assertReset()
    dut.clockDomain.waitRisingEdge(100)
    dut.clockDomain.deassertReset()

    // Tracking
    var cycleCount   = 0
    var hsyncCount   = 0
    var vsyncCount   = 0
    var lastHsync    = false
    var lastVsync    = false
    var sdramReads   = 0
    var sdramWrites  = 0
    var cpuEnCount   = 0
    var lastSerinReady = false
    var serinReadyCount = 0
    var lastVsyncForCapture = 0

    // Frame capture (same as Atari800CoreSimTb)
    val maxFbWidth  = 256
    val maxFbHeight = 320
    val frameR = Array.ofDim[Int](maxFbHeight, maxFbWidth)
    val frameG = Array.ofDim[Int](maxFbHeight, maxFbWidth)
    val frameB = Array.ofDim[Int](maxFbHeight, maxFbWidth)
    var fbPixelX       = 0
    var fbPixelY       = 0
    var fbMaxPixelX    = 0
    var fbMaxPixelY    = 0
    var fbFramesCaptured = 0
    var fbCaptureFrame   = false
    var fbLastRawHsync = false
    var fbLastRawVsync = false
    var fbLastColClk   = false
    val maxCaptures = 30  // capture frames throughout boot + run

    // PAL palette (from GtiaPalette)
    val palR = Array(
      0x00, 0x11, 0x22, 0x33, 0x44, 0x55, 0x66, 0x77, 0x88, 0x99, 0xAA, 0xBB, 0xCC, 0xDD, 0xEE, 0xFF,
      0x3F, 0x50, 0x61, 0x72, 0x83, 0x94, 0xA5, 0xB6, 0xC7, 0xD8, 0xE9, 0xFA, 0xFF, 0xFF, 0xFF, 0xFF,
      0x50, 0x61, 0x72, 0x83, 0x94, 0xA5, 0xB6, 0xC7, 0xD8, 0xE9, 0xFA, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF,
      0x54, 0x65, 0x76, 0x87, 0x98, 0xA9, 0xBA, 0xCB, 0xDC, 0xED, 0xFE, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF,
      0x4F, 0x60, 0x71, 0x82, 0x93, 0xA4, 0xB5, 0xC6, 0xD7, 0xE8, 0xF9, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF,
      0x3D, 0x4E, 0x5F, 0x70, 0x81, 0x92, 0xA3, 0xB4, 0xC5, 0xD6, 0xE7, 0xF8, 0xFF, 0xFF, 0xFF, 0xFF,
      0x20, 0x31, 0x42, 0x53, 0x64, 0x75, 0x86, 0x97, 0xA8, 0xB9, 0xCA, 0xDB, 0xEC, 0xFD, 0xFF, 0xFF,
      0x00, 0x00, 0x00, 0x10, 0x21, 0x32, 0x43, 0x54, 0x65, 0x76, 0x87, 0x98, 0xA9, 0xBA, 0xCB, 0xDC,
      0x00, 0x00, 0x00, 0x00, 0x05, 0x16, 0x27, 0x38, 0x49, 0x5A, 0x6B, 0x7C, 0x8D, 0x9E, 0xAF, 0xC0,
      0x00, 0x00, 0x00, 0x00, 0x00, 0x05, 0x16, 0x27, 0x38, 0x49, 0x5A, 0x6B, 0x7C, 0x8D, 0x9E, 0xAF,
      0x00, 0x00, 0x00, 0x00, 0x00, 0x01, 0x12, 0x23, 0x34, 0x45, 0x56, 0x67, 0x78, 0x89, 0x9A, 0xAB,
      0x00, 0x00, 0x00, 0x00, 0x07, 0x18, 0x29, 0x3A, 0x4B, 0x5C, 0x6D, 0x7E, 0x8F, 0xA0, 0xB1, 0xC2,
      0x00, 0x00, 0x02, 0x13, 0x24, 0x35, 0x46, 0x57, 0x68, 0x79, 0x8A, 0x9B, 0xAC, 0xBD, 0xCE, 0xDF,
      0x01, 0x12, 0x23, 0x34, 0x45, 0x56, 0x67, 0x78, 0x89, 0x9A, 0xAB, 0xBC, 0xCD, 0xDE, 0xEF, 0xFF,
      0x23, 0x34, 0x45, 0x56, 0x67, 0x78, 0x89, 0x9A, 0xAB, 0xBC, 0xCD, 0xDE, 0xEF, 0xFF, 0xFF, 0xFF,
      0x3F, 0x50, 0x61, 0x72, 0x83, 0x94, 0xA5, 0xB6, 0xC7, 0xD8, 0xE9, 0xFA, 0xFF, 0xFF, 0xFF, 0xFF)
    val palG = Array(
      0x00, 0x11, 0x22, 0x33, 0x44, 0x55, 0x66, 0x77, 0x88, 0x99, 0xAA, 0xBB, 0xCC, 0xDD, 0xEE, 0xFF,
      0x00, 0x05, 0x16, 0x27, 0x38, 0x49, 0x5A, 0x6B, 0x7C, 0x8D, 0x9E, 0xAF, 0xC0, 0xD1, 0xE2, 0xF3,
      0x00, 0x00, 0x03, 0x14, 0x25, 0x36, 0x47, 0x58, 0x69, 0x7A, 0x8B, 0x9C, 0xAD, 0xBE, 0xCF, 0xE0,
      0x00, 0x00, 0x00, 0x08, 0x19, 0x2A, 0x3B, 0x4C, 0x5D, 0x6E, 0x7F, 0x90, 0xA1, 0xB2, 0xC3, 0xD4,
      0x00, 0x00, 0x00, 0x01, 0x12, 0x23, 0x34, 0x45, 0x56, 0x67, 0x78, 0x89, 0x9A, 0xAB, 0xBC, 0xCD,
      0x00, 0x00, 0x00, 0x00, 0x11, 0x22, 0x33, 0x44, 0x55, 0x66, 0x77, 0x88, 0x99, 0xAA, 0xBB, 0xCC,
      0x00, 0x00, 0x00, 0x08, 0x19, 0x2A, 0x3B, 0x4C, 0x5D, 0x6E, 0x7F, 0x90, 0xA1, 0xB2, 0xC3, 0xD4,
      0x00, 0x08, 0x19, 0x2A, 0x3B, 0x4C, 0x5D, 0x6E, 0x7F, 0x90, 0xA1, 0xB2, 0xC3, 0xD4, 0xE5, 0xF6,
      0x0C, 0x1D, 0x2E, 0x3F, 0x50, 0x61, 0x72, 0x83, 0x94, 0xA5, 0xB6, 0xC7, 0xD8, 0xE9, 0xFA, 0xFF,
      0x1F, 0x30, 0x41, 0x52, 0x63, 0x74, 0x85, 0x96, 0xA7, 0xB8, 0xC9, 0xDA, 0xEB, 0xFC, 0xFF, 0xFF,
      0x2B, 0x3C, 0x4D, 0x5E, 0x6F, 0x80, 0x91, 0xA2, 0xB3, 0xC4, 0xD5, 0xE6, 0xF7, 0xFF, 0xFF, 0xFF,
      0x33, 0x44, 0x55, 0x66, 0x77, 0x88, 0x99, 0xAA, 0xBB, 0xCC, 0xDD, 0xEE, 0xFF, 0xFF, 0xFF, 0xFF,
      0x2B, 0x3C, 0x4D, 0x5E, 0x6F, 0x80, 0x91, 0xA2, 0xB3, 0xC4, 0xD5, 0xE6, 0xF7, 0xFF, 0xFF, 0xFF,
      0x1C, 0x2D, 0x3E, 0x4F, 0x60, 0x71, 0x82, 0x93, 0xA4, 0xB5, 0xC6, 0xD7, 0xE8, 0xF9, 0xFF, 0xFF,
      0x09, 0x1A, 0x2B, 0x3C, 0x4D, 0x5E, 0x6F, 0x80, 0x91, 0xA2, 0xB3, 0xC4, 0xD5, 0xE6, 0xF7, 0xFF,
      0x00, 0x05, 0x16, 0x27, 0x38, 0x49, 0x5A, 0x6B, 0x7C, 0x8D, 0x9E, 0xAF, 0xC0, 0xD1, 0xE2, 0xF3)
    val palB = Array(
      0x00, 0x11, 0x22, 0x33, 0x44, 0x55, 0x66, 0x77, 0x88, 0x99, 0xAA, 0xBB, 0xCC, 0xDD, 0xEE, 0xFF,
      0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x01, 0x12, 0x23, 0x34, 0x45, 0x56, 0x67, 0x78, 0x89, 0x9A,
      0x00, 0x00, 0x00, 0x03, 0x14, 0x25, 0x36, 0x47, 0x58, 0x69, 0x7A, 0x8B, 0x9C, 0xAD, 0xBE, 0xCF,
      0x03, 0x14, 0x25, 0x36, 0x47, 0x58, 0x69, 0x7A, 0x8B, 0x9C, 0xAD, 0xBE, 0xCF, 0xE0, 0xF1, 0xFF,
      0x35, 0x46, 0x57, 0x68, 0x79, 0x8A, 0x9B, 0xAC, 0xBD, 0xCE, 0xDF, 0xF0, 0xFF, 0xFF, 0xFF, 0xFF,
      0x68, 0x79, 0x8A, 0x9B, 0xAC, 0xBD, 0xCE, 0xDF, 0xF0, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF,
      0x8B, 0x9C, 0xAD, 0xBE, 0xCF, 0xE0, 0xF1, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF,
      0x89, 0x9A, 0xAB, 0xBC, 0xCD, 0xDE, 0xEF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF,
      0x65, 0x76, 0x87, 0x98, 0xA9, 0xBA, 0xCB, 0xDC, 0xED, 0xFE, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF,
      0x30, 0x41, 0x52, 0x63, 0x74, 0x85, 0x96, 0xA7, 0xB8, 0xC9, 0xDA, 0xEB, 0xFC, 0xFF, 0xFF, 0xFF,
      0x00, 0x0E, 0x1F, 0x30, 0x41, 0x52, 0x63, 0x74, 0x85, 0x96, 0xA7, 0xB8, 0xC9, 0xDA, 0xEB, 0xFC,
      0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x0F, 0x20, 0x31, 0x42, 0x53, 0x64, 0x75, 0x86, 0x97,
      0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x0E, 0x1F, 0x30, 0x41, 0x52, 0x63, 0x74,
      0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x03, 0x14, 0x25, 0x36, 0x47, 0x58, 0x69,
      0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x10, 0x21, 0x32, 0x43, 0x54, 0x65, 0x76,
      0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x01, 0x12, 0x23, 0x34, 0x45, 0x56, 0x67, 0x78, 0x89, 0x9A)

    def writePpm(frameNum: Int): Unit = {
      val width  = if (fbMaxPixelX > 0) fbMaxPixelX else 1
      val height = if (fbMaxPixelY > 0) fbMaxPixelY else 1
      val dir = new File(s"sim_workspace/Atari800_disk_${diskName}")
      dir.mkdirs()
      val path = s"${dir.getPath}/${diskName}_frame_$frameNum.ppm"
      val fos = new BufferedOutputStream(new FileOutputStream(path))
      val header = s"P6\n$width $height\n255\n"
      fos.write(header.getBytes)
      for (y <- 0 until height; x <- 0 until width) {
        fos.write(frameR(y)(x))
        fos.write(frameG(y)(x))
        fos.write(frameB(y)(x))
      }
      fos.close()
      println(s"  Frame $frameNum captured: ${width}x${height} -> $path")
    }

    // Run for enough time: disk boot is slow. ~180 sectors × 67ms/sector ≈ 12 seconds
    // At 56.67 MHz that's ~680M cycles. Start with a shorter run and extend if needed.
    // Boot sectors (1-3) load first, then the loader reads the rest.
    // 60M cycles ≈ 1 second — enough for boot + a few frames of loaded program.
    // Jumpman loads ~180 sectors × ~67ms = ~12 sec = ~680M cycles.
    // Add extra for post-load display. Total ~15 seconds.
    val totalCycles = 850_000_000L  // ~15 seconds of Atari time
    val reportInterval = 50_000_000

    println(s"Starting Atari 800 disk boot simulation: $totalCycles cycles")
    println(s"  ATR: $atrPath ($diskName), sector size=${atrFile.sectorSize}")
    println("=" * 60)

    var done = false
    while (!done) {
      dut.clockDomain.waitRisingEdge()
      cycleCount += 1
      if (cycleCount >= totalCycles) done = true

      // SDRAM behavioral model
      val request   = dut.io.sdramRequest.toBoolean
      val readEn    = dut.io.sdramReadEnable.toBoolean
      val writeEn   = dut.io.sdramWriteEnable.toBoolean
      val write8    = dut.io.sdramWrite8.toBoolean
      val write16   = dut.io.sdramWrite16.toBoolean
      val write32   = dut.io.sdramWrite32.toBoolean
      val addr      = dut.io.sdramAddr.toInt

      if (request) {
        if (writeEn) {
          val data = dut.io.sdramDi.toLong
          sdram.write(addr, data, write8, write16, write32)
          sdramWrites += 1
        }
        if (readEn) {
          val data = sdram.read32(addr)
          dut.io.sdramDo #= data
          sdramReads += 1
        }
        dut.io.sdramRequestComplete #= true
      } else {
        dut.io.sdramRequestComplete #= false
      }

      // SIO disk responder
      val sioTxd     = dut.io.sio_txd.toBoolean
      val sioCmd     = dut.io.sio_command.toBoolean
      val sioClkout  = dut.io.sio_clockout.toBoolean
      val sioRxd     = sio.tick(sioTxd, sioCmd, sioClkout)
      dut.io.sio_rxd #= sioRxd

      // CPU enable counting + display diagnostics
      val cpuEn = dut.atariCore.atari800xl.cpu6502.CPU_ENABLE.toBoolean
      if (cpuEn) {
        cpuEnCount += 1
        val we = dut.atariCore.atari800xl.cpu6502.WE.toBoolean
        if (we) {
          val cpuAddr = dut.atariCore.atari800xl.cpu6502.addrUnsigned.toInt
          val doVal = dut.atariCore.atari800xl.cpu6502.doUnsigned.toInt
          val pc = dut.atariCore.atari800xl.cpu6502.debugPc.toInt
          cpuAddr match {
            case 0xD400 => println(f"  ** DMACTL write: $$${doVal}%02X at cpuEN=$cpuEnCount%d cyc=$cycleCount%d PC=$$${pc}%04X")
            case 0xD402 => println(f"  ** DLISTL write: $$${doVal}%02X at cpuEN=$cpuEnCount%d cyc=$cycleCount%d PC=$$${pc}%04X")
            case 0xD403 => println(f"  ** DLISTH write: $$${doVal}%02X at cpuEN=$cpuEnCount%d cyc=$cycleCount%d PC=$$${pc}%04X")
            case _ =>
          }
        }
      }

      // Sync edge detection
      val hsync = dut.io.vga_hsync.toBoolean
      val vsync = dut.io.vga_vsync.toBoolean
      if (hsync && !lastHsync) hsyncCount += 1
      if (vsync && !lastVsync) vsyncCount += 1
      lastHsync = hsync
      lastVsync = vsync

      // Raw frame capture
      val rawHsync = dut.io.videoHs.toBoolean
      val rawVsync = dut.io.videoVs.toBoolean
      val colClk   = dut.atariCore.atari800xl.antic1.colourClock1x.toBoolean

      if (rawVsync && !fbLastRawVsync) {
        if (fbCaptureFrame && fbPixelY > 0 && fbFramesCaptured < maxCaptures) {
          fbMaxPixelY = fbPixelY
          writePpm(fbFramesCaptured)
          fbFramesCaptured += 1
        }
        fbPixelY = 0; fbPixelX = 0; fbMaxPixelX = 0
        // Capture one frame every 25 vsyncs (~0.5 sec), starting after vsync 5
        if (vsyncCount >= 5 && (vsyncCount - lastVsyncForCapture) >= 25) {
          fbCaptureFrame = true
          lastVsyncForCapture = vsyncCount
        } else {
          fbCaptureFrame = false  // only capture designated frames
        }
      }
      if (rawHsync && !fbLastRawHsync && fbCaptureFrame) {
        if (fbPixelX > fbMaxPixelX) fbMaxPixelX = fbPixelX
        fbPixelX = 0
        fbPixelY += 1
        if (fbPixelY >= maxFbHeight) fbPixelY = maxFbHeight - 1
      }
      if (fbCaptureFrame && colClk && !fbLastColClk && !rawVsync) {
        if (fbPixelX < maxFbWidth && fbPixelY < maxFbHeight) {
          val colIdx = dut.io.dbgVideoB.toInt & 0xFF
          frameR(fbPixelY)(fbPixelX) = palR(colIdx)
          frameG(fbPixelY)(fbPixelX) = palG(colIdx)
          frameB(fbPixelY)(fbPixelX) = palB(colIdx)
        }
        fbPixelX += 1
      }
      fbLastRawHsync = rawHsync
      fbLastRawVsync = rawVsync
      fbLastColClk   = colClk

      // Periodic status
      if (cycleCount % reportInterval == 0) {
        val timeUs = cycleCount.toDouble * clockPeriod / 1e6
        println(f"  Cycle $cycleCount%,d (${timeUs}%.0f µs): " +
          f"vsync=$vsyncCount%d frames=$fbFramesCaptured%d " +
          f"SDRAM rd=$sdramReads%d wr=$sdramWrites%d " +
          f"SIO: cmds=${sio.commandsReceived}%d sectors=${sio.sectorsServed}%d")
      }
    }

    println("=" * 60)
    println(s"Simulation complete: $cycleCount cycles")
    println(s"  VGA: $hsyncCount hsync, $vsyncCount vsync")
    println(s"  SDRAM: $sdramReads reads, $sdramWrites writes")
    println(s"  SIO: ${sio.commandsReceived} commands, ${sio.sectorsServed} sectors served, ${sio.nakCount} NAKs")
    println(s"  Frames captured: $fbFramesCaptured")
    println(s"  CPU enables: $cpuEnCount")

    // Dump key zero-page / OS locations from SDRAM
    // NOTE: With internal_ram > 0, lower RAM is in BRAM (not visible here — will show zeros)
    println("\n--- SDRAM RAM dump (key OS locations — zeros expected if BRAM-only) ---")
    def sdramByte(cpuAddr: Int): Int = {
      // Behavioral model uses byte-level addressing directly
      sdram.mem(cpuAddr & (sdram.sizeBytes - 1)) & 0xFF
    }
    val ramtop = sdramByte(0x6A)
    val sdmctl = sdramByte(0x022F)
    val dlistL = sdramByte(0x0230)
    val dlistH = sdramByte(0x0231)
    val chbas  = sdramByte(0x02F4)
    val warmst = sdramByte(0x08)   // warm start flag
    val boot   = sdramByte(0x09)   // boot completion flag
    val dosini_l = sdramByte(0x0C)
    val dosini_h = sdramByte(0x0D)
    val memlo_l = sdramByte(0x02E7)
    val memlo_h = sdramByte(0x02E8)
    printf(f"  RAMTOP=$$${ramtop}%02X SDMCTL=$$${sdmctl}%02X DLIST=$$${dlistH}%02X${dlistL}%02X CHBAS=$$${chbas}%02X%n")
    printf(f"  WARMST=$$${warmst}%02X BOOT=$$${boot}%02X DOSINI=$$${dosini_h}%02X${dosini_l}%02X MEMLO=$$${memlo_h}%02X${memlo_l}%02X%n")
    val dlistAddr = dlistL | (dlistH << 8)
    if (dlistAddr > 0 && dlistAddr < 0xC000) {
      print(f"  DLIST @$$${dlistAddr}%04X: ")
      for (i <- 0 until 32) printf(f"${sdramByte(dlistAddr + i)}%02X ")
      println()
    }
    // Dump display list contents if valid
    if (dlistAddr > 0 && dlistAddr < 0xC000) {
      // Find LMS addresses in display list
      var dlOff = 0
      var lmsCount = 0
      while (dlOff < 64 && lmsCount < 5) {
        val instr = sdramByte(dlistAddr + dlOff)
        val mode = instr & 0x0F
        if ((instr & 0x40) != 0 && mode != 0) { // LMS bit set
          val lo = sdramByte(dlistAddr + dlOff + 1)
          val hi = sdramByte(dlistAddr + dlOff + 2)
          val lmsAddr = lo | (hi << 8)
          printf(f"  DL LMS: mode=$mode%X addr=$$${lmsAddr}%04X (at DL+$dlOff%d)%n")
          lmsCount += 1
          dlOff += 3
        } else if (mode == 1) { // JVB
          val lo = sdramByte(dlistAddr + dlOff + 1)
          val hi = sdramByte(dlistAddr + dlOff + 2)
          printf(f"  DL JVB: → $$${hi}%02X${lo}%02X (at DL+$dlOff%d)%n")
          dlOff = 64 // stop
        } else {
          dlOff += 1
        }
      }
    }
    // Dump Jumpman-relevant SDRAM areas
    print("  PMBASE area $7800 (P0): ")
    for (i <- 0 until 16) printf(f"${sdramByte(0x7800 + i)}%02X ")
    println()
    print("  Code at $4000: ")
    for (i <- 0 until 32) printf(f"${sdramByte(0x4000 + i)}%02X ")
    println()
  }
}
