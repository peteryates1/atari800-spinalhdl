package atari800

import spinal.core._
import spinal.core.sim._
import spinal.lib._
import java.nio.file.{Files, Paths}

// The definitive ROM-in-RAM boot simulation: the REAL hardware topology —
// Atari core -> SdramArbiter3 port A -> toggle-protocol MockSdram — instead
// of a behavioral model driving the core's interface directly. A request-
// pulse-shape or toggle-handshake bug in the ROM fetch path shows up here
// and nowhere else. OS window at 0x140000 (current decoder map).
// Run: sbt "atari/runMain atari800.Atari800ArbBootSimTb"
class Atari800ArbBootHarness(cartMode: Int = 0) extends Component {
  val io = new Bundle {
    val videoVs   = out Bool()
    val osReadSeen = out Bool()
  }
  val atari = new Atari800CoreSimpleSdram(
    cycle_length = 32, video_bits = 8, palette = 0,
    internal_rom = 0, internal_ram = 0,
    basic_in_sdram = false, cartridge_rom = ""
  )
  // idle non-SDRAM inputs
  atari.io.PAL := True
  atari.io.RAM_SELECT := B"011"
  atari.io.TURBO_VBLANK_ONLY := False
  atari.io.THROTTLE_COUNT_6502 := B(31, 6 bits)
  atari.io.emulated_cartridge_select := B(cartMode, 6 bits)
  atari.io.freezer_enable := False
  atari.io.freezer_activate := False
  atari.io.atari800mode := True
  atari.io.HIRES_ENA := False
  atari.io.JOY1_n := B"11111"; atari.io.JOY2_n := B"11111"
  atari.io.JOY3_n := B"11111"; atari.io.JOY4_n := B"11111"
  atari.io.KEYBOARD_RESPONSE := B"11"
  atari.io.CONSOL_OPTION := False
  atari.io.CONSOL_SELECT := False
  atari.io.CONSOL_START := False
  atari.io.HALT := False
  atari.io.SIO_RXD := True
  atari.io.DMA_FETCH := False
  atari.io.DMA_READ_ENABLE := False
  atari.io.DMA_32BIT_WRITE_ENABLE := False
  atari.io.DMA_16BIT_WRITE_ENABLE := False
  atari.io.DMA_8BIT_WRITE_ENABLE := False
  atari.io.DMA_ADDR := 0
  atari.io.DMA_WRITE_DATA := 0
  for (pad <- Seq(atari.io.PADDLE0, atari.io.PADDLE1, atari.io.PADDLE2, atari.io.PADDLE3,
                  atari.io.PADDLE4, atari.io.PADDLE5, atari.io.PADDLE6, atari.io.PADDLE7))
    pad := 0

  Seq(atari.io.SDRAM_REQUEST, atari.io.SDRAM_ADDR).foreach(_.simPublic())
  atari.atari800xl.cpu6502.CPU_ENABLE.simPublic()
  atari.atari800xl.cpu6502.debugPc.simPublic()

  val arb  = new SdramArbiter3
  val mock = new MockSdram(latency = 6, memBits = 22)   // 4 MB
  mock.mem.simPublic()

  arb.io.a.request        := atari.io.SDRAM_REQUEST
  arb.io.a.readEnable     := atari.io.SDRAM_READ_ENABLE
  arb.io.a.writeEnable    := atari.io.SDRAM_WRITE_ENABLE
  arb.io.a.addr           := B"0" ## atari.io.SDRAM_ADDR
  arb.io.a.dataIn         := atari.io.SDRAM_DI
  arb.io.a.byteAccess     := atari.io.SDRAM_8BIT_WRITE_ENABLE
  arb.io.a.wordAccess     := atari.io.SDRAM_16BIT_WRITE_ENABLE
  arb.io.a.longwordAccess := atari.io.SDRAM_32BIT_WRITE_ENABLE
  arb.io.a.refresh        := atari.io.SDRAM_REFRESH   // core output
  atari.io.SDRAM_REQUEST_COMPLETE := arb.io.a.complete
  atari.io.SDRAM_DO               := arb.io.a.dataOut

  for (p <- Seq(arb.io.b, arb.io.c, arb.io.d)) {
    p.request := False; p.readEnable := False; p.writeEnable := False
    p.addr := 0; p.dataIn := 0
    p.byteAccess := False; p.wordAccess := False; p.longwordAccess := False
  }
  arb.io.b.wideAccess := False; arb.io.b.wideIn := 0
  arb.io.c.wideAccess := False

  mock.io.REQUEST := arb.io.sdram.request;  arb.io.sdram.complete := mock.io.COMPLETE
  mock.io.READ_EN := arb.io.sdram.readEnable; mock.io.WRITE_EN := arb.io.sdram.writeEnable
  mock.io.ADDRESS_IN := arb.io.sdram.addr; mock.io.DATA_IN := arb.io.sdram.dataIn
  arb.io.sdram.dataOut := mock.io.DATA_OUT
  mock.io.BYTE_ACCESS := arb.io.sdram.byteAccess; mock.io.WORD_ACCESS := arb.io.sdram.wordAccess
  mock.io.LONGWORD_ACCESS := arb.io.sdram.longwordAccess; mock.io.REFRESH := arb.io.sdram.refresh
  mock.io.WIDE_ACCESS := arb.io.sdram.wideAccess; mock.io.WIDE_IN := arb.io.sdram.wideIn
  arb.io.sdram.wideOut := mock.io.WIDE_OUT

  io.videoVs := atari.io.VIDEO_VS
  io.osReadSeen := RegInit(False) setWhen (atari.io.SDRAM_REQUEST && atari.io.SDRAM_ADDR(20))
}

object Atari800ArbBootSimTb extends App {
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
    .compile(new Atari800ArbBootHarness)

  compiled.doSim("arb_boot", seed = 42) { dut =>
    val os2 = Files.readAllBytes(Paths.get("roms/atarios2.rom"))
    val osb = Files.readAllBytes(Paths.get("roms/atariosb.rom"))
    for (i <- os2.indices) dut.mock.mem.setBigInt(0xD800 + i, BigInt(os2(i) & 0xFF))
    for (i <- osb.indices) dut.mock.mem.setBigInt(0xE000 + i, BigInt(osb(i) & 0xFF))
    println(f"preloaded OS; vector bytes ${osb(0x1FFC) & 0xFF}%02x ${osb(0x1FFD) & 0xFF}%02x")

    dut.clockDomain.forkStimulus(period = 17640)
    dut.clockDomain.waitRisingEdge(10)
    dut.clockDomain.assertReset()
    dut.clockDomain.waitRisingEdge(100)
    dut.clockDomain.deassertReset()

    var vsyncs = 0; var lastVs = false
    var osReads = 0; var lastReq = false
    var cycles = 0
    val pcPages = scala.collection.mutable.LinkedHashSet[Int]()
    while (cycles < 3000000 && vsyncs < 3) {
      dut.clockDomain.waitRisingEdge(); cycles += 1
      val req = dut.atari.io.SDRAM_REQUEST.toBoolean
      if (req && !lastReq && dut.atari.io.SDRAM_ADDR.toInt >= 0xD800 && dut.atari.io.SDRAM_ADDR.toInt <= 0xFFFF) {
        osReads += 1
        if (osReads <= 8) println(f"OS req #$osReads addr=${dut.atari.io.SDRAM_ADDR.toInt}%06x cyc=$cycles")
      }
      lastReq = req
      if (dut.atari.atari800xl.cpu6502.CPU_ENABLE.toBoolean) {
        val pc = dut.atari.atari800xl.cpu6502.debugPc.toInt
        if (!pcPages.contains(pc >> 12)) { pcPages += (pc >> 12); println(f"CPU page ${pc >> 12}%X000 (PC=$pc%04X) cyc=$cycles") }
      }
      val vs = dut.io.videoVs.toBoolean
      if (vs && !lastVs) { vsyncs += 1; println(s"vsync #$vsyncs cyc=$cycles") }
      lastVs = vs
    }
    println(s"done: cycles=$cycles osReqs=$osReads vsyncs=$vsyncs pcPages=${pcPages.map(_.toHexString).mkString(",")}")
  }
}

// Cart-boot variant: OS at flat $D800/$E000 AND an 8K cart at flat $A000,
// with emulated_cartridge_select=1 (CART_MODE_8K). Confirms the flat cart
// read + OS cart-detection works before hardware.
// Run: sbt "atari/runMain atari800.Atari800CartBootSimTb"
object Atari800CartBootSimTb extends App {
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
    .compile(new Atari800ArbBootHarness(cartMode = 1))   // CART_MODE_8K

  compiled.doSim("cart_boot", seed = 42) { dut =>
    val os2 = Files.readAllBytes(Paths.get("roms/atarios2.rom"))
    val osb = Files.readAllBytes(Paths.get("roms/atariosb.rom"))
    val cart = Files.readAllBytes(Paths.get("roms/Star Raiders.rom"))
    for (i <- os2.indices)  dut.mock.mem.setBigInt(0xD800 + i, BigInt(os2(i) & 0xFF))
    for (i <- osb.indices)  dut.mock.mem.setBigInt(0xE000 + i, BigInt(osb(i) & 0xFF))
    for (i <- cart.indices) dut.mock.mem.setBigInt(0xA000 + i, BigInt(cart(i) & 0xFF))
    println(f"preloaded OS + 8K cart; cart vectors BFFA=${cart(0x1FFA) & 0xFF}%02x${cart(0x1FFB) & 0xFF}%02x " +
            f"BFFC=${cart(0x1FFC) & 0xFF}%02x BFFE=${cart(0x1FFE) & 0xFF}%02x${cart(0x1FFF) & 0xFF}%02x")

    dut.clockDomain.forkStimulus(period = 17640)
    dut.clockDomain.waitRisingEdge(10)
    dut.clockDomain.assertReset()
    dut.clockDomain.waitRisingEdge(100)
    dut.clockDomain.deassertReset()

    var vsyncs = 0; var lastVs = false
    var cartReads = 0; var lastReq = false
    var cycles = 0
    val pcPages = scala.collection.mutable.LinkedHashSet[Int]()
    while (cycles < 4000000 && vsyncs < 3) {
      dut.clockDomain.waitRisingEdge(); cycles += 1
      val req = dut.atari.io.SDRAM_REQUEST.toBoolean
      val a = dut.atari.io.SDRAM_ADDR.toInt
      if (req && !lastReq && a >= 0xA000 && a <= 0xBFFF) {
        cartReads += 1
        if (cartReads <= 8) println(f"cart read #$cartReads addr=$a%06x cyc=$cycles")
      }
      lastReq = req
      if (dut.atari.atari800xl.cpu6502.CPU_ENABLE.toBoolean) {
        val pc = dut.atari.atari800xl.cpu6502.debugPc.toInt
        if (!pcPages.contains(pc >> 12)) { pcPages += (pc >> 12); println(f"CPU page ${pc >> 12}%X000 (PC=$pc%04X) cyc=$cycles") }
      }
      val vs = dut.io.videoVs.toBoolean
      if (vs && !lastVs) { vsyncs += 1; println(s"vsync #$vsyncs cyc=$cycles") }
      lastVs = vs
    }
    val ranCart = pcPages.contains(0xA) || pcPages.contains(0xB)
    println(s"done: cycles=$cycles cartReads=$cartReads vsyncs=$vsyncs pcPages=${pcPages.map(_.toHexString).mkString(",")} ranInCart=$ranCart")
  }
}
