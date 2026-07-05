package atari800

import spinal.core._
import spinal.lib._

// QMTech-proven SDRAM PLL (pll.v): 50 MHz in -> c0 = c1 = 100 MHz, phase 0.
class SdramPll extends BlackBox {
  setDefinitionName("pll")
  val areset = in  Bool()
  val inclk0 = in  Bool()
  val c0     = out Bool()
  val c1     = out Bool()
}

// Standalone FULL-RANGE SDRAM test for the QMTech 10CL025 (RP2040-HDMI board).
//
// The original Stage-0 test only covered the first 32 KB - which let a
// full-range addressing fault hide for weeks. This version runs SdramBistEngine
// (address-bit walk + two full 32 MB sweeps with address-unique data + a
// refresh-retention pass) against OUR SdramStatemachine, configured with the
// QMTech Test04 reference geometry (13-bit rows, 10-bit columns, 2 bank bits).
//
// Reporting:
//   led_core: steady = running, fast blink (~6 Hz) = PASS, slow (~1.5 Hz) = FAIL
//   RP2040 SPI (rp_sck/mosi/miso/csn): any frame returns the status block
//   (see BistSpiReporter); supervisor 'm' polls it, 0xA0 restarts.
class SdramTestTop extends Component {
  val io = new Bundle {
    val clk_in     = in  Bool()
    val sdram_clk  = out Bool()
    val sdram_cke  = out Bool()
    val sdram_csn  = out Bool()
    val sdram_rasn = out Bool()
    val sdram_casn = out Bool()
    val sdram_wen  = out Bool()
    val sdram_ba   = out Bits(2 bits)
    val sdram_addr = out Bits(13 bits)
    val sdram_dqm  = out Bits(2 bits)
    val sdram_dq   = inout(Analog(Bits(16 bits)))
    val rp_sck     = in  Bool()
    val rp_mosi    = in  Bool()
    val rp_csn     = in  Bool()
    val rp_miso    = out Bool()
    val led_core   = out Bits(1 bits)
  }
  noIoPrefix()

  val pll = new SdramPll
  pll.areset := False
  pll.inclk0 := io.clk_in
  val clkSys   = pll.c0
  val clkSdram = pll.c1
  io.sdram_clk := clkSdram

  val sysDomain = ClockDomain(clkSys, config = ClockDomainConfig(resetKind = BOOT))

  val area = new ClockingArea(sysDomain) {
    // Power-on reset: hold controller reset low ~330 us for SDRAM power-up.
    val porCnt = Reg(UInt(16 bits)) init 0
    when(porCnt =/= porCnt.maxValue) { porCnt := porCnt + 1 }
    val resetN = porCnt.msb

    // BIST run 1 (10-bit columns) failed instantly: write 0x400 clobbered 0
    // - the chip ignores column A9, so it's a 9-bit-column (32 MB) part and
    // the QMTech Test04 COLSIZE=10 does not describe this module.
    val ctrl = new SdramStatemachine(
      ADDRESS_WIDTH = 24, ROW_WIDTH = 13, COLUMN_WIDTH = 9, AP_BIT = 10
    )
    ctrl.io.CLK_SYSTEM := clkSys
    ctrl.io.CLK_SDRAM  := clkSdram
    ctrl.io.RESET_N    := resetN
    ctrl.io.REFRESH    := False   // SdramStatemachine auto-refreshes internally

    val bist = new SdramBistEngine(addrWidth = ctrl.io.ADDRESS_IN.getWidth)
    bist.io.ready := ctrl.io.reset_client_n
    ctrl.io.REQUEST         := bist.io.request
    ctrl.io.WRITE_EN        := bist.io.writeEn
    ctrl.io.READ_EN         := bist.io.readEn
    ctrl.io.ADDRESS_IN      := bist.io.addr
    ctrl.io.DATA_IN         := bist.io.dataOut
    ctrl.io.LONGWORD_ACCESS := True
    ctrl.io.WORD_ACCESS     := False
    ctrl.io.BYTE_ACCESS     := False
    bist.io.dataIn   := ctrl.io.DATA_OUT
    bist.io.complete := ctrl.io.COMPLETE

    val rpt = new BistSpiReporter
    rpt.io.spiSck  := io.rp_sck
    rpt.io.spiMosi := io.rp_mosi
    rpt.io.spiCsN  := io.rp_csn
    io.rp_miso     := rpt.io.spiMiso
    rpt.io.state      := bist.io.state
    rpt.io.phase      := bist.io.phase
    rpt.io.progress   := bist.io.progress
    rpt.io.errCnt     := bist.io.errCnt
    rpt.io.firstAddr  := bist.io.firstAddr
    rpt.io.firstGot   := bist.io.firstGot
    rpt.io.firstExp   := bist.io.firstExp
    rpt.io.firstPhase := bist.io.firstPhase
    bist.io.restart   := rpt.io.restart

    // led_core: steady = running, fast = pass, slow = fail
    val hb = Reg(UInt(27 bits)) init 0
    hb := hb + 1
    val done = bist.io.state =/= 0
    val fail = bist.io.state === 2
    io.led_core(0) := Mux(done, Mux(fail, hb(25), hb(23)), False)

    // SDRAM pin wiring
    io.sdram_addr := ctrl.io.SDRAM_ADDR
    io.sdram_ba   := ctrl.io.SDRAM_BA1 ## ctrl.io.SDRAM_BA0
    io.sdram_cke  := ctrl.io.SDRAM_CKE
    io.sdram_csn  := ctrl.io.SDRAM_CS_N
    io.sdram_rasn := ctrl.io.SDRAM_RAS_N
    io.sdram_casn := ctrl.io.SDRAM_CAS_N
    io.sdram_wen  := ctrl.io.SDRAM_WE_N
    io.sdram_dqm  := ctrl.io.SDRAM_udqm ## ctrl.io.SDRAM_ldqm

    ctrl.io.SDRAM_DQ_IN := io.sdram_dq
    when(ctrl.io.SDRAM_DQ_OE) { io.sdram_dq := ctrl.io.SDRAM_DQ_OUT }
  }
}

object SdramTestTopSv extends App {
  SpinalConfig(mode = SystemVerilog, targetDirectory = "generated")
    .generate(new SdramTestTop)
}
