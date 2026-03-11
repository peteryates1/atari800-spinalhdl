package atari800

import spinal.core._
import spinal.core.sim._

// Behavioral SDRAM model - responds to Atari core's SDRAM request interface directly
// (bypasses the SDRAM controller, provides instant responses)
class SdramBehavioral(sizeBytes: Int = 512 * 1024) {
  val mem = Array.fill[Byte](sizeBytes)(0.toByte)

  def read32(addr: Int): Long = {
    val a = (addr & (sizeBytes - 1)) & ~3 // word-aligned
    ((mem(a) & 0xFFL)) |
    ((mem(a + 1) & 0xFFL) << 8) |
    ((mem(a + 2) & 0xFFL) << 16) |
    ((mem(a + 3) & 0xFFL) << 24)
  }

  def write(addr: Int, data: Long, write8: Boolean, write16: Boolean, write32: Boolean): Unit = {
    val a = addr & (sizeBytes - 1)
    if (write8) {
      val byteOfs = a & 3
      val base = a & ~3
      mem(base + byteOfs) = (data >> (byteOfs * 8)).toByte
    } else if (write16) {
      val wordOfs = a & 2
      val base = a & ~3
      val d = (data >> (wordOfs * 8)).toInt
      mem(base + wordOfs)     = (d & 0xFF).toByte
      mem(base + wordOfs + 1) = ((d >> 8) & 0xFF).toByte
    } else if (write32) {
      val base = a & ~3
      mem(base)     = (data & 0xFF).toByte
      mem(base + 1) = ((data >> 8) & 0xFF).toByte
      mem(base + 2) = ((data >> 16) & 0xFF).toByte
      mem(base + 3) = ((data >> 24) & 0xFF).toByte
    }
  }
}

object Atari800CoreSimTb extends App {
  val compiled = SimConfig
    .withConfig(SpinalConfig(
      defaultClockDomainFrequency = FixedFrequency(56.67 MHz),
      defaultConfigForClockDomains = ClockDomainConfig(
        resetKind = ASYNC,
        resetActiveLevel = HIGH
      )
    ))
    .withWave                       // Generate VCD waveform
    .workspacePath("sim_workspace")
    .addSimulatorFlag("-Wno-WIDTHEXPAND")
    .addSimulatorFlag("-Wno-WIDTHTRUNC")
    .addSimulatorFlag("--x-initial-edge")
    .addSimulatorFlag("--x-assign 0")
    .compile(new Atari800CoreSim)

  compiled.doSim("Atari800_boot_test", seed = 42) { dut =>
    val sdram = new SdramBehavioral()

    // Clock period: ~17.64ns for 56.67 MHz
    val clockPeriod = 17640  // ps

    // Fork the clock
    dut.clockDomain.forkStimulus(period = clockPeriod)

    // Initialize inputs
    dut.io.reset_btn  #= false
    dut.io.option_btn #= false
    dut.io.select_btn #= false
    dut.io.start_btn  #= false
    dut.io.joy1       #= 0x1F  // all released (active low)
    dut.io.sdramRequestComplete #= false
    dut.io.sdramDo   #= 0

    // Hold reset for 100 cycles
    dut.clockDomain.waitRisingEdge(10)
    dut.clockDomain.assertReset()
    dut.clockDomain.waitRisingEdge(100)
    dut.clockDomain.deassertReset()

    // Tracking variables
    var hsyncCount   = 0
    var vsyncCount   = 0
    var lastHsync    = false
    var lastVsync    = false
    var sdramReads   = 0
    var sdramWrites  = 0
    var cycleCount   = 0
    var debugCount   = 0
    var writeEnCount = 0
    var reqCount     = 0
    var lastAddr     = -1
    var addrChanges  = 0
    var memReadyCount = 0
    var thrCount     = 0
    var cpuEnCount   = 0

    // Run for ~2 frames worth of cycles
    val totalCycles = 2000000
    val reportInterval = 200000

    println(s"Starting simulation: $totalCycles cycles")
    println("=" * 60)

    for (_ <- 0 until totalCycles) {
      dut.clockDomain.waitRisingEdge()
      cycleCount += 1

      // Handle SDRAM requests - immediate response (combinatorial model)
      val request   = dut.io.sdramRequest.toBoolean
      val readEn    = dut.io.sdramReadEnable.toBoolean
      val writeEn   = dut.io.sdramWriteEnable.toBoolean
      val write8    = dut.io.sdramWrite8.toBoolean
      val write16   = dut.io.sdramWrite16.toBoolean
      val write32   = dut.io.sdramWrite32.toBoolean
      val addr      = dut.io.sdramAddr.toInt
      val refresh   = dut.io.sdramRefresh.toBoolean

      if (writeEn) writeEnCount += 1

      // Count CPU enable sub-signals
      if (dut.io.dbgMemReady.toBoolean) memReadyCount += 1
      if (dut.io.dbgThrottle.toBoolean) thrCount += 1
      if (dut.atariCore.atari800xl.cpu6502.CPU_ENABLE.toBoolean) {
        cpuEnCount += 1
        val we = dut.atariCore.atari800xl.cpu6502.WE.toBoolean
        if (we) writeEnCount += 1
        // Trace CPU state periodically
        if (cpuEnCount <= 20 || cpuEnCount % 200000 == 0) {
          val pc = dut.atariCore.atari800xl.cpu6502.debugPc.toInt
          val a = dut.atariCore.atari800xl.cpu6502.debugA.toInt
          val cpuCycleVal = dut.atariCore.atari800xl.cpu6502.cpu6502.theCpuCycle.toEnum.toString
          println(f"  CPU#$cpuEnCount%d cyc=$cycleCount%d PC=0x${pc}%04X A=0x${a}%02X WE=$we st=$cpuCycleVal")
        }
      }

      if (request) {
        reqCount += 1
        if (addr != lastAddr) { addrChanges += 1; lastAddr = addr }
        if (writeEn) {
          val data = dut.io.sdramDi.toLong
          sdram.write(addr, data, write8, write16, write32)
          sdramWrites += 1
          if (sdramWrites <= 10) {
            println(f"  WRITE[$sdramWrites%d] addr=0x${addr}%06X data=0x${data}%08X w8=$write8 w16=$write16 w32=$write32")
          }
        }
        if (readEn) {
          val data = sdram.read32(addr)
          dut.io.sdramDo #= data
          sdramReads += 1
        }
        dut.io.sdramRequestComplete #= true
      } else {
        dut.io.sdramRequestComplete #= refresh  // ack refreshes too
      }

      // Detect VGA sync edges
      val hsync = dut.io.vga_hsync.toBoolean
      val vsync = dut.io.vga_vsync.toBoolean

      if (hsync && !lastHsync) hsyncCount += 1
      if (vsync && !lastVsync) vsyncCount += 1

      lastHsync = hsync
      lastVsync = vsync

      // Periodic status
      if (cycleCount % reportInterval == 0) {
        val timeUs = cycleCount.toDouble * clockPeriod / 1e6
        println(f"  Cycle $cycleCount%,d (${timeUs}%.0f us): " +
          f"hsync=$hsyncCount%d vsync=$vsyncCount%d " +
          f"SDRAM rd=$sdramReads%d wr=$sdramWrites%d " +
          f"MRcnt=$memReadyCount%d THRcnt=$thrCount%d cpuEN=$cpuEnCount%d")
      }
    }

    println("=" * 60)
    println(s"Simulation complete: $cycleCount cycles")
    println(s"  VGA: $hsyncCount hsync, $vsyncCount vsync")
    println(s"  SDRAM: $sdramReads reads, $sdramWrites writes")

    if (hsyncCount > 10) {
      println(s"  PASS: VGA hsync active ($hsyncCount pulses)")
    } else {
      println(s"  WARN: Low VGA hsync count ($hsyncCount)")
    }

    if (vsyncCount >= 1) {
      println(s"  PASS: VGA vsync detected ($vsyncCount frames)")
    } else {
      println(s"  WARN: No VGA vsync (may need more cycles)")
    }

    println(s"  Waveform: sim_workspace/Atari800CoreSim/Atari800_boot_test.vcd")
  }
}
