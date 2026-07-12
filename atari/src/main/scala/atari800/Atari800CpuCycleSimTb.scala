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
    .compile(new Atari800CoreSim(cartridge_rom = cart, internal_ram = 49152))

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

    var pVs = false; var pCpu = false; var pAntic = false; var pRefresh = false
    var frame = 0
    var nCpu = 0; var nAntic = 0; var nRefresh = 0
    // N_cpu measured as the arbiter's CPU memory grant (one per 6502 bus cycle) —
    // NOT the raw combinational CPU_ENABLE, which toggles sub-cycle.
    val results = scala.collection.mutable.ArrayBuffer[(Int, Int, Int)]()  // (cpu, antic, refresh)

    val maxCycles = 40000000  // safety cap (~35 PAL frames)
    var done = false
    var c = 0
    while (!done && c < maxCycles) {
      dut.clockDomain.waitRisingEdge()
      c += 1

      // minimal SDRAM tie-off (BRAM config makes no data requests, but be safe)
      dut.io.sdramRequestComplete #= dut.io.sdramRequest.toBoolean

      val vs      = dut.io.videoVs.toBoolean
      val cpu     = dut.atariCore.atari800xl.mmu1.notifyCpu.toBoolean     // true CPU bus-cycle grant
      val antic   = dut.atariCore.atari800xl.mmu1.notifyAntic.toBoolean
      val refresh = dut.io.sdramRefresh.toBoolean

      // frame boundary = vsync rising edge
      if (vs && !pVs) {
        if (frame >= warmupFrames && frame < warmupFrames + measureFrames)
          results += ((nCpu, nAntic, nRefresh))
        nCpu = 0; nAntic = 0; nRefresh = 0
        frame += 1
        if (frame >= warmupFrames + measureFrames) done = true
      }

      // count rising edges within the frame (only while in/after warmup)
      if (frame >= warmupFrames) {
        if (cpu && !pCpu)         nCpu += 1
        if (antic && !pAntic)     nAntic += 1
        if (refresh && !pRefresh) nRefresh += 1
      }
      pVs = vs; pCpu = cpu; pAntic = antic; pRefresh = refresh
    }

    // Derive from RELIABLE signals only. notifyCpu is inflated (fast BRAM re-grants
    // the held CPU request many times/machine-cycle), but notifyAntic (transient,
    // one per fetch) and refresh are exact. Frame geometry from refresh: real Atari
    // does 9 refresh cycles/scanline, 114 machine cycles/scanline.
    val CYCLES_PER_LINE = 114; val REFRESH_PER_LINE = 9
    println("=" * 72)
    println(f"CPU cycle-stealing baseline — cart='$cart', internal_ram=49152 (BRAM)")
    println("=" * 72)
    println(f"${"frame"}%-6s ${"lines"}%6s ${"machCyc"}%8s ${"N_antic"}%8s ${"refresh"}%8s ${"ourCPU"}%8s ${"realCPU"}%8s ${"fast"}%7s")
    var sumAntic = 0L; var sumRef = 0L; var sumOur = 0L; var sumReal = 0L; var sumLines = 0L
    for (((_, antic, ref), i) <- results.zipWithIndex) {
      val lines   = ref / REFRESH_PER_LINE
      val machCyc = lines * CYCLES_PER_LINE
      val ourCpu  = machCyc - ref             // today: CPU loses only refresh
      val realCpu = machCyc - ref - antic     // real 800: also loses ANTIC DMA
      val fast    = if (realCpu > 0) ourCpu.toDouble / realCpu else 0.0
      println(f"${i}%-6d ${lines}%6d ${machCyc}%8d ${antic}%8d ${ref}%8d ${ourCpu}%8d ${realCpu}%8d ${fast}%6.3fx")
      sumAntic += antic; sumRef += ref; sumOur += ourCpu; sumReal += realCpu; sumLines += lines
    }
    val n = math.max(1, results.size)
    val aSpeedup = if (sumReal > 0) sumOur.toDouble / sumReal else 0.0
    println("-" * 72)
    println(f"avg    ${sumLines/n}%6d ${(sumOur+sumRef)/n}%8d ${sumAntic/n}%8d ${sumRef/n}%8d ${sumOur/n}%8d ${sumReal/n}%8d ${aSpeedup}%6.3fx")
    println()
    println(f"ANTIC steals ~${sumAntic/n} DMA cycles/frame that our core gives the CPU instead.")
    println(f"CPU-bound games (Defender) run about ${aSpeedup}%.2fx too fast on this display load.")
    println(f"(Frame-locked games are immune. Heavier displays => more ANTIC DMA => faster.)")
  }
}
