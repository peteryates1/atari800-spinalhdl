package atari800

import spinal.core._
import spinal.core.sim._

// Reproduces the i5 HDMI top's EXACT Atari config + SDRAM stub in sim, to see whether the
// embedded-ROM model (internal_rom=3, internal_ram=49152, Star Raiders) actually boots with
// SDRAM_REQUEST_COMPLETE looped back and SDRAM_DO=0 (the hardware stub). Traces CPU PC pages,
// COLBK, and first non-black video. Run: sbt "atari/runMain atari800.Atari800Ecp5BootSimTb"
class Atari800Ecp5BootHarness extends Component {
  val io = new Bundle {
    val vs      = out Bool()
    val colNZ   = out Bool()   // any VIDEO_R/G/B non-zero
    val colbkNZ = out Bool()   // COLBK register non-zero
  }
  val atari = new Atari800CoreSimpleSdram(
    cycle_length = 21, video_bits = 8, palette = 0, internal_rom = 3,
    internal_ram = 49152, basic_in_sdram = false, cartridge_rom = "roms/Star Raiders.rom")
  atari.io.PAL := False; atari.io.RAM_SELECT := B"011"; atari.io.HALT := False
  atari.io.TURBO_VBLANK_ONLY := False; atari.io.THROTTLE_COUNT_6502 := B(20, 6 bits)
  atari.io.emulated_cartridge_select := B(0, 6 bits)
  atari.io.freezer_enable := False; atari.io.freezer_activate := False
  atari.io.atari800mode := True; atari.io.HIRES_ENA := False
  atari.io.JOY1_n := B"11111"; atari.io.JOY2_n := B"11111"
  atari.io.JOY3_n := B"11111"; atari.io.JOY4_n := B"11111"
  for (p <- Seq(atari.io.PADDLE0, atari.io.PADDLE1, atari.io.PADDLE2, atari.io.PADDLE3,
                atari.io.PADDLE4, atari.io.PADDLE5, atari.io.PADDLE6, atari.io.PADDLE7)) p := S(0, 8 bits)
  atari.io.KEYBOARD_RESPONSE := B"11"; atari.io.SIO_RXD := True
  atari.io.CONSOL_OPTION := False; atari.io.CONSOL_SELECT := False; atari.io.CONSOL_START := False
  atari.io.DMA_FETCH := False; atari.io.DMA_READ_ENABLE := False
  atari.io.DMA_32BIT_WRITE_ENABLE := False; atari.io.DMA_16BIT_WRITE_ENABLE := False
  atari.io.DMA_8BIT_WRITE_ENABLE := False; atari.io.DMA_ADDR := B(0, 24 bits)
  atari.io.DMA_WRITE_DATA := B(0, 32 bits)
  atari.io.SDRAM_REQUEST_COMPLETE := atari.io.SDRAM_REQUEST   // hardware stub (loopback)
  atari.io.SDRAM_DO := B(0, 32 bits)

  atari.atari800xl.cpu6502.CPU_ENABLE.simPublic()
  atari.atari800xl.cpu6502.debugPc.simPublic()

  io.vs      := atari.io.VIDEO_VS
  io.colNZ   := (atari.io.VIDEO_R ## atari.io.VIDEO_G ## atari.io.VIDEO_B).orR
  io.colbkNZ := atari.io.dbgColbk.orR
}

object Atari800Ecp5BootSimTb extends App {
  val compiled = SimConfig
    .withConfig(SpinalConfig(
      defaultClockDomainFrequency = FixedFrequency(37.5 MHz),
      defaultConfigForClockDomains = ClockDomainConfig(resetKind = ASYNC, resetActiveLevel = LOW)))
    .workspacePath("sim_workspace")
    .addSimulatorFlag("-Wno-WIDTHEXPAND").addSimulatorFlag("-Wno-WIDTHTRUNC")
    .addSimulatorFlag("--x-initial-edge").addSimulatorFlag("--x-assign 0")
    .compile(new Atari800Ecp5BootHarness)

  compiled.doSim("ecp5_boot", seed = 42) { dut =>
    dut.clockDomain.forkStimulus(period = 26666)
    dut.clockDomain.waitRisingEdge(10); dut.clockDomain.assertReset()
    dut.clockDomain.waitRisingEdge(100); dut.clockDomain.deassertReset()

    var cycles = 0; var vsyncs = 0; var lastVs = false
    var firstColor = -1; var firstColbk = -1
    val pcPages = scala.collection.mutable.LinkedHashSet[Int]()
    while (cycles < 4000000 && vsyncs < 5) {
      dut.clockDomain.waitRisingEdge(); cycles += 1
      if (dut.atari.atari800xl.cpu6502.CPU_ENABLE.toBoolean) {
        val pc = dut.atari.atari800xl.cpu6502.debugPc.toInt
        if (!pcPages.contains(pc >> 12)) { pcPages += (pc >> 12); println(f"CPU page ${pc >> 12}%X000 (PC=$pc%04X) cyc=$cycles") }
      }
      if (firstColor < 0 && dut.io.colNZ.toBoolean)  { firstColor = cycles; println(s"first non-black video @cyc=$cycles") }
      if (firstColbk < 0 && dut.io.colbkNZ.toBoolean) { firstColbk = cycles; println(s"first COLBK!=0 @cyc=$cycles") }
      val vs = dut.io.vs.toBoolean
      if (vs && !lastVs) { vsyncs += 1; println(s"vsync #$vsyncs cyc=$cycles") }
      lastVs = vs
    }
    println(s"done: cycles=$cycles vsyncs=$vsyncs firstColor=$firstColor firstColbk=$firstColbk")
    println(s"PC pages visited: ${pcPages.map(_.toHexString).mkString(",")}")
  }
}
