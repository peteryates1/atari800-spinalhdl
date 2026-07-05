package atari800

import spinal.core._
import spinal.core.sim._
import java.nio.file.{Files, Paths}

// Boot the core with internal_rom = 0 (ROM_IN_RAM): the OS lives in
// behavioral SDRAM at the AddressDecoder's OS window (0x704000 + addr[13:0]),
// exactly as the RP2040 supervisor loads it on hardware. Reproduces the
// hardware symptom bench: does the CPU get past the reset vector?
// Run: sbt "atari/runMain atari800.Atari800RomInRamSimTb"
object Atari800RomInRamSimTb extends App {
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
    .compile(new Atari800CoreSim(cartridge_rom = "", internal_ram = 0, internal_rom = 0))

  compiled.doSim("rom_in_ram_boot", seed = 42) { dut =>
    val sdram = new SdramBehavioral(sizeBytes = 8 * 1024 * 1024)
    // Preload the OS exactly as the supervisor does
    val os2 = Files.readAllBytes(Paths.get("roms/atarios2.rom"))
    val osb = Files.readAllBytes(Paths.get("roms/atariosb.rom"))
    for (i <- os2.indices) sdram.mem(0x385800 + i) = os2(i)
    for (i <- osb.indices) sdram.mem(0x386000 + i) = osb(i)
    println(f"preloaded: os2 ${os2.length} @385800, osb ${osb.length} @386000; vector=${osb(0x1FFC) & 0xFF}%02x ${osb(0x1FFD) & 0xFF}%02x")

    dut.clockDomain.forkStimulus(period = 17640)
    dut.io.reset_btn #= false; dut.io.option_btn #= false
    dut.io.select_btn #= false; dut.io.start_btn #= false
    dut.io.joy1 #= 0x1F
    dut.io.sdramRequestComplete #= false; dut.io.sdramDo #= 0
    dut.clockDomain.waitRisingEdge(10)
    dut.clockDomain.assertReset()
    dut.clockDomain.waitRisingEdge(100)
    dut.clockDomain.deassertReset()

    var osReads = 0
    var vsyncs = 0; var lastVs = false
    val pcPages = scala.collection.mutable.LinkedHashSet[Int]()
    var cycles = 0
    val maxCycles = 3000000

    // Realistic completion latency: the real SdramStatemachine + arbiter takes
    // ~15 sys cycles per transaction (the zero-latency model hides handshake
    // bugs that only appear with delayed completion).
    var pendingCnt = 0
    var pendingWrite = false
    while (cycles < maxCycles && vsyncs < 3) {
      dut.clockDomain.waitRisingEdge()
      cycles += 1
      val request = dut.io.sdramRequest.toBoolean
      dut.io.sdramRequestComplete #= false
      if (request && pendingCnt == 0) {
        pendingCnt = 15
        pendingWrite = dut.io.sdramWriteEnable.toBoolean
        if (pendingWrite) {
          sdram.write(dut.io.sdramAddr.toInt, dut.io.sdramDi.toLong,
                      dut.io.sdramWrite8.toBoolean, dut.io.sdramWrite16.toBoolean, dut.io.sdramWrite32.toBoolean)
        } else {
          val addr = dut.io.sdramAddr.toInt
          val data = sdram.read32(addr)
          dut.io.sdramDo #= data
          if (addr >= 0x380000) {
            osReads += 1
            if (osReads <= 12) println(f"OS read #$osReads addr=$addr%06x data8=${data & 0xFF}%02x cyc=$cycles")
          }
        }
      } else if (pendingCnt > 0) {
        pendingCnt -= 1
        if (pendingCnt == 0) dut.io.sdramRequestComplete #= true
      }
      if (dut.atariCore.atari800xl.cpu6502.CPU_ENABLE.toBoolean) {
        val pc = dut.atariCore.atari800xl.cpu6502.debugPc.toInt
        if (!pcPages.contains(pc >> 12)) {
          pcPages += (pc >> 12)
          println(f"CPU entered page ${pc >> 12}%X000 (PC=$pc%04X) cyc=$cycles")
        }
      }
      val vs = dut.io.videoVs.toBoolean
      if (vs && !lastVs) { vsyncs += 1; println(s"vsync #$vsyncs at cyc=$cycles") }
      lastVs = vs
    }
    println(s"done: cycles=$cycles osReads=$osReads vsyncs=$vsyncs pcPages=${pcPages.map(_.toHexString).mkString(",")}")
  }
}
