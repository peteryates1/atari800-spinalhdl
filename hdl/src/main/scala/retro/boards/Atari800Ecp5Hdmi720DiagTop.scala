package retro.boards
import retro.common.util._
import retro.common.video._
import retro.common.scaler._
import retro.common.sdram._
import retro.link._
import retro.machines.atari._

import spinal.core._
import spinal.lib._

// Diagnostic top: is the Atari core actually alive on the i5? Uses the PROVEN 720p path
// (ClkGen720 + Hdmi720Bars timing + Ecp5DvidOutX2) but paints the whole active screen from
// two crossed core-liveness signals instead of the scaler buffer:
//   * PLL not locked            -> static RED
//   * locked + VIDEO_VS toggling -> blink WHITE/BLUE (~0.5 s: heartbeat = VIDEO_VS/32)
//   * locked + VIDEO_VS frozen   -> static WHITE or BLUE (core stuck / no video)
// So: blinking => PLL+core both alive (bug is in the scaler/genlock); static => core frozen.
class Atari800Ecp5Hdmi720DiagTop extends Component {
  val io = new Bundle {
    val clk_25mhz = in  Bool()
    val gpdi      = out Bits(4 bits)
  }
  noIoPrefix()

  val pllA = new PllEcp5
  pllA.io.inclk0 := io.clk_25mhz
  val clkSys = pllA.io.c0

  val cg = new ClkGen720
  cg.clk25 := io.clk_25mhz

  val sysDomain = ClockDomain(clkSys, reset = pllA.io.locked,
    config = ClockDomainConfig(clockEdge = RISING, resetKind = ASYNC, resetActiveLevel = LOW))

  val sysArea = new ClockingArea(sysDomain) {
    val colourEnable = Reg(Bool()) init False
    colourEnable := ~colourEnable

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
    atari.io.SDRAM_REQUEST_COMPLETE := atari.io.SDRAM_REQUEST; atari.io.SDRAM_DO := B(0, 32 bits)

    val vsRise  = atari.io.VIDEO_VS && !RegNext(atari.io.VIDEO_VS).init(False)
    val vsCount = Reg(UInt(6 bits)) init 0
    when(vsRise) { vsCount := vsCount + 1 }
    val hbBit = vsCount(5)     // toggles every 32 frames (~0.5 s @ 60 Hz)

    // Sticky OR of every colour index the core emits: non-zero => the core IS producing
    // non-black video (isolates "core boots blank" vs "scaler bug"). Stabilises, no blink.
    val seen = Reg(Bits(8 bits)) init 0
    seen := seen | atari.io.VIDEO_R | atari.io.VIDEO_G | atari.io.VIDEO_B

    // Sticky: did the CPU ever write a non-zero background colour (COLBK)? => running OS code.
    val colbkSeen = Reg(Bool()) init False
    when(atari.io.dbgColbk =/= 0) { colbkSeen := True }
    // Sticky: did ANTIC ever fetch non-zero playfield data (AN bus)? => a display list is live.
    val anSeen = Reg(Bool()) init False
    when(atari.io.dbgAN =/= 0) { anSeen := True }
  }

  val pixCd   = ClockDomain(cg.pixel, config = ClockDomainConfig(resetKind = BOOT))
  val pixArea = new ClockingArea(pixCd) {
    val gen    = new Hdmi720Bars        // proven 720p timing
    val locked = BufferCC(pllA.io.locked, False)
    val vSeen = BufferCC(sysArea.seen, B(0, 8 bits)) =/= 0   // video ever non-black
    val cb    = BufferCC(sysArea.colbkSeen, False)           // CPU wrote non-zero COLBK
    val an    = BufferCC(sysArea.anSeen, False)              // ANTIC fetched non-zero playfield

    // Flat field, one bit per channel (all sticky, no blink):
    //   RED   = CPU wrote a non-zero background colour (running OS/game code)
    //   GREEN = ANTIC fetched non-zero playfield (a display list is live)
    //   BLUE  = final video ever non-black
    // white = fully working; red-only = CPU runs but no display list; black = CPU dead.
    val de = RegNext(gen.io.de) init False
    val r  = RegNext(Mux(cb,    B(0xFF, 8 bits), B(0, 8 bits))) init 0
    val g  = RegNext(Mux(an,    B(0xFF, 8 bits), B(0, 8 bits))) init 0
    val b  = RegNext(Mux(vSeen, B(0xFF, 8 bits), B(0, 8 bits))) init 0
  }

  val ser = new Ecp5DvidOutX2
  ser.io.clkPixel := cg.pixel
  ser.io.clkSclk  := cg.sclk
  ser.io.red   := Mux(pixArea.de, pixArea.r, B(0, 8 bits))
  ser.io.green := Mux(pixArea.de, pixArea.g, B(0, 8 bits))
  ser.io.blue  := Mux(pixArea.de, pixArea.b, B(0, 8 bits))
  ser.io.hsync := pixArea.gen.io.hs
  ser.io.vsync := pixArea.gen.io.vs
  ser.io.de    := pixArea.gen.io.de

  val oP = new Oddrx2x4
  oP.nib := ser.io.nibbles; oP.eclk := cg.eclk; oP.sclk := cg.sclk
  io.gpdi := oP.q
}

object Atari800Ecp5Hdmi720DiagSv extends App {
  SpinalConfig(mode = SystemVerilog, targetDirectory = "generated")
    .generate(new Atari800Ecp5Hdmi720DiagTop)
}
