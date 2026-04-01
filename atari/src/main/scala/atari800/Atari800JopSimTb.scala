package atari800

import spinal.core._
import spinal.core.sim._
import java.io.{FileOutputStream, BufferedOutputStream}
import jop.utils.JopFileLoader

// Combined Atari 800 + JOP simulation testbench.
//
// Tests the full integrated design: Atari core + JOP soft-core sharing SDRAM
// through the priority arbiter.  JOP uses simulation boot (pre-loaded SDRAM,
// no UART download wait).  AtariApp.jop is loaded at SDRAM byte offset 0x000000.
// Memory map is defined in Atari800JopSim.boardConfig.
//
// Captures VGA frames as PPM images in sim_workspace/.
//
// Verifies:
//   - JOP boots and executes AtariApp (UART output decoded to console)
//   - Atari VGA output (hsync/vsync counting + visual frame capture)
//   - SDRAM arbiter doesn't deadlock
//   - Both cores coexist without bus conflicts
object Atari800JopSimTb extends App {

  val compiled = SimConfig
    .withConfig(SpinalConfig(
      defaultClockDomainFrequency = FixedFrequency(56.67 MHz),
      defaultConfigForClockDomains = ClockDomainConfig(
        resetKind        = ASYNC,
        resetActiveLevel = LOW   // Must match Atari core's RESET_N (active-low)
      )
    ))
    //.withWave                     // Disabled: VCD files are huge (21GB+)
    .workspacePath("sim_workspace")
    .addSimulatorFlag("-Wno-WIDTHEXPAND")
    .addSimulatorFlag("-Wno-WIDTHTRUNC")
    .addSimulatorFlag("--x-initial-edge")
    .addSimulatorFlag("--x-assign 0")
    .compile(new Atari800JopSim)

  compiled.doSim("Atari800Jop_combined_test", seed = 42) { dut =>
    val sdram = new SdramBehavioral(32 * 1024 * 1024)  // 32MB

    // ---------------------------------------------------------------
    // Pre-load AtariApp.jop into JOP's SDRAM region (byte offset 0x000000)
    // Memory map: JOP at 0x000000, Atari at boardConfig.atariSdramBase
    // ---------------------------------------------------------------
    val boardConfig = Atari800JopSim.boardConfig
    val jopFilePath = "java/apps/AtariSupervisor/AtariSupervisor.jop"
    val SDRAM_JOP_OFFSET = 0x000000  // JOP always at physical byte 0 (jvm.asm sim boot)
    try {
      val jopData = JopFileLoader.loadJopFile(jopFilePath)
      println(s"Loaded $jopFilePath: ${jopData.words.length} words (heap=${jopData.words(0)}, mp=${jopData.words(1)})")
      for ((word, i) <- jopData.words.zipWithIndex) {
        val byteAddr = SDRAM_JOP_OFFSET + i * 4
        sdram.write(byteAddr, word.toLong, write8 = false, write16 = false, write32 = true)
      }
      println(s"  JOP SDRAM region: bytes 0x${SDRAM_JOP_OFFSET.toHexString}..0x${(SDRAM_JOP_OFFSET + jopData.words.length * 4).toHexString}")
      println(s"  Atari SDRAM region: bytes 0x${boardConfig.atariSdramBase.toHexString}+")
    } catch {
      case e: Exception =>
        println(s"WARNING: Could not load $jopFilePath: ${e.getMessage}")
        println("  JOP will boot with empty SDRAM (heap=0, mp=0 — likely crash)")
    }

    val clockPeriodPs = 17640  // ~56.67 MHz
    val baudCycles    = (56670000.0 / 115200).toInt  // ~492 cycles per UART bit

    // Fork main system clock
    dut.clockDomain.forkStimulus(period = clockPeriodPs)

    // Fork VGA text pixel clock (25 MHz = 40ns period = 40000 ps)
    fork {
      while (true) {
        dut.io.vgaTextClk #= false
        sleep(20000)  // 20ns half-period
        dut.io.vgaTextClk #= true
        sleep(20000)
      }
    }

    // ---------------------------------------------------------------
    // JOP UART decoder fork — decode 8N1 at 115200 baud, print to console
    // ---------------------------------------------------------------
    fork {
      val uartBuf = new StringBuilder
      while (true) {
        // Wait for start bit (falling edge on TX — idle=high)
        waitUntil(!dut.io.jopUartTx.toBoolean)
        // Half-bit delay to centre-sample start bit
        dut.clockDomain.waitRisingEdge(baudCycles / 2)
        if (!dut.io.jopUartTx.toBoolean) {
          // Valid start bit — sample 8 data bits
          var byte = 0
          for (bit <- 0 until 8) {
            dut.clockDomain.waitRisingEdge(baudCycles)
            if (dut.io.jopUartTx.toBoolean) byte |= (1 << bit)
          }
          // Skip stop bit
          dut.clockDomain.waitRisingEdge(baudCycles)
          val ch = (byte & 0xFF).toChar
          if (ch == '\n') {
            print(s"JOP: ${uartBuf.toString}\n")
            uartBuf.clear()
          } else if (ch >= ' ' && ch <= '~') {
            uartBuf.append(ch)
          }
        }
      }
    }

    // Initialize inputs
    dut.io.reset_btn  #= false
    dut.io.option_btn #= false
    dut.io.select_btn #= false
    dut.io.start_btn  #= false
    dut.io.joy1       #= 0x1F  // all released (active low)
    dut.io.sdramComplete #= false  // idle = complete LOW (matches SdramStatemachine ~REQUEST term)
    dut.io.sdramDo      #= 0

    // Hold reset
    dut.clockDomain.waitRisingEdge(10)
    dut.clockDomain.assertReset()
    dut.clockDomain.waitRisingEdge(100)
    dut.clockDomain.deassertReset()

    // Tracking
    var hsyncCount    = 0
    var vsyncCount    = 0
    var lastHsync     = false
    var lastVsync     = false
    var sdramReads    = 0
    var sdramWrites   = 0
    var sdramRequests = 0
    var cycleCount    = 0
    var jopUartBits   = 0
    var jopBmbCmdValid = 0  // count cycles where JOP bmb cmd.valid
    var jopSdramReqs   = 0  // count JOP bridge request pulses
    var jopBmbRspValid = 0  // count cycles where JOP bmb rsp.valid
    var jopBcFillSeen  = false
    var jopBcFillFirstAddr = 0; var jopBcFillFirstLen = 0; var jopBcFillFirstRaw = 0L

    var lastUartTx    = true

    // Debug: Atari raw request tracking (before arbiter)
    var atariRawReqs    = 0
    var atariRawReads   = 0
    var atariRawWrites  = 0

    // Debug: video signal monitoring
    var maxVideoB       = 0
    var nonZeroVideoB   = 0
    var maxScanR        = 0
    var maxScanG        = 0
    var maxScanB        = 0
    var nonZeroScanRGB  = 0

    // Debug: GTIA/ANTIC internals
    var maxColbk        = 0
    var colourClockCount = 0
    var visibleLiveCount = 0
    var anCounts         = Array.fill(8)(0)  // count per AN value
    var gtiaWrites      = 0
    var anticWrites     = 0
    var maxDmactl       = 0
    var videoBvalues    = scala.collection.mutable.Set[Int]()  // unique VIDEO_B values seen

    // CPU tracking
    var cpuEnCount   = 0
    val pcPageSeen   = Array.fill(16)(false)  // track 4K pages
    var lastPc       = -1
    var pcRepeatCount = 0
    var pcLoopDetected = ""

    // SDRAM behavioral model state
    var lastSdramRequest = false

    // Boot diagnostic tracking
    var lastPortbOut   = -1
    var lastPortbDdr   = -1
    var lastTrigReg    = -1
    var lastCartTrig3  = -1
    var lastPortbOpts  = -1

    // ---------------------------------------------------------------
    // VGA frame capture — records pixels into a framebuffer, writes PPM
    // ---------------------------------------------------------------
    val maxWidth  = 2048
    val maxHeight = 1024
    val frameR = Array.ofDim[Int](maxHeight, maxWidth)
    val frameG = Array.ofDim[Int](maxHeight, maxWidth)
    val frameB = Array.ofDim[Int](maxHeight, maxWidth)
    var pixelX       = 0
    var pixelY       = 0
    var inActiveH    = false   // between hsync pulses (active line)
    var framesCaptured = 0
    var captureFrame   = false  // start capturing after first full vsync
    var maxPixelX    = 0
    var maxPixelY    = 0

    def writePpm(frameNum: Int): Unit = {
      val width  = if (maxPixelX > 0) maxPixelX else 1
      val height = if (maxPixelY > 0) maxPixelY else 1
      val path   = s"sim_workspace/Atari800JopSim/frame_$frameNum.ppm"
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

    val totalCycles    = 20000000   // ~353 ms — enough for JOP boot + several heartbeats
    val reportInterval = 2000000

    println(s"Starting combined Atari+JOP simulation: $totalCycles cycles (~${totalCycles * clockPeriodPs / 1000000000L} ms)")
    println("=" * 70)

    for (_ <- 0 until totalCycles) {
      dut.clockDomain.waitRisingEdge()
      cycleCount += 1

      // ---------------------------------------------------------------
      // Track Atari raw SDRAM requests (before arbiter)
      // ---------------------------------------------------------------
      if (dut.io.dbgAtariSdramReq.toBoolean) {
        atariRawReqs += 1
        if (dut.io.dbgAtariSdramReadEn.toBoolean) atariRawReads += 1
        if (dut.io.dbgAtariSdramWriteEn.toBoolean) atariRawWrites += 1
      }

      // ---------------------------------------------------------------
      // CPU PC tracking
      // ---------------------------------------------------------------
      if (dut.atariCore.atari800xl.cpu6502.CPU_ENABLE.toBoolean) {
        cpuEnCount += 1
        val pc = dut.atariCore.atari800xl.cpu6502.debugPc.toInt
        // Log first entry into each 4K page
        val pcPage = (pc >> 12) & 0xF
        if (!pcPageSeen(pcPage)) {
          pcPageSeen(pcPage) = true
          println(f"  ** First PC in page 0x${pcPage}%X000 at cpuEN=$cpuEnCount%d cyc=$cycleCount%d PC=0x${pc}%04X")
        }
        // Trace first 20 CPU enables
        if (cpuEnCount <= 20) {
          val a = dut.atariCore.atari800xl.cpu6502.debugA.toInt
          println(f"  CPU#$cpuEnCount%d cyc=$cycleCount%d PC=0x${pc}%04X A=0x${a}%02X")
        }
        // Track PC loop (same PC repeating)
        if (pc == lastPc) {
          pcRepeatCount += 1
          if (pcRepeatCount == 100 && pcLoopDetected.isEmpty) {
            pcLoopDetected = f"0x${pc}%04X at cpuEN=$cpuEnCount%d cyc=$cycleCount%d"
            println(f"  ** CPU LOOP DETECTED: PC=0x${pc}%04X repeating at cpuEN=$cpuEnCount%d cyc=$cycleCount%d")
          }
        } else {
          pcRepeatCount = 0
        }
        lastPc = pc
        // Log PC at intervals
        if (cpuEnCount % 100000 == 0) {
          val a = dut.atariCore.atari800xl.cpu6502.debugA.toInt
          println(f"  CPU#$cpuEnCount%d cyc=$cycleCount%d PC=0x${pc}%04X A=0x${a}%02X")
        }
        // Boot flow tracing
        val cpu = dut.atariCore.atari800xl.cpu6502
        pc match {
          case 0xC2D6 =>
            // STA $01 — sets boot flag to 1
            val a = cpu.debugA.toInt
            println(f"  ** BOOT: STA $$01 (A=0x${a}%02X) at cpuEN=$cpuEnCount%d cyc=$cycleCount%d")
          case 0xC2EC =>
            // LSR $01 — RAM test fail (writes $FF)
            val a = cpu.debugA.toInt
            println(f"  ** RAM TEST FAIL (FF): LSR $$01 at cpuEN=$cpuEnCount%d cyc=$cycleCount%d A=0x${a}%02X")
          case 0xC2F6 =>
            // LSR $01 — RAM test fail (writes $00)
            val a = cpu.debugA.toInt
            println(f"  ** RAM TEST FAIL (00): LSR $$01 at cpuEN=$cpuEnCount%d cyc=$cycleCount%d A=0x${a}%02X")
          case 0xC31D =>
            // LSR $01 — checksum fail
            println(f"  ** CKSUM LSR $$01 at cpuEN=$cpuEnCount%d cyc=$cycleCount%d")
          case 0xC3AB =>
            // LDA $01 — decision point
            val a = cpu.debugA.toInt
            println(f"  ** BOOT DECISION: LDA $$01 (A=0x${a}%02X) at cpuEN=$cpuEnCount%d cyc=$cycleCount%d")
          case 0xC3AD =>
            // BNE $C3C4 — branch on $01
            val a = cpu.debugA.toInt
            val flags = cpu.debugFlags.toInt
            val z = if ((flags & 0x02) != 0) "Z=1(selftest)" else "Z=0(boot)"
            println(f"  ** BOOT BNE: A=0x${a}%02X flags=0x${flags}%02X $z%s at cpuEN=$cpuEnCount%d")
          case 0xC3C4 =>
            // Normal boot path (cartridge/BASIC check)
            println(f"  ** BOOT: Normal path at C3C4 cpuEN=$cpuEnCount%d cyc=$cycleCount%d")
          case 0xC3C1 =>
            // JMP $5003 — self-test entry
            println(f"  ** BOOT: JMP $$5003 (self-test) at cpuEN=$cpuEnCount%d cyc=$cycleCount%d")
          case 0xFF73 =>
            println(f"  ** CHECKSUM ENTRY at cpuEN=$cpuEnCount%d cyc=$cycleCount%d")
          case 0xC316 =>
            // BCS $C31D — branch after checksum
            val flags = cpu.debugFlags.toInt
            val c = if ((flags & 0x01) != 0) "C=1(fail)" else "C=0(pass)"
            println(f"  ** BOOT BCS: at C316 flags=0x${flags}%02X $c%s")
          case 0x5003 =>
            println(f"  ** SELF-TEST ENTRY at PC=0x5003 cpuEN=$cpuEnCount%d cyc=$cycleCount%d")
          // RAM sizing routine traces ($C471 hw init)
          case 0xC471 =>
            println(f"  ** HW INIT ENTRY at C471 cpuEN=$cpuEnCount%d cyc=$cycleCount%d")
          case 0xC475 =>
            // BCC $C484 — cart check
            val flags = cpu.debugFlags.toInt
            val c = if ((flags & 0x01) != 0) "C=1(cart)" else "C=0(nocart)"
            println(f"  ** CART CHECK BCC: flags=0x${flags}%02X $c%s cpuEN=$cpuEnCount%d")
          case 0xC481 =>
            println(f"  ** DANGER: JMP ($$BFFE) cart boot! cpuEN=$cpuEnCount%d cyc=$cycleCount%d")
          case 0xC484 =>
            println(f"  ** HW INIT: JSR C4DA (zero regs) cpuEN=$cpuEnCount%d cyc=$cycleCount%d")
          case 0xC4A9 =>
            println(f"  ** RAM SIZING START at C4A9 cpuEN=$cpuEnCount%d cyc=$cycleCount%d")
          case 0xC4B0 =>
            val a = cpu.debugA.toInt
            println(f"  ** RAM SIZING: STA $$06 (A=0x${a}%02X) initial RAMTOP cpuEN=$cpuEnCount%d")
          case 0xC4BA =>
            // BNE $C4C8 — sizing test fail
            val flags = cpu.debugFlags.toInt
            val a = cpu.debugA.toInt
            val z = if ((flags & 0x02) != 0) "Z=1(pass)" else "Z=0(FAIL)"
            if ((flags & 0x02) == 0)
              println(f"  ** RAM SIZING FAIL (EOR test): A=0x${a}%02X $z%s cpuEN=$cpuEnCount%d cyc=$cycleCount%d")
          case 0xC4C2 =>
            // BNE $C4C8 — sizing verify fail
            val flags = cpu.debugFlags.toInt
            val a = cpu.debugA.toInt
            val z = if ((flags & 0x02) != 0) "Z=1(pass)" else "Z=0(FAIL)"
            if ((flags & 0x02) == 0)
              println(f"  ** RAM SIZING FAIL (restore test): A=0x${a}%02X $z%s cpuEN=$cpuEnCount%d cyc=$cycleCount%d")
          case 0xC4C4 =>
            // INC $06 — RAMTOP++
            // (logged frequently, only log first and last)
          case 0xC4C8 =>
            println(f"  ** RAM SIZING DONE (RTS) cpuEN=$cpuEnCount%d cyc=$cycleCount%d")
          case 0xC2FF =>
            // CPX $06 — RAM test loop check (log first occurrence and when it exits)
            val x = cpu.debugX.toInt
            val flags = cpu.debugFlags.toInt
            if (x == 0x40 || x <= 2 || (flags & 0x02) != 0)
              println(f"  ** RAM TEST CPX $$06: X=0x${x}%02X flags=0x${flags}%02X cpuEN=$cpuEnCount%d cyc=$cycleCount%d")
          case _ =>
        }
      }

      // ---------------------------------------------------------------
      // RAM diagnostic: trace internal RAM signals during first test
      // (RAM test starts around cpuEN=210200, cyc=456600)
      // ---------------------------------------------------------------
      // Watch for writes to RAM address 0x0001 (boot flag) and 0x0006 (RAMTOP)
      // Only when internal RAM exists (internal_ram > 0)
      if (dut.atariCore.internalromram1.ramInt.isDefined) {
        if (cycleCount >= 100 && cycleCount <= 5000000) {
          val ramGri = dut.atariCore.internalromram1.ramInt.get
          val we = ramGri.weRam.toBoolean
          val addr = ramGri.memAddr.toInt
          if (we && addr == 1) {
            val din = ramGri.io.data.toInt & 0xFF
            val pc = dut.atariCore.atari800xl.cpu6502.debugPc.toInt
            val a = dut.atariCore.atari800xl.cpu6502.debugA.toInt
            val y = dut.atariCore.atari800xl.cpu6502.debugY.toInt
            println(f"  ** ZP01 WRITE: din=0x${din}%02X PC=0x${pc}%04X A=0x${a}%02X Y=0x${y}%02X cyc=$cycleCount%d cpuEN=$cpuEnCount%d")
          }
          if (we && addr == 6) {
            val din = ramGri.io.data.toInt & 0xFF
            val pc = dut.atariCore.atari800xl.cpu6502.debugPc.toInt
            val a = dut.atariCore.atari800xl.cpu6502.debugA.toInt
            val x = dut.atariCore.atari800xl.cpu6502.debugX.toInt
            val y = dut.atariCore.atari800xl.cpu6502.debugY.toInt
            println(f"  ** ZP06 WRITE (RAMTOP): din=0x${din}%02X PC=0x${pc}%04X A=0x${a}%02X X=0x${x}%02X Y=0x${y}%02X cyc=$cycleCount%d cpuEN=$cpuEnCount%d")
          }
          // Also watch $05 (pointer hi, shared with RAMTOP mechanism)
          if (we && addr == 5) {
            val din = ramGri.io.data.toInt & 0xFF
            val pc = dut.atariCore.atari800xl.cpu6502.debugPc.toInt
            if (cycleCount >= 300000 && cycleCount <= 500000)
              println(f"  ** ZP05 WRITE: din=0x${din}%02X PC=0x${pc}%04X cyc=$cycleCount%d cpuEN=$cpuEnCount%d")
          }
        }
        // Trace internal RAM during first RAM test iteration (STA $01 at cpuEN=210382)
        if (cpuEnCount >= 210370 && cpuEnCount <= 210420) {
          val ramGri = dut.atariCore.internalromram1.ramInt.get
          val we = ramGri.weRam.toBoolean
          val addr = ramGri.memAddr.toInt
          val din = ramGri.io.data.toInt & 0xFF
          val qr = ramGri.qRam.toInt & 0xFF
          val cpuEn = dut.atariCore.atari800xl.cpu6502.CPU_ENABLE.toBoolean
          val pc = dut.atariCore.atari800xl.cpu6502.debugPc.toInt
          val a = dut.atariCore.atari800xl.cpu6502.debugA.toInt
          val y = dut.atariCore.atari800xl.cpu6502.debugY.toInt
          println(f"  RAM_TRACE cpuEN=$cpuEnCount%d cyc=$cycleCount%d PC=$pc%04X A=$a%02X Y=$y%02X ramAddr=$addr%05X we=$we%b din=$din%02X qRam=$qr%02X cpuEn=$cpuEn%b")
        }
      }

      // Trace SDRAM access during RAM test at page $40 (first failure)
      // The first ZP01 write (failure) was at ~cycle 1777838, so trace from 1777700
      if (cycleCount >= 1777700 && cycleCount <= 1777900) {
        val sdReq = dut.io.sdramRequest.toBoolean
        val sdRd = dut.io.sdramReadEnable.toBoolean
        val sdWr = dut.io.sdramWriteEnable.toBoolean
        val sdAddr = dut.io.sdramAddr.toInt
        val sdDi = dut.io.sdramDi.toLong & 0xFFFFFFFFL
        val sdDo = dut.io.sdramDo.toLong & 0xFFFFFFFFL
        val sdComplete = dut.io.sdramComplete.toBoolean
        val sdByte = dut.io.sdramByteAccess.toBoolean
        val pc = dut.atariCore.atari800xl.cpu6502.debugPc.toInt
        val a = dut.atariCore.atari800xl.cpu6502.debugA.toInt
        val y = dut.atariCore.atari800xl.cpu6502.debugY.toInt
        val atariReq = dut.io.dbgAtariSdramReq.toBoolean
        val atariAddr = dut.io.dbgAtariSdramAddr.toInt
        val atariRd = dut.io.dbgAtariSdramReadEn.toBoolean
        val atariWr = dut.io.dbgAtariSdramWriteEn.toBoolean
        if (sdReq || atariReq) {
          val rw = if (sdWr) "WR" else if (sdRd) "RD" else "??"
          println(f"  SDRAM[$cycleCount%d] $rw atariAddr=0x${atariAddr}%06X sdAddr=0x${sdAddr}%07X di=0x${sdDi}%08X do=0x${sdDo}%08X byte=$sdByte cpl=$sdComplete PC=0x${pc}%04X A=0x${a}%02X Y=0x${y}%02X")
        }
      }

      // ---------------------------------------------------------------
      // Boot diagnostic: track PIA PORTB, TRIG3, cart_trig3
      // ---------------------------------------------------------------
      if (cycleCount <= 5000000) {
        val portbOut  = dut.atariCore.atari800xl.pia1.portbOutputReg.toInt
        val portbDdr  = dut.atariCore.atari800xl.pia1.portbDirectionReg.toInt
        val trigReg   = dut.atariCore.atari800xl.gtia1.trigReg.toInt
        val cartTrig3 = if (dut.atariCore.atari800xl.cart_trig3_out.toBoolean) 1 else 0
        val portbOpts = dut.atariCore.atari800xl.PORTB_OPTIONS.toInt

        if (portbOut != lastPortbOut) {
          println(f"  ** PORTB data reg changed: 0x${lastPortbOut}%02X -> 0x${portbOut}%02X at cyc=$cycleCount%d cpuEN=$cpuEnCount%d")
          lastPortbOut = portbOut
        }
        if (portbDdr != lastPortbDdr) {
          println(f"  ** PORTB DDR changed: 0x${lastPortbDdr}%02X -> 0x${portbDdr}%02X at cyc=$cycleCount%d cpuEN=$cpuEnCount%d")
          lastPortbDdr = portbDdr
        }
        if (trigReg != lastTrigReg) {
          println(f"  ** GTIA trigReg changed: 0x${lastTrigReg}%X -> 0x${trigReg}%X at cyc=$cycleCount%d cpuEN=$cpuEnCount%d")
          lastTrigReg = trigReg
        }
        if (cartTrig3 != lastCartTrig3) {
          println(f"  ** cart_trig3_out changed: $lastCartTrig3%d -> $cartTrig3%d at cyc=$cycleCount%d cpuEN=$cpuEnCount%d")
          lastCartTrig3 = cartTrig3
        }
        if (portbOpts != lastPortbOpts) {
          println(f"  ** PORTB_OPTIONS changed: 0x${lastPortbOpts}%02X -> 0x${portbOpts}%02X at cyc=$cycleCount%d cpuEN=$cpuEnCount%d")
          lastPortbOpts = portbOpts
        }
      }

      // ---------------------------------------------------------------
      // Behavioral SDRAM model
      // ---------------------------------------------------------------
      // Behavioral SDRAM model:
      // - Instant response: complete + data asserted same cycle as request
      // - Arbiter is bypassed: Atari SDRAM signals connected directly (same as standalone)
      // - Refresh acked via complete when no request (matches standalone behavior)
      // Behavioral SDRAM model:
      // - One-cycle latency: request on cycle K → complete + data on cycle K+1
      // - sdramComplete must be FALSE when no data request is active, even during
      //   refresh.  If complete is true when a new request arrives in STATE_IDLE,
      //   the AddressDecoder would complete immediately with stale data.
      // - Refresh needs no actual work (behavioral model); the Atari just needs
      //   complete to go high for one cycle after the refresh request.
      val request = dut.io.sdramRequest.toBoolean
      val refresh = dut.io.sdramRefresh.toBoolean
      if (request) {
        sdramRequests += 1
        val addr = dut.io.sdramAddr.toInt
        val readEn  = dut.io.sdramReadEnable.toBoolean
        val writeEn = dut.io.sdramWriteEnable.toBoolean
        if (readEn) {
          val data = sdram.read32(addr)
          dut.io.sdramDo #= data
          sdramReads += 1
          // Log JOP SDRAM reads (JOP data is at 0x000000..0x100000, Atari at 0x800000+)
          // Log JOP SDRAM reads: Atari at 0x800000+, JOP at lower addresses
          // Also log the first 15 reads total to trace boot sequence
          if (sdramReads <= 15 || addr < 0x800000) {
            val jpc = dut.io.dbgJopPc.toInt
            val src = if (addr >= 0x800000) "ATARI" else "JOP"
            println(f"  $src SDRAM RD[$cycleCount%d]: addr=0x${addr}%06X wordAddr=${addr/4} data=0x${data}%08X jpc=0x${jpc}%03X")
          }
        }
        if (writeEn) {
          val data = dut.io.sdramDi.toLong
          sdram.write(addr, data,
            dut.io.sdramByteAccess.toBoolean,
            dut.io.sdramWordAccess.toBoolean,
            dut.io.sdramLongwordAccess.toBoolean)
          sdramWrites += 1
        }
        dut.io.sdramComplete #= true   // data/write complete
      } else if (refresh && !lastSdramRequest) {
        dut.io.sdramComplete #= true   // ack refresh, but only if no request was active last cycle
      } else {
        dut.io.sdramComplete #= false  // idle: must be false to avoid stale-data race
      }
      lastSdramRequest = request

      // ---------------------------------------------------------------
      // Raw Atari video frame capture (pre-scandoubler, 15kHz)
      // Uses VIDEO_B colour index and VIDEO_BLANK for active area.
      // Captures on colour clock enable for correct pixel rate.
      // ---------------------------------------------------------------
      val hsync = dut.io.atariVideoHs.toBoolean
      val vsync = dut.io.atariVideoVs.toBoolean
      val blank = dut.io.dbgVideoBlank.toBoolean
      val colourClock = dut.io.dbgColourClock.toBoolean

      // Vsync rising edge = new frame
      if (vsync && !lastVsync) {
        vsyncCount += 1
        if (captureFrame && pixelY > 0 && vsyncCount >= 3 && framesCaptured < 3) {
          maxPixelY = pixelY
          writePpm(framesCaptured)
          framesCaptured += 1
        }
        pixelY = 0
        pixelX = 0
        maxPixelX = 0
        captureFrame = (vsyncCount >= 6)
      }

      // Hsync rising edge = new line
      if (hsync && !lastHsync) {
        hsyncCount += 1
        if (captureFrame) {
          if (pixelX > maxPixelX) maxPixelX = pixelX
          pixelX = 0
          pixelY += 1
          if (pixelY >= maxHeight) pixelY = maxHeight - 1
        }
      }

      // Capture raw colour index on colour clock when not blanked
      // VIDEO_B is 8-bit Atari palette index: upper nibble=hue, lower=luma
      // Use full byte value as grayscale intensity (non-zero = visible)
      if (captureFrame && colourClock && !blank && !vsync) {
        if (pixelX < maxWidth && pixelY < maxHeight) {
          val colIdx = dut.io.dbgVideoB.toInt
          frameR(pixelY)(pixelX) = colIdx
          frameG(pixelY)(pixelX) = colIdx
          frameB(pixelY)(pixelX) = colIdx
        }
        pixelX += 1
      }

      lastHsync = hsync
      lastVsync = vsync

      // ---------------------------------------------------------------
      // Video signal monitoring
      // ---------------------------------------------------------------
      val videoB = dut.io.dbgVideoB.toInt
      if (videoB > maxVideoB) maxVideoB = videoB
      if (videoB > 0) nonZeroVideoB += 1
      if (videoB > 0 && !videoBvalues.contains(videoB)) {
        videoBvalues += videoB
        if (videoBvalues.size <= 20) println(f"  ** New VIDEO_B value: 0x${videoB}%02X ($videoB) at cycle $cycleCount%d")
      }
      val scanR = dut.io.atariVgaR.toInt
      val scanG = dut.io.atariVgaG.toInt
      val scanB = dut.io.atariVgaB.toInt
      if (scanR > maxScanR) maxScanR = scanR
      if (scanG > maxScanG) maxScanG = scanG
      if (scanB > maxScanB) maxScanB = scanB
      if (scanR > 0 || scanG > 0 || scanB > 0) nonZeroScanRGB += 1

      // ---------------------------------------------------------------
      // GTIA/ANTIC debug monitoring
      // ---------------------------------------------------------------
      val colbk = dut.io.dbgColbk.toInt
      if (colbk > maxColbk) maxColbk = colbk
      if (dut.io.dbgColourClock.toBoolean) colourClockCount += 1
      if (dut.io.dbgVisibleLive.toBoolean) visibleLiveCount += 1
      val an = dut.io.dbgAN.toInt
      anCounts(an) += 1
      if (dut.io.dbgGtiaWrEn.toBoolean) gtiaWrites += 1
      if (dut.io.dbgAnticWrEn.toBoolean) anticWrites += 1
      val dmactl = dut.io.dbgDmactl.toInt
      if (dmactl > maxDmactl) maxDmactl = dmactl

      // ---------------------------------------------------------------
      // JOP BMB bridge monitoring
      // ---------------------------------------------------------------
      if (dut.io.dbgJopBmbCmdValid.toBoolean) jopBmbCmdValid += 1
      if (dut.io.dbgJopSdramReq.toBoolean)   jopSdramReqs   += 1
      if (dut.io.dbgJopBmbRspValid.toBoolean) jopBmbRspValid += 1
      // Log first JOP BMB cmd.valid (indicates memory controller is trying)
      if (dut.io.dbgJopBmbCmdValid.toBoolean && jopBmbCmdValid == 1)
        println(f"  ** JOP first BMB cmd.valid at cyc=$cycleCount%d")
      if (dut.io.dbgJopSdramReq.toBoolean && jopSdramReqs == 1)
        println(f"  ** JOP first SDRAM request pulse at cyc=$cycleCount%d")
      // Log JOP state every 100k cycles for diagnosis (first 1M) and every 1M after
      val logJopState = (cycleCount % 100000 == 0 && cycleCount <= 1000000) ||
                        (cycleCount % 1000000 == 0 && cycleCount > 1000000)
      if (logJopState) {
        val jpc      = dut.io.dbgJopPc.toInt
        val memSt    = dut.io.dbgJopMemState.toInt
        val memBusy  = dut.io.dbgJopMemBusy.toBoolean
        val ioRd     = dut.io.dbgJopIoRdCount.toInt
        val ioWr     = dut.io.dbgJopIoWrCount.toInt
        val exc      = dut.io.dbgJopExc.toBoolean
        val bcAddr   = dut.io.dbgJopBcFillAddr.toInt
        val bcLen    = dut.io.dbgJopBcFillLen.toInt
        val bcCapt   = dut.io.dbgJopBcRdCapture.toLong & 0xFFFFFFFFL
        println(f"  JOP@$cycleCount%d: pc=0x${jpc}%03X memSt=$memSt%d busy=$memBusy%b ioRd=$ioRd%d ioWr=$ioWr%d exc=$exc%b bmbCmd=$jopBmbCmdValid%d bcFill=0x${bcAddr}%06X len=$bcLen%d raw=0x${bcCapt}%08X")
      }
      // Track bcFill — log once when it first happens
      val bcLen = dut.io.dbgJopBcFillLen.toInt
      if (bcLen > 0 && !jopBcFillSeen) {
        jopBcFillSeen = true
        val bcAddr = dut.io.dbgJopBcFillAddr.toInt
        val bcCapt = dut.io.dbgJopBcRdCapture.toLong & 0xFFFFFFFFL
        val jpc    = dut.io.dbgJopPc.toInt
        println(f"  ** JOP bcFill FIRST: addr=0x${bcAddr}%06X len=$bcLen%d raw=0x${bcCapt}%08X jpc=0x${jpc}%03X cyc=$cycleCount%d")
        jopBcFillFirstAddr = bcAddr; jopBcFillFirstLen = bcLen; jopBcFillFirstRaw = bcCapt
      }

      // ---------------------------------------------------------------
      // JOP UART monitoring
      // ---------------------------------------------------------------
      val uartTx = dut.io.jopUartTx.toBoolean
      if (uartTx != lastUartTx) jopUartBits += 1
      lastUartTx = uartTx

      // ---------------------------------------------------------------
      // Periodic status
      // ---------------------------------------------------------------
      if (cycleCount % reportInterval == 0) {
        val timeUs = cycleCount.toDouble * clockPeriodPs / 1e6
        println(f"  Cycle $cycleCount%,d ($timeUs%.0f us): " +
          f"hsync=$hsyncCount%d vsync=$vsyncCount%d " +
          f"SDRAM req=$sdramRequests%d rd=$sdramReads%d wr=$sdramWrites%d " +
          f"Atari-raw=$atariRawReqs%d(rd=$atariRawReads%d wr=$atariRawWrites%d) " +
          f"VidB_max=$maxVideoB%d(nz=$nonZeroVideoB%d) ScanRGB_max=($maxScanR%d,$maxScanG%d,$maxScanB%d nz=$nonZeroScanRGB%d) " +
          f"COLBK=$maxColbk%d cclk=$colourClockCount%d vis=$visibleLiveCount%d " +
          f"gtiaW=$gtiaWrites%d anticW=$anticWrites%d DMACTL=$maxDmactl%d " +
          f"frames=$framesCaptured%d cpuEN=$cpuEnCount%d PC=0x${lastPc}%04X")
      }
    }

    // Capture final partial frame if any
    if (captureFrame && pixelY > 10 && framesCaptured < 3) {
      maxPixelY = pixelY
      if (maxPixelX == 0 && pixelX > 0) maxPixelX = pixelX
      writePpm(framesCaptured)
      framesCaptured += 1
    }

    // Final report
    println("=" * 70)
    println(s"Simulation complete: $cycleCount cycles")
    println(s"  VGA: $hsyncCount hsync, $vsyncCount vsync")
    println(s"  SDRAM: $sdramRequests requests, $sdramReads reads, $sdramWrites writes")
    println(s"  Atari raw: $atariRawReqs requests, $atariRawReads reads, $atariRawWrites writes")
    println(s"  JOP UART: $jopUartBits line transitions")
    println(s"  JOP BMB: cmd.valid=$jopBmbCmdValid cycles, sdramReq=$jopSdramReqs pulses, rsp.valid=$jopBmbRspValid cycles")
    println(s"  COLBK max: $maxColbk (${if (maxColbk > 0) "CPU wrote colour regs" else "never written"})")
    println(s"  Colour clock: $colourClockCount pulses, visibleLive: $visibleLiveCount cycles")
    println(s"  GTIA writes: $gtiaWrites, ANTIC writes: $anticWrites, DMACTL max: $maxDmactl")
    println(s"  AN values: ${anCounts.zipWithIndex.map{case(c,i) => f"$i%d=$c%d"}.mkString(" ")}")
    println(s"  Frames captured: $framesCaptured")
    println(s"  Unique VIDEO_B values: ${videoBvalues.size} -> ${videoBvalues.toSeq.sorted.map(v => f"0x$v%02X").mkString(", ")}")
    println(f"  CPU: $cpuEnCount%d enables, last PC=0x${lastPc}%04X")
    println(s"  PC pages: ${pcPageSeen.zipWithIndex.filter(_._1).map(p => f"0x${p._2}%X000").mkString(", ")}")
    if (pcLoopDetected.nonEmpty) println(s"  CPU LOOP: $pcLoopDetected")

    if (hsyncCount > 10) println(s"  PASS: VGA hsync active ($hsyncCount pulses)")
    else println(s"  WARN: Low VGA hsync count ($hsyncCount)")

    if (vsyncCount >= 1) println(s"  PASS: VGA vsync detected ($vsyncCount frames)")
    else println(s"  WARN: No VGA vsync (may need more cycles)")

    if (sdramRequests > 0) println(s"  PASS: SDRAM arbiter active ($sdramRequests requests)")
    else println(s"  WARN: No SDRAM requests")

    if (jopUartBits > 10) println(s"  PASS: JOP UART active ($jopUartBits bit transitions)")
    else println(s"  WARN: JOP UART silent (expected > 10 transitions if AtariApp booted)")

    // Final JOP state snapshot
    val finalJpc    = dut.io.dbgJopPc.toInt
    val finalMemSt  = dut.io.dbgJopMemState.toInt
    val finalMemBsy = dut.io.dbgJopMemBusy.toBoolean
    val finalIoRd   = dut.io.dbgJopIoRdCount.toInt
    val finalIoWr   = dut.io.dbgJopIoWrCount.toInt
    val finalExc    = dut.io.dbgJopExc.toBoolean
    val finalBcAddr = dut.io.dbgJopBcFillAddr.toInt
    val finalBcLen  = dut.io.dbgJopBcFillLen.toInt
    val finalBcCapt = dut.io.dbgJopBcRdCapture.toLong & 0xFFFFFFFFL
    println(f"  JOP final state: pc=0x${finalJpc}%03X memSt=$finalMemSt%d busy=$finalMemBsy%b ioRd=$finalIoRd%d ioWr=$finalIoWr%d exc=$finalExc%b")
    println(f"  JOP bcFill final: addr=0x${finalBcAddr}%06X len=$finalBcLen%d raw=0x${finalBcCapt}%08X")
    if (jopBcFillSeen)
      println(f"  JOP bcFill first: addr=0x${jopBcFillFirstAddr}%06X len=$jopBcFillFirstLen%d raw=0x${jopBcFillFirstRaw}%08X")
    else
      println(s"  JOP bcFill: never observed")

    println(s"  Waveform: sim_workspace/Atari800JopSim/Atari800Jop_combined_test.vcd")
  }
}
