package atari800

import spinal.core._
import spinal.core.sim._

// Measures how many CPU bus-cycles the 6502 gets per frame vs how many ANTIC
// steals for DMA — the quantitative baseline for the missing ANTIC-DMA
// cycle-stealing (see project memory project_cpu_cycle_stealing). CPU-bound
// games (Defender) run fast because the CPU keeps cycles that a real Atari would
// hand to ANTIC.
//
// Config MUST match hardware: internal_ram=49152 (48K RAM in BRAM, always-ready)
// — that's the config where cycle-stealing is absent. With SDRAM RAM the memory
// latency would throttle the CPU differently.
//
// Per frame it counts, on the machine-cycle grid:
//   N_cpu     = 6502 bus cycles  (cpu6502.CPU_ENABLE rising edges)
//   N_antic   = ANTIC memory grants (mmu1.notifyAntic) — display/PM/DL/LMS DMA
//   N_refresh = DRAM refresh cycles (already stolen from the CPU today)
// Our core: N_cpu ≈ machineCycles − refresh (CPU skips only refresh).
// Real 800: CPU would ALSO lose N_antic → N_cpu_real ≈ N_cpu − N_antic.
//   => speedup ≈ N_cpu / (N_cpu − N_antic)   (CPU-bound game runs this much fast)
object Atari800CpuCycleSimTb extends App {
  val cart = if (args.nonEmpty) args(0) else "roms/Star Raiders.rom"
  val throttle = if (args.length > 1) args(1).toInt else 31  // 31 = hardware default

  val compiled = SimConfig
    .withConfig(SpinalConfig(
      defaultClockDomainFrequency = FixedFrequency(56.67 MHz),
      defaultConfigForClockDomains = ClockDomainConfig(resetKind = ASYNC, resetActiveLevel = LOW)
    ))
    .workspacePath("sim_workspace")
    .addSimulatorFlag("-Wno-WIDTHEXPAND")
    .addSimulatorFlag("-Wno-WIDTHTRUNC")
    .addSimulatorFlag("--x-initial-edge")
    .addSimulatorFlag("--x-assign 0")
    .compile(new Atari800CoreSim(cartridge_rom = cart, internal_ram = 49152, throttle = throttle))

  compiled.doSim("cpu_cycle_measure", seed = 42) { dut =>
    dut.clockDomain.forkStimulus(period = 17640)  // 56.67 MHz

    dut.io.reset_btn  #= false
    dut.io.option_btn #= false
    dut.io.select_btn #= false
    dut.io.start_btn  #= false
    dut.io.joy1       #= 0x1F
    dut.io.sdramRequestComplete #= false
    dut.io.sdramDo   #= 0

    dut.clockDomain.waitRisingEdge(10)
    dut.clockDomain.assertReset()
    dut.clockDomain.waitRisingEdge(100)
    dut.clockDomain.deassertReset()

    val warmupFrames = 6   // let it boot + reach a steady display
    val measureFrames = 8

    var pVs = false; var pAntic = false; var pRefresh = false; var pRdy = false
    var frame = 0
    var nInstr = 0; var nRdy = 0; var nAntic = 0; var nRefresh = 0; var lastPc = -1
    // GROUND-TRUTH the 6502 rate: nInstr = distinct-PC transitions (instructions),
    // nRdy = CPU_ENABLE_RDY rising edges (candidate bus-cycle count). Compare to
    // machine-cycle budget to see if the 6502 is really ~1x or inflated.
    val results = scala.collection.mutable.ArrayBuffer[(Int, Int, Int, Int)]()  // (instr, rdy, antic, refresh)

    val maxCycles = 40000000  // safety cap (~35 PAL frames)
    var done = false
    var c = 0
    while (!done && c < maxCycles) {
      dut.clockDomain.waitRisingEdge()
      c += 1

      // minimal SDRAM tie-off (BRAM config makes no data requests, but be safe)
      dut.io.sdramRequestComplete #= dut.io.sdramRequest.toBoolean

      val vs      = dut.io.videoVs.toBoolean
      val rdy     = dut.atariCore.atari800xl.cpu6502.CPU_ENABLE_RDY.toBoolean
      val pc      = dut.atariCore.atari800xl.cpu6502.debugPc.toInt
      val antic   = dut.atariCore.atari800xl.mmu1.notifyAntic.toBoolean
      val refresh = dut.io.sdramRefresh.toBoolean

      // frame boundary = vsync rising edge
      if (vs && !pVs) {
        if (frame >= warmupFrames && frame < warmupFrames + measureFrames)
          results += ((nInstr, nRdy, nAntic, nRefresh))
        nInstr = 0; nRdy = 0; nAntic = 0; nRefresh = 0
        frame += 1
        if (frame >= warmupFrames + measureFrames) done = true
      }

      // count within the frame (only while in/after warmup)
      if (frame >= warmupFrames) {
        if (pc != lastPc)         nInstr += 1
        if (rdy && !pRdy)         nRdy += 1
        if (antic && !pAntic)     nAntic += 1
        if (refresh && !pRefresh) nRefresh += 1
      }
      lastPc = pc
      pVs = vs; pRdy = rdy; pAntic = antic; pRefresh = refresh
    }

    val CYCLES_PER_LINE = 114; val REFRESH_PER_LINE = 9
    println("=" * 76)
    println(f"CPU cycle rate — cart='$cart', internal_ram=49152 (BRAM), throttle=$throttle")
    println("=" * 76)
    println(f"${"frame"}%-6s ${"lines"}%6s ${"machCyc"}%8s ${"instr"}%8s ${"cpuRdy"}%8s ${"N_antic"}%8s ${"refresh"}%8s")
    var sInstr = 0L; var sRdy = 0L; var sAntic = 0L; var sRef = 0L; var sLines = 0L
    for (((instr, rdy, antic, ref), i) <- results.zipWithIndex) {
      val lines = ref / REFRESH_PER_LINE
      println(f"${i}%-6d ${lines}%6d ${lines*CYCLES_PER_LINE}%8d ${instr}%8d ${rdy}%8d ${antic}%8d ${ref}%8d")
      sInstr += instr; sRdy += rdy; sAntic += antic; sRef += ref; sLines += lines
    }
    val n = math.max(1, results.size)
    val lines = sLines/n; val machCyc = lines*CYCLES_PER_LINE
    println("-" * 76)
    println(f"avg    ${lines}%6d ${machCyc}%8d ${sInstr/n}%8d ${sRdy/n}%8d ${sAntic/n}%8d ${sRef/n}%8d")
    println()
    println(f"machine cycles/frame = $machCyc; refresh = ${sRef/n}; ANTIC DMA = ${sAntic/n}")
    println(f"6502 bus cycles/frame (cpuRdy) = ${sRdy/n}  <- should be ~1 per machine cycle")
    println(f"instructions/frame = ${sInstr/n}")
    val ourCpu = sRdy/n; val realCpu = ourCpu - sAntic/n
    if (realCpu > 0)
      println(f"If cpuRdy ~= machCyc-refresh, real-800 CPU would be ~$realCpu => ${ourCpu.toDouble/realCpu}%.3fx too fast")
  }
}
