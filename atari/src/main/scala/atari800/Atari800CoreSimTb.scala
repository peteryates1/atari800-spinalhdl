package atari800

import spinal.core._
import spinal.core.sim._
import java.io.{FileOutputStream, BufferedOutputStream, File}

// Behavioral SDRAM model - responds to Atari core's SDRAM request interface directly
// (bypasses the SDRAM controller, provides instant responses)
//
// Byte lane convention (matches AddressDecoder):
//   - CPU byte write:  data always in bits [7:0], addr includes byte offset
//   - CPU byte read:   expects addressed byte in bits [7:0] of returned word
//   - DMA 32-bit:      full 32 bits, addr word-aligned
//   - DMA 16-bit:      data in bits [15:0], addr half-word aligned
class SdramBehavioral(val sizeBytes: Int = 512 * 1024) {
  val mem = Array.fill[Byte](sizeBytes)(0.toByte)

  def read32(addr: Int): Long = {
    val a = addr & (sizeBytes - 1)
    ((mem(a) & 0xFFL)) |
    ((mem((a + 1) & (sizeBytes - 1)) & 0xFFL) << 8) |
    ((mem((a + 2) & (sizeBytes - 1)) & 0xFFL) << 16) |
    ((mem((a + 3) & (sizeBytes - 1)) & 0xFFL) << 24)
  }

  def write(addr: Int, data: Long, write8: Boolean, write16: Boolean, write32: Boolean): Unit = {
    val a = addr & (sizeBytes - 1)
    if (write8) {
      mem(a) = (data & 0xFF).toByte
    } else if (write16) {
      mem(a) = (data & 0xFF).toByte
      mem((a + 1) & (sizeBytes - 1)) = ((data >> 8) & 0xFF).toByte
    } else if (write32) {
      mem(a) = (data & 0xFF).toByte
      mem((a + 1) & (sizeBytes - 1)) = ((data >> 8) & 0xFF).toByte
      mem((a + 2) & (sizeBytes - 1)) = ((data >> 16) & 0xFF).toByte
      mem((a + 3) & (sizeBytes - 1)) = ((data >> 24) & 0xFF).toByte
    }
  }
}

object Atari800CoreSimTb extends App {
  val compiled = SimConfig
    .withConfig(SpinalConfig(
      defaultClockDomainFrequency = FixedFrequency(56.67 MHz),
      defaultConfigForClockDomains = ClockDomainConfig(
        resetKind = ASYNC,
        resetActiveLevel = LOW   // Must match Atari core's RESET_N (active-low)
      )
    ))
    //.withWave                     // Disabled: VCD files are huge (3GB+)
    .workspacePath("sim_workspace")
    .addSimulatorFlag("-Wno-WIDTHEXPAND")
    .addSimulatorFlag("-Wno-WIDTHTRUNC")
    .addSimulatorFlag("--x-initial-edge")
    .addSimulatorFlag("--x-assign 0")
    .compile(new Atari800CoreSim(cartridge_rom = "roms/Star Raiders.rom"))

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
    val distinctVidB = scala.collection.mutable.Set[Int]()
    var anticDmaCount = 0
    var anticDmaRomCount = 0  // $E000-$FFFF (character generator reads)
    var anticDmaRamCount = 0  // $0000-$3FFF (screen memory reads)
    val distinctAN = scala.collection.mutable.Set[Int]()
    var lastFirstLine = false
    var firstLineTransitions = 0
    var lbWriteCount = 0
    var cacheReadyCount = 0
    var lastDmaFetch = false
    var dmaFetchLbCount = 0
    var instrFetchCount = 0
    var srLoadCount = 0
    var lbNzCount = 0
    var srNzCount = 0
    var pfSrNzCount = 0      // displayShiftReg != 0 during pfActive + colourClockSelected
    var pfDsrNzCount = 0     // delayDisplayShiftReg bits 1:0 non-zero during pfActive
    var pfAnFgCount = 0      // AN is foreground (not 0 or 4) during pfActive
    var anPipeCount = 0       // separate counter for AN pipeline trace
    var memReadyAnticCount = 0
    var flTraceCount = 0
    var cpuEnCount   = 0
    var maxVideoB    = 0
    var nzVideoB     = 0
    var vbColpf0Count = 0  // $28 = COLPF0
    var vbColpf1Count = 0  // $CA = COLPF1
    var vbColpf2Count = 0  // $94 = COLPF2
    var vbColpf3Count = 0  // $46 = COLPF3
    var vbColbkCount  = 0  // $00 = COLBK
    var lastDmactl   = -1
    var lastColbk    = -1
    var gtiaWrites   = 0
    var anticWrites  = 0
    val pcPageSeen   = Array.fill(16)(false)  // track which 4K pages visited

    // Raw Atari frame capture — samples VIDEO_B on colour clock, applies PAL palette in software
    val maxFbWidth  = 256  // ~228 colour clocks per line
    val maxFbHeight = 320  // ~312 lines per PAL frame
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

    // Full PAL palette (256 entries from GtiaPalette.scala)
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

    // Derive frame name from cartridge ROM path
    val cartName = {
      val rom = "Star Raiders.rom"
      if (rom.nonEmpty) rom.replaceAll("[^a-zA-Z0-9]", "_").replaceAll("_+$", "")
      else "basic"
    }

    def writePpm(frameNum: Int): Unit = {
      val width  = if (fbMaxPixelX > 0) fbMaxPixelX else 1
      val height = if (fbMaxPixelY > 0) fbMaxPixelY else 1
      val dir = new File("sim_workspace/Atari800_boot_test")
      dir.mkdirs()
      val path = s"sim_workspace/Atari800_boot_test/${cartName}_frame_$frameNum.ppm"
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

    val totalCycles = 14000000   // ~247ms = ~12 PAL frames, captures 3 frames after boot
    val reportInterval = 2000000
    var lastNmienRaw = -1
    var lastNmiOut = true
    var vbiNmiEvents = 0
    var nmiEdgeCount = 0
    var maxVcount = 0
    var nmiHandlerCount = 0
    var nmiTraceRemaining = 0
    var irqCount = 0
    var lastIrqN = true
    var lastIrqst = 0xFF
    var lastIrqen = 0x00
    var irqHandlerCount = 0
    var irqTraceRemaining = 0
    var lastCritic = 0

    println(s"Starting Atari 800 simulation: $totalCycles cycles (internal_rom=3, no cartridge)")
    println("=" * 60)

    for (_ <- 0 until totalCycles) {
      dut.clockDomain.waitRisingEdge()
      cycleCount += 1

      // Handle SDRAM requests - immediate response (combinatorial model)
      // IMPORTANT: sdramRequestComplete must be FALSE when no data request is
      // active, even during refresh.
      val request   = dut.io.sdramRequest.toBoolean
      val readEn    = dut.io.sdramReadEnable.toBoolean
      val writeEn   = dut.io.sdramWriteEnable.toBoolean
      val write8    = dut.io.sdramWrite8.toBoolean
      val write16   = dut.io.sdramWrite16.toBoolean
      val write32   = dut.io.sdramWrite32.toBoolean
      val addr      = dut.io.sdramAddr.toInt

      val cpuEn = dut.atariCore.atari800xl.cpu6502.CPU_ENABLE.toBoolean

      if (cpuEn) {
        cpuEnCount += 1
        val pc = dut.atariCore.atari800xl.cpu6502.debugPc.toInt
        val a = dut.atariCore.atari800xl.cpu6502.debugA.toInt
        val flags = dut.atariCore.atari800xl.cpu6502.debugFlags.toInt
        val we = dut.atariCore.atari800xl.cpu6502.WE.toBoolean
        val cpuAddr = dut.atariCore.atari800xl.cpu6502.addrUnsigned.toInt

        // Log first entry into each 4K page
        val pcPage = (pc >> 12) & 0xF
        if (!pcPageSeen(pcPage)) {
          pcPageSeen(pcPage) = true
          println(f"  ** First PC in page 0x${pcPage}%X000 at cpuEN=$cpuEnCount%d cyc=$cycleCount%d PC=0x${pc}%04X")
        }

        // Track key hardware register writes and OS shadow registers
        if (we) {
          val doVal = dut.atariCore.atari800xl.cpu6502.doUnsigned.toInt
          cpuAddr match {
            case 0xD400 => println(f"  ** DMACTL write: $$${doVal}%02X at cpuEN=$cpuEnCount%d cyc=$cycleCount%d PC=$pc%04X")
            case 0xD402 => println(f"  ** DLISTL write: $$${doVal}%02X at cpuEN=$cpuEnCount%d cyc=$cycleCount%d")
            case 0xD403 => println(f"  ** DLISTH write: $$${doVal}%02X at cpuEN=$cpuEnCount%d cyc=$cycleCount%d")
            case 0xD40E => println(f"  ** NMIEN write: $$${doVal}%02X at cpuEN=$cpuEnCount%d cyc=$cycleCount%d PC=$pc%04X")
            case 0xD40F => println(f"  ** NMIRES write: $$${doVal}%02X at cpuEN=$cpuEnCount%d cyc=$cycleCount%d PC=$pc%04X")
            case 0xD016 => println(f"  ** COLPF0 write: $$${doVal}%02X at cpuEN=$cpuEnCount%d cyc=$cycleCount%d")
            case 0xD017 => println(f"  ** COLPF1 write: $$${doVal}%02X at cpuEN=$cpuEnCount%d cyc=$cycleCount%d")
            case 0xD018 => println(f"  ** COLPF2 write: $$${doVal}%02X at cpuEN=$cpuEnCount%d cyc=$cycleCount%d")
            case 0xD019 => println(f"  ** COLPF3 write: $$${doVal}%02X at cpuEN=$cpuEnCount%d cyc=$cycleCount%d")
            case 0xD01A => println(f"  ** COLBK write: $$${doVal}%02X at cpuEN=$cpuEnCount%d cyc=$cycleCount%d")
            // OS shadow registers and flags
            case 0x0042 => println(f"  ** CRITIC write: $$${doVal}%02X at cpuEN=$cpuEnCount%d cyc=$cycleCount%d PC=$pc%04X")
            case 0x022F => println(f"  ** SDMCTL shadow write: $$${doVal}%02X at cpuEN=$cpuEnCount%d cyc=$cycleCount%d PC=$pc%04X")
            case 0x0222 => println(f"  ** VVBLKI_LO write: $$${doVal}%02X at cpuEN=$cpuEnCount%d cyc=$cycleCount%d")
            case 0x0223 => println(f"  ** VVBLKI_HI write: $$${doVal}%02X at cpuEN=$cpuEnCount%d cyc=$cycleCount%d")
            case 0x0224 => println(f"  ** VVBLKD_LO write: $$${doVal}%02X at cpuEN=$cpuEnCount%d cyc=$cycleCount%d")
            case 0x0225 => println(f"  ** VVBLKD_HI write: $$${doVal}%02X at cpuEN=$cpuEnCount%d cyc=$cycleCount%d")
            // POKEY registers (SIO/timer related)
            case 0xD200 => println(f"  ** AUDF0 write: $$${doVal}%02X at cpuEN=$cpuEnCount%d cyc=$cycleCount%d PC=$pc%04X")
            case 0xD201 => println(f"  ** AUDC0 write: $$${doVal}%02X at cpuEN=$cpuEnCount%d cyc=$cycleCount%d PC=$pc%04X")
            case 0xD202 => println(f"  ** AUDF1 write: $$${doVal}%02X at cpuEN=$cpuEnCount%d cyc=$cycleCount%d PC=$pc%04X")
            case 0xD203 => println(f"  ** AUDC1 write: $$${doVal}%02X at cpuEN=$cpuEnCount%d cyc=$cycleCount%d PC=$pc%04X")
            case 0xD204 => println(f"  ** AUDF2 write: $$${doVal}%02X at cpuEN=$cpuEnCount%d cyc=$cycleCount%d PC=$pc%04X")
            case 0xD205 => println(f"  ** AUDC2 write: $$${doVal}%02X at cpuEN=$cpuEnCount%d cyc=$cycleCount%d PC=$pc%04X")
            case 0xD206 => println(f"  ** AUDF3 write: $$${doVal}%02X at cpuEN=$cpuEnCount%d cyc=$cycleCount%d PC=$pc%04X")
            case 0xD207 => println(f"  ** AUDC3 write: $$${doVal}%02X at cpuEN=$cpuEnCount%d cyc=$cycleCount%d PC=$pc%04X")
            case 0xD208 => println(f"  ** AUDCTL write: $$${doVal}%02X at cpuEN=$cpuEnCount%d cyc=$cycleCount%d PC=$pc%04X")
            case 0xD209 => println(f"  ** STIMER write: $$${doVal}%02X at cpuEN=$cpuEnCount%d cyc=$cycleCount%d PC=$pc%04X")
            case 0xD20D => println(f"  ** SEROUT write: $$${doVal}%02X at cpuEN=$cpuEnCount%d cyc=$cycleCount%d PC=$pc%04X")
            case 0xD20E => println(f"  ** IRQEN write: $$${doVal}%02X at cpuEN=$cpuEnCount%d cyc=$cycleCount%d PC=$pc%04X")
            case 0xD20F => println(f"  ** SKCTL write: $$${doVal}%02X at cpuEN=$cpuEnCount%d cyc=$cycleCount%d PC=$pc%04X")
            // PIA (SIO direction control)
            case 0xD302 => println(f"  ** PACTL write: $$${doVal}%02X at cpuEN=$cpuEnCount%d cyc=$cycleCount%d PC=$pc%04X")
            case 0xD303 => println(f"  ** PBCTL write: $$${doVal}%02X at cpuEN=$cpuEnCount%d cyc=$cycleCount%d PC=$pc%04X")
            case 0xD300 => println(f"  ** PORTA write: $$${doVal}%02X at cpuEN=$cpuEnCount%d cyc=$cycleCount%d PC=$pc%04X")
            case 0xD301 => println(f"  ** PORTB write: $$${doVal}%02X at cpuEN=$cpuEnCount%d cyc=$cycleCount%d PC=$pc%04X")
            case _ =>
          }
        }

        // Track NMI handler entry and trace first 60 opcode fetches
        val myAddrVal = dut.atariCore.atari800xl.cpu6502.cpu6502.myAddr.toInt
        val cpuCycleSt = dut.atariCore.atari800xl.cpu6502.cpu6502.theCpuCycle.toEnum.toString
        if (myAddrVal == 0xE791 && cpuCycleSt.contains("opcodeFetch")) {
          nmiHandlerCount += 1
          val x = dut.atariCore.atari800xl.cpu6502.debugX.toInt
          val s = dut.atariCore.atari800xl.cpu6502.debugS.toInt
          println(f"  ** NMI[$nmiHandlerCount%d] entry E791 cpuEN=$cpuEnCount%d cyc=$cycleCount%d A=$a%02X X=$x%02X S=$s%02X flags=$flags%02X")
          if (nmiHandlerCount <= 3) nmiTraceRemaining = 60
        }
        // Trace opcode fetches after NMI entry
        if (nmiTraceRemaining > 0 && cpuCycleSt.contains("opcodeFetch")) {
          val di = dut.atariCore.atari800xl.cpu6502.diUnsigned.toInt
          val x = dut.atariCore.atari800xl.cpu6502.debugX.toInt
          val s = dut.atariCore.atari800xl.cpu6502.debugS.toInt
          println(f"    NMI trace: PC=$myAddrVal%04X op=$di%02X A=$a%02X X=$x%02X S=$s%02X flags=$flags%02X")
          nmiTraceRemaining -= 1
        }

        // Track IRQ handler entry - detect BRK/IRQ vector fetch
        // The Atari 800 OS IRQ handler can be at various addresses; track any opcode fetch
        // at the IRQ vector target
        val cpuCycleSt2 = dut.atariCore.atari800xl.cpu6502.cpu6502.theCpuCycle.toEnum.toString
        val myAddrVal2 = dut.atariCore.atari800xl.cpu6502.cpu6502.myAddr.toInt
        // Track when CPU processes IRQ (I flag clear + IRQ_N low triggers sequence)
        if (irqTraceRemaining > 0 && cpuCycleSt2.contains("opcodeFetch")) {
          val di = dut.atariCore.atari800xl.cpu6502.diUnsigned.toInt
          val x = dut.atariCore.atari800xl.cpu6502.debugX.toInt
          val s = dut.atariCore.atari800xl.cpu6502.debugS.toInt
          println(f"    IRQ trace: PC=$myAddrVal2%04X op=$di%02X A=$a%02X X=$x%02X S=$s%02X flags=$flags%02X")
          irqTraceRemaining -= 1
        }

        // Trace CPU state: first 20 cycles + periodic
        if (cpuEnCount <= 20 || cpuEnCount % 500000 == 0) {
          val x = dut.atariCore.atari800xl.cpu6502.debugX.toInt
          val y = dut.atariCore.atari800xl.cpu6502.debugY.toInt
          val s = dut.atariCore.atari800xl.cpu6502.debugS.toInt
          val cpuCycleSt = dut.atariCore.atari800xl.cpu6502.cpu6502.theCpuCycle.toEnum.toString
          println(f"  CPU#$cpuEnCount%d cyc=$cycleCount%d PC=$pc%04X A=$a%02X X=$x%02X Y=$y%02X S=$s%02X flags=$flags%02X st=$cpuCycleSt")
        }
      }

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

      // POKEY IRQ tracking (use internal regs, not io ports, for sim access)
      val irqN = dut.atariCore.atari800xl.pokey1.irqNReg.toBoolean
      val irqst = dut.atariCore.atari800xl.pokey1.irqstReg.toInt
      val irqen = dut.atariCore.atari800xl.pokey1.irqenReg.toInt
      if (!irqN && lastIrqN) {
        irqCount += 1
        if (irqCount <= 20) {
          println(f"  ** IRQ_N fell (#$irqCount%d) at cyc=$cycleCount%d IRQST=$$${irqst}%02X IRQEN=$$${irqen}%02X")
        }
        irqHandlerCount += 1
        if (irqHandlerCount <= 5) irqTraceRemaining = 30
      }
      if (irqN && !lastIrqN && irqCount <= 30) {
        println(f"  ** IRQ_N rose at cyc=$cycleCount%d IRQST=$$${irqst}%02X IRQEN=$$${irqen}%02X")
      }
      lastIrqN = irqN
      if (irqst != lastIrqst) {
        if (cycleCount < 1500000) {  // only log very early changes
          println(f"  ** IRQST changed: $$${lastIrqst}%02X -> $$${irqst}%02X at cyc=$cycleCount%d")
        }
        lastIrqst = irqst
      }
      if (irqen != lastIrqen) {
        if (cycleCount < 1500000) {
          println(f"  ** IRQEN reg changed: $$${lastIrqen}%02X -> $$${irqen}%02X at cyc=$cycleCount%d")
        }
        lastIrqen = irqen
      }

      // ANTIC NMI tracking
      val nmienRaw = dut.atariCore.atari800xl.antic1.nmienRawReg.toInt
      val vbiNmi   = dut.atariCore.atari800xl.antic1.vbiNmiReg.toBoolean
      val nmiOut   = dut.atariCore.atari800xl.antic1.nmiReg.toBoolean
      val vcount   = dut.atariCore.atari800xl.antic1.vcountReg.toInt

      if (vcount > maxVcount) maxVcount = vcount
      if (nmienRaw != lastNmienRaw) lastNmienRaw = nmienRaw
      if (vbiNmi) vbiNmiEvents += 1
      if (cycleCount > 200 && nmiOut != lastNmiOut) nmiEdgeCount += 1
      lastNmiOut = nmiOut

      // Detect VGA sync edges
      val hsync = dut.io.vga_hsync.toBoolean
      val vsync = dut.io.vga_vsync.toBoolean
      if (hsync && !lastHsync) hsyncCount += 1
      if (vsync && !lastVsync) vsyncCount += 1
      lastHsync = hsync
      lastVsync = vsync

      // Raw Atari frame capture (15kHz, sampled on colour clock rising edge)
      val rawHsync = dut.io.videoHs.toBoolean
      val rawVsync = dut.io.videoVs.toBoolean
      val rawBlank = dut.io.videoBlank.toBoolean
      val colClk   = dut.atariCore.atari800xl.antic1.colourClock1x.toBoolean
      // Vsync rising edge = new frame
      if (rawVsync && !fbLastRawVsync) {
        if (fbCaptureFrame && fbPixelY > 0 && fbFramesCaptured < 3) {
          fbMaxPixelY = fbPixelY
          writePpm(fbFramesCaptured)
          fbFramesCaptured += 1
        }
        fbPixelY = 0; fbPixelX = 0; fbMaxPixelX = 0
        if (vsyncCount >= 7) fbCaptureFrame = true
      }
      // Hsync rising edge = new scanline
      if (rawHsync && !fbLastRawHsync && fbCaptureFrame) {
        if (fbPixelX > fbMaxPixelX) fbMaxPixelX = fbPixelX
        fbPixelX = 0
        fbPixelY += 1
        if (fbPixelY >= maxFbHeight) fbPixelY = maxFbHeight - 1
      }
      // Sample VIDEO_B on colour clock rising edge, apply palette
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

      // Video monitoring
      val vb = dut.io.dbgVideoB.toInt
      if (vb > maxVideoB) maxVideoB = vb
      if (vb > 0) nzVideoB += 1

      // Global non-zero cache check (runs from start, not just after 5M)
      {
        val cacheReadyG = dut.atariCore.atari800xl.antic1.dmaCacheReadyReg.toBoolean
        val cycleLG = dut.atariCore.atari800xl.antic1.cycleLatter.toBoolean
        if (cacheReadyG && cycleLG) {
          val destG = dut.atariCore.atari800xl.antic1.dmaFetchDestinationReg.toInt
          val cacheG = dut.atariCore.atari800xl.antic1.dmaCacheReg.toInt
          val addrG = dut.atariCore.atari800xl.antic1.dmaAddressReg.toInt
          val flG = dut.atariCore.atari800xl.antic1.firstLineOfInstructionReg.toBoolean
          if (destG == 0 && cacheG != 0) { // LB write with non-zero data
            lbNzCount += 1
            if (lbNzCount <= 40) {
              println(f"  LB_NZ#$lbNzCount%d: data=$$${cacheG}%02X addr=$$${addrG}%04X firstLine=$flG%b cyc=$cycleCount%d")
            }
          }
          if (destG == 1 && cacheG != 0) { // SR load with non-zero data
            srNzCount += 1
            if (srNzCount <= 40) {
              println(f"  SR_NZ#$srNzCount%d: data=$$${cacheG}%02X addr=$$${addrG}%04X firstLine=$flG%b cyc=$cycleCount%d")
            }
          }
        }
      }

      if (cycleCount > 5000000) {
        distinctVidB += vb
        val anticFetch = dut.atariCore.atari800xl.ANTIC_FETCH.toBoolean
        val destReg = dut.atariCore.atari800xl.antic1.dmaFetchDestinationReg.toInt
        val cacheReady = dut.atariCore.atari800xl.antic1.dmaCacheReadyReg.toBoolean
        val cacheData = dut.atariCore.atari800xl.antic1.dmaCacheReg.toInt
        val firstLine = dut.atariCore.atari800xl.antic1.firstLineOfInstructionReg.toBoolean
        val destNames = Seq("LB","SR","NL","IN","LL","LH","?6","?7")
        if (anticFetch) {
          val anticAddr = dut.atariCore.atari800xl.ANTIC_ADDR.toInt
          anticDmaCount += 1
          if (anticAddr >= 0xE000) anticDmaRomCount += 1
          else if (anticAddr < 0x4000) anticDmaRamCount += 1
        }
        // Track firstLine transitions
        if (firstLine != lastFirstLine) {
          firstLineTransitions += 1
          if (firstLineTransitions <= 30) {
            println(f"  FIRST_LINE ${if (firstLine) "ON" else "OFF"} cyc=$cycleCount%d dest=${destNames(destReg)}%s")
          }
          lastFirstLine = firstLine
        }
        // Track DMA fetch starts — rising edge of dmaFetchReg
        val dmaFetch = dut.atariCore.atari800xl.antic1.dmaFetchReg.toBoolean
        val dmaAddr = dut.atariCore.atari800xl.antic1.dmaAddressReg.toInt
        val memReadyAntic = dut.atariCore.atari800xl.mmu1.notifyAntic.toBoolean
        if (memReadyAntic) memReadyAnticCount += 1
        if (dmaFetch && !lastDmaFetch && destReg == 0) dmaFetchLbCount += 1
        lastDmaFetch = dmaFetch
        val hcount = dut.atariCore.atari800xl.antic1.hcountReg.toInt
        val cycleLat = dut.atariCore.atari800xl.antic1.cycleLatter.toBoolean
        // Log first 60 cycles of firstLine=True (instruction fetch + cache timing)
        if (firstLine && firstLineTransitions == 1 && flTraceCount < 60) {
          flTraceCount += 1
          val allowDma = dut.atariCore.atari800xl.antic1.allowRealDmaReg.toBoolean
          println(f"  FL#$flTraceCount%d cyc=$cycleCount%d: fetch=$dmaFetch%b ready=$memReadyAntic%b cacheRdy=$cacheReady%b cycleLat=$cycleLat%b hc=$hcount%03X dest=${destNames(destReg)}%s addr=$$${dmaAddr}%04X cacheD=$$${cacheData}%02X allowDma=$allowDma%b")
        }
        // Targeted trace: log events during charName DMA (dest=LB, firstLine=True)
        if (firstLine && firstLineTransitions == 1 && destReg == 0 && (dmaFetch || cacheReady) && flTraceCount >= 60) {
          flTraceCount += 1
          if (flTraceCount < 260) {
            val allowDma = dut.atariCore.atari800xl.antic1.allowRealDmaReg.toBoolean
            println(f"  LB_DMA#${flTraceCount-60}%d cyc=$cycleCount%d: fetch=$dmaFetch%b ready=$memReadyAntic%b cacheRdy=$cacheReady%b cycleLat=$cycleLat%b hc=$hcount%03X addr=$$${dmaAddr}%04X cacheD=$$${cacheData}%02X allowDma=$allowDma%b")
          }
        }
        // Track cache-ready events — only count as actual write when cycleLatter is also True
        if (cacheReady) {
          cacheReadyCount += 1
          if (destReg == 0 && cycleLat) { // DMA_FETCH_LINE_BUFFER, actual write cycle
            lbWriteCount += 1
            // Log first 50 LB writes (after 5M)
            if (lbWriteCount <= 50) {
              println(f"  LB_WRITE#$lbWriteCount%d: data=$$${cacheData}%02X addr=$$${dmaAddr}%04X firstLine=$firstLine%b cyc=$cycleCount%d hc=$hcount%03X")
            }
          }
          // Log SHIFTREG DMA (charData) — first 60 (after 5M)
          if (destReg == 1 && cycleLat) { // DMA_FETCH_SHIFTREG, actual load cycle
            srLoadCount += 1
            if (srLoadCount <= 60) {
              println(f"  SR_LOAD#$srLoadCount%d: data=$$${cacheData}%02X addr=$$${dmaAddr}%04X firstLine=$firstLine%b cyc=$cycleCount%d hc=$hcount%03X")
            }
          }
          if (destReg == 3) { // DMA_FETCH_INSTRUCTION
            instrFetchCount += 1
            if (instrFetchCount <= 10) {
              println(f"  INSTR_FETCH#$instrFetchCount%d: data=$$${cacheData}%02X addr=$$${dmaAddr}%04X firstLine=$firstLine%b cyc=$cycleCount%d")
            }
          }
        }
        val an = dut.atariCore.atari800xl.ANTIC_AN.toInt
        distinctAN += an
        val pfActive = dut.atariCore.atari800xl.antic1.playfieldDisplayActiveReg.toBoolean
        val displaySR = dut.atariCore.atari800xl.antic1.displayShiftReg.toInt
        val delayDSR = dut.atariCore.atari800xl.antic1.delayDisplayShiftReg.toLong
        val colClkSel = dut.atariCore.atari800xl.antic1.colourClock1x.toBoolean
        // Count non-zero shift register and foreground AN during display active
        if (pfActive && colClkSel) {
          if (displaySR != 0) pfSrNzCount += 1
          if ((delayDSR & 3) != 0) pfDsrNzCount += 1
          if (an != 0 && an != 4) pfAnFgCount += 1
        }
        // AN pipeline trace on colourClockSelected only, 300 entries, during text lines
        if (pfActive && colClkSel && lbNzCount >= 23 && lbNzCount <= 80 && anPipeCount < 300) {
          anPipeCount += 1
          val dsrLo5 = (delayDSR & 0x1F).toInt
          val sPf0 = dut.atariCore.atari800xl.gtia1.setPf0.toBoolean
          val sPf1 = dut.atariCore.atari800xl.gtia1.setPf1.toBoolean
          val sPf2 = dut.atariCore.atari800xl.gtia1.setPf2.toBoolean
          val sPf3 = dut.atariCore.atari800xl.gtia1.setPf3.toBoolean
          val sBk  = dut.atariCore.atari800xl.gtia1.setBk.toBoolean
          val aPf0 = dut.atariCore.atari800xl.gtia1.activePf0Live.toBoolean
          val aPf1 = dut.atariCore.atari800xl.gtia1.activePf1Live.toBoolean
          val aPf2 = dut.atariCore.atari800xl.gtia1.activePf2Live.toBoolean
          val aPf3 = dut.atariCore.atari800xl.gtia1.activePf3Live.toBoolean
          val aBk  = dut.atariCore.atari800xl.gtia1.activeBkLive.toBoolean
          val vis  = dut.atariCore.atari800xl.gtia1.visibleLive.toBoolean
          val priFlags = s"${if(sPf0) "0" else "."}${if(sPf1) "1" else "."}${if(sPf2) "2" else "."}${if(sPf3) "3" else "."}${if(sBk) "B" else "."}"
          val actFlags = s"${if(aPf0) "0" else "."}${if(aPf1) "1" else "."}${if(aPf2) "2" else "."}${if(aPf3) "3" else "."}${if(aBk) "B" else "."}"
          println(f"  AN_PIPE#$anPipeCount%d cyc=$cycleCount%d: AN=$an%d vb=$$${vb}%02X sr=$$${displaySR}%02X dsr=$dsrLo5%02X hc=$hcount%03X set=$priFlags act=$actFlags vis=$vis%b")
        }
        // Count VIDEO_B by color register value
        vb match {
          case 0x00 => vbColbkCount += 1
          case 0x28 => vbColpf0Count += 1
          case 0x94 => vbColpf2Count += 1
          case 0xCA => vbColpf1Count += 1
          case 0x46 => vbColpf3Count += 1
          case _ =>
        }
      }

      // DMACTL/COLBK change tracking
      val dmactl = dut.io.dbgDmactl.toInt
      val colbk  = dut.io.dbgColbk.toInt
      if (dut.io.dbgGtiaWrEn.toBoolean) gtiaWrites += 1
      if (dut.io.dbgAnticWrEn.toBoolean) anticWrites += 1
      if (dmactl != lastDmactl) lastDmactl = dmactl
      if (colbk != lastColbk) lastColbk = colbk

      // Periodic status
      if (cycleCount % reportInterval == 0) {
        val timeUs = cycleCount.toDouble * clockPeriod / 1e6
        println(f"  Cycle $cycleCount%,d (${timeUs}%.0f us): " +
          f"hsync=$hsyncCount%d vsync=$vsyncCount%d " +
          f"SDRAM rd=$sdramReads%d wr=$sdramWrites%d " +
          f"cpuEN=$cpuEnCount%d DMACTL=$dmactl%d COLBK=$colbk%d " +
          f"gtiaW=$gtiaWrites%d anticW=$anticWrites%d " +
          f"VidB_max=$maxVideoB%d(nz=$nzVideoB%d) " +
          f"nmien=$nmienRaw%d nmiEdges=$nmiEdgeCount%d maxVcount=$maxVcount%d " +
          f"IRQs=$irqCount%d IRQST=$$${irqst}%02X IRQEN=$$${irqen}%02X")
      }
    }

    println("=" * 60)
    println(s"Simulation complete: $cycleCount cycles")
    println(s"  VGA: $hsyncCount hsync, $vsyncCount vsync")
    println(s"  SDRAM: $sdramReads reads, $sdramWrites writes")
    println(s"  NMI: $nmiEdgeCount edges, $vbiNmiEvents VBI events")
    println(s"  IRQ: $irqCount events, $irqHandlerCount handler entries")
    println(s"  POKEY: IRQST=0x${"%02X".format(lastIrqst)}, IRQEN=0x${"%02X".format(lastIrqen)}")
    println(s"  Non-zero DMA data: LB=$lbNzCount SR=$srNzCount (global, entire sim)")
    println(f"  Display active: pfSrNz=$pfSrNzCount%d pfDsrNz=$pfDsrNzCount%d pfAnFg=$pfAnFgCount%d (on colClkSel)")
    println(f"  VIDEO_B colors: COLBK($$00)=$vbColbkCount%d COLPF0($$28)=$vbColpf0Count%d COLPF1($$CA)=$vbColpf1Count%d COLPF2($$94)=$vbColpf2Count%d COLPF3($$46)=$vbColpf3Count%d")

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

    println(s"  Distinct VIDEO_B values (after 5M): ${distinctVidB.toSeq.sorted.map(v => f"$$${v}%02X").mkString(", ")}")
    println(s"  ANTIC DMA (after 5M): total=$anticDmaCount ROM(E000+)=$anticDmaRomCount RAM(0-3FFF)=$anticDmaRamCount")
    println(s"  DMA detail: lbWrites=$lbWriteCount dmaFetchLB=$dmaFetchLbCount instrFetch=$instrFetchCount cacheReady=$cacheReadyCount firstLineTransitions=$firstLineTransitions")
    println(s"  Distinct AN values (after 5M): ${distinctAN.toSeq.sorted.map(v => f"$v%d").mkString(", ")}")

    // Dump internal RAM: display list and screen memory (only if internal RAM exists)
    if (!dut.atariCore.internalromram1.ramInt.isDefined) {
      println("\n--- Internal RAM dump skipped (internal_ram=0, all RAM via SDRAM) ---")
    } else {
    println("\n--- Internal RAM dump (display list & screen) ---")
    val ram = dut.atariCore.internalromram1.ramInt.get
    // Display list at $3C20 (32 bytes)
    print("  DLIST $3C20: ")
    for (i <- 0 until 32) {
      print(f"${ram.ramBlock.getBigInt(0x3C20 + i).toInt}%02X ")
    }
    println()
    // Find LMS address from display list (byte 3,4 after blank lines)
    val dlistBytes = (0 until 32).map(i => ram.ramBlock.getBigInt(0x3C20 + i).toInt)
    // Look for first mode 2 instruction (0x42 = LMS mode 2, or 0x02 = mode 2)
    val lmsIdx = dlistBytes.indexWhere(b => (b & 0x4F) == 0x42) // LMS + mode 2
    if (lmsIdx >= 0 && lmsIdx + 2 < 32) {
      val screenAddr = dlistBytes(lmsIdx + 1) | (dlistBytes(lmsIdx + 2) << 8)
      println(f"  LMS found at DLIST+$lmsIdx%d: screen at $$${screenAddr}%04X")
      // Dump first 2 lines of screen memory (64 bytes)
      print(f"  Screen $$${screenAddr}%04X: ")
      for (i <- 0 until 64) {
        val addr = screenAddr + i
        if (addr < 16384) {
          print(f"${ram.ramBlock.getBigInt(addr).toInt}%02X ")
        } else {
          print("?? ")
        }
      }
      println()
      // Show as ATASCII text (approximate: internal code to ASCII for printable chars)
      print("  Text (internal): \"")
      for (i <- 0 until 40) {
        val addr = screenAddr + i
        if (addr < 16384) {
          val ch = ram.ramBlock.getBigInt(addr).toInt & 0x7F
          val ascii = if (ch >= 0x20 && ch < 0x60) (ch + 0x20).toChar
                      else if (ch >= 0 && ch < 0x20) (ch + 0x40).toChar
                      else '.'
          print(ascii)
        }
      }
      println("\"")
    } else {
      println("  No LMS instruction found in display list!")
    }
    // Zero-page: RAMTOP ($6A), SDMCTL ($022F), DLISTL/H ($0230/$0231)
    print("  ZP: RAMTOP=")
    print(f"$$${ram.ramBlock.getBigInt(0x6A).toInt}%02X")
    print(f" SDMCTL=$$${ram.ramBlock.getBigInt(0x022F).toInt}%02X")
    print(f" DLIST=$$${ram.ramBlock.getBigInt(0x0231).toInt}%02X${ram.ramBlock.getBigInt(0x0230).toInt}%02X")
    print(f" CRITIC=$$${ram.ramBlock.getBigInt(0x42).toInt}%02X")
    print(f" CHBAS=$$${ram.ramBlock.getBigInt(0x02F4).toInt}%02X")
    print(f" COLOR0=$$${ram.ramBlock.getBigInt(0x02C4).toInt}%02X")
    print(f" COLOR1=$$${ram.ramBlock.getBigInt(0x02C5).toInt}%02X")
    print(f" COLOR2=$$${ram.ramBlock.getBigInt(0x02C6).toInt}%02X")
    print(f" COLOR4=$$${ram.ramBlock.getBigInt(0x02C8).toInt}%02X")
    println()
    // Check character generator data - 'A' (internal $21) pattern at CHBAS*256 + $21*8
    val chbas = ram.ramBlock.getBigInt(0x02F4).toInt
    val charAddr = chbas * 256 + 0x21 * 8  // 'A' pattern address
    print(f"  CharGen 'A' at $$${charAddr}%04X: ")
    for (i <- 0 until 8) {
      if (charAddr + i < 16384) {
        print(f"${ram.ramBlock.getBigInt(charAddr + i).toInt}%02X ")
      } else {
        print("ROM ")
      }
    }
    println()
    } // end if ramInt.isDefined
  }
}
