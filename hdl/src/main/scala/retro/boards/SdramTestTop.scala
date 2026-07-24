package retro.boards
import retro.common.util._
import retro.common.video._
import retro.common.scaler._
import retro.common.sdram._
import retro.link._
import retro.machines.atari._

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
// BIST-proven chip geometry (W9825G6KH-class: 13-bit rows, 9-bit columns).
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

// Same full-range BIST, but on the MAIN design's exact clock tree: atari_pll
// c0 = 57.69 MHz system (engine + reporter), c1 = 115.38 MHz controller,
// c2 = 115.38 MHz @ -2400 ps driving the chip's clock pin - plus the main
// project's IOE registers and SDC constraints. The 100 MHz test passing while
// the Atari corrupts high rows makes THIS configuration the prime suspect;
// a failure here names the weak address bits directly.
class SdramTest115Top extends Component {
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

  val pll = new AtariPll
  pll.io.areset := False
  pll.io.inclk0 := io.clk_in
  val clkSys   = pll.io.c0        //  57.69 MHz
  val clkCtrl  = pll.io.c1        // 115.38 MHz, 0 deg
  io.sdram_clk := pll.io.c2       // 115.38 MHz, -2400 ps (chip clock pin)

  val sysDomain = ClockDomain(clkSys, config = ClockDomainConfig(resetKind = BOOT))

  val area = new ClockingArea(sysDomain) {
    val porCnt = Reg(UInt(16 bits)) init 0
    when(porCnt =/= porCnt.maxValue) { porCnt := porCnt + 1 }
    val resetN = porCnt.msb

    val ctrl = new SdramStatemachine(
      ADDRESS_WIDTH = 24, ROW_WIDTH = 13, COLUMN_WIDTH = 9, AP_BIT = 10
    )
    ctrl.io.CLK_SYSTEM := clkSys
    ctrl.io.CLK_SDRAM  := clkCtrl
    ctrl.io.RESET_N    := resetN
    ctrl.io.REFRESH    := False

    val bist = new SdramBistEngine(addrWidth = ctrl.io.ADDRESS_IN.getWidth,
                                   sweepWords = BigInt(1) << 19,  // x32 bytes = 16 MB
                                   retWaitBits = 26,   // 1.16 s @ 57.69 MHz
                                   wideMode = true)
    bist.io.ready := ctrl.io.reset_client_n
    ctrl.io.REQUEST         := bist.io.request
    ctrl.io.WRITE_EN        := bist.io.writeEn
    ctrl.io.READ_EN         := bist.io.readEn
    ctrl.io.ADDRESS_IN      := bist.io.addr
    ctrl.io.DATA_IN         := bist.io.dataOut
    ctrl.io.LONGWORD_ACCESS := !bist.io.wideAcc
    ctrl.io.WORD_ACCESS     := False
    ctrl.io.BYTE_ACCESS     := False
    ctrl.io.WIDE_ACCESS     := bist.io.wideAcc
    ctrl.io.WIDE_IN         := bist.io.wideOut
    bist.io.wideIn   := ctrl.io.WIDE_OUT
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

    val hb = Reg(UInt(27 bits)) init 0
    hb := hb + 1
    val done = bist.io.state =/= 0
    val fail = bist.io.state === 2
    io.led_core(0) := Mux(done, Mux(fail, hb(25), hb(23)), False)

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

object SdramTest115TopSv extends App {
  SpinalConfig(mode = SystemVerilog, targetDirectory = "generated")
    .generate(new SdramTest115Top)
}

// BIST through SdramArbiter3 port D (the supervisor loader's port) on the
// main clock tree - ports A/B/C idle. This is the exact topology of a
// quiesced supervisor load, the configuration that still corrupts on the
// Atari build even though the controller-direct BIST passes: if the
// arbiter/controller seam (e.g. serve-into-refresh) drops or mangles
// transactions, THIS fails and names the first bad address.
class SdramTestArbTop extends Component {
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

  val pll = new AtariPll
  pll.io.areset := False
  pll.io.inclk0 := io.clk_in
  io.sdram_clk := pll.io.c2

  val sysDomain = ClockDomain(pll.io.c0, config = ClockDomainConfig(resetKind = BOOT))

  val area = new ClockingArea(sysDomain) {
    val porCnt = Reg(UInt(16 bits)) init 0
    when(porCnt =/= porCnt.maxValue) { porCnt := porCnt + 1 }
    val resetN = porCnt.msb

    val ctrl = new SdramStatemachine(
      ADDRESS_WIDTH = 24, ROW_WIDTH = 13, COLUMN_WIDTH = 9, AP_BIT = 10
    )
    ctrl.io.CLK_SYSTEM := pll.io.c0
    ctrl.io.CLK_SDRAM  := pll.io.c1
    ctrl.io.RESET_N    := resetN
    ctrl.io.REFRESH    := True   // continuous suggest - the main design's cadence

    val arb = new SdramArbiter3
    arb.io.c.request := False; arb.io.c.readEnable := False; arb.io.c.writeEnable := False
    arb.io.c.addr := 0; arb.io.c.dataIn := 0
    arb.io.c.byteAccess := False; arb.io.c.wordAccess := False; arb.io.c.longwordAccess := False
    arb.io.c.wideAccess := False
    arb.io.a.refresh := False

    // ANTIC mimic on port A: HALT pauses only the 6502 - ANTIC DMA runs
    // through every supervisor load, so the real quiesced-load condition is
    // port-D writes interleaved with port-A reads. Pulse-request protocol,
    // one byte read every 64 cycles (~1.1 us at 57.69 MHz - ANTIC-dense).
    val aTick   = Reg(UInt(6 bits)) init 0
    val aBusy   = RegInit(False)
    val aSeen   = RegInit(False)
    val aAddr   = Reg(UInt(11 bits)) init 0
    aTick := aTick + 1
    arb.io.a.request        := False
    arb.io.a.readEnable     := True
    arb.io.a.writeEnable    := False
    arb.io.a.addr           := aAddr.asBits.resized
    arb.io.a.dataIn         := 0
    arb.io.a.byteAccess     := True
    arb.io.a.wordAccess     := False
    arb.io.a.longwordAccess := False
    when(!aBusy && aTick === 0) {
      arb.io.a.request := True
      aBusy := True; aSeen := False
      aAddr := aAddr + 1
    }
    when(aBusy && !arb.io.a.request) {
      when(!arb.io.a.complete) { aSeen := True }
      when(aSeen && arb.io.a.complete) { aBusy := False }
    }

    ctrl.io.REQUEST         := arb.io.sdram.request
    ctrl.io.READ_EN         := arb.io.sdram.readEnable
    ctrl.io.WRITE_EN        := arb.io.sdram.writeEnable
    ctrl.io.ADDRESS_IN      := arb.io.sdram.addr
    ctrl.io.DATA_IN         := arb.io.sdram.dataIn
    ctrl.io.BYTE_ACCESS     := arb.io.sdram.byteAccess
    ctrl.io.WORD_ACCESS     := arb.io.sdram.wordAccess
    ctrl.io.LONGWORD_ACCESS := arb.io.sdram.longwordAccess
    arb.io.sdram.complete := ctrl.io.COMPLETE
    arb.io.sdram.dataOut  := ctrl.io.DATA_OUT
    ctrl.io.WIDE_ACCESS   := arb.io.sdram.wideAccess
    ctrl.io.WIDE_IN       := arb.io.sdram.wideIn
    arb.io.sdram.wideOut  := ctrl.io.WIDE_OUT

    // Arbiter client ports carry 24-bit byte addresses (16 MB): size the
    // walk and sweeps to fit or the test self-aliases at the truncation.
    // WIDE mode on port B: the framebuffer write path's exact transaction mix,
    // under concurrent ANTIC-cadence port-A traffic. (Direct-controller wide
    // BIST passed; the main design corrupts words 1-7 of each group - this
    // testbed isolates the arbiter/interleave/timing difference.)
    arb.io.d.request := False; arb.io.d.readEnable := False; arb.io.d.writeEnable := False
    arb.io.d.addr := 0; arb.io.d.dataIn := 0
    arb.io.d.byteAccess := False; arb.io.d.wordAccess := False; arb.io.d.longwordAccess := False

    val bist = new SdramBistEngine(addrWidth = 24, walkMax = 23,
                                   sweepWords = BigInt(1) << 19, retWaitBits = 26,
                                   wideMode = true)
    bist.io.ready := ctrl.io.reset_client_n
    arb.io.b.request        := bist.io.request
    arb.io.b.readEnable     := bist.io.readEn
    arb.io.b.writeEnable    := bist.io.writeEn
    arb.io.b.addr           := bist.io.addr(23 downto 0)
    arb.io.b.dataIn         := bist.io.dataOut
    arb.io.b.byteAccess     := False
    arb.io.b.wordAccess     := False
    arb.io.b.longwordAccess := !bist.io.wideAcc
    arb.io.b.wideAccess     := bist.io.wideAcc
    arb.io.b.wideIn         := bist.io.wideOut
    bist.io.dataIn := arb.io.b.dataOut
    bist.io.wideIn := arb.io.b.wideOut
    val dBusy = RegInit(False) setWhen (bist.io.request) clearWhen (arb.io.b.complete)
    bist.io.complete := !dBusy || arb.io.b.complete

    val rpt = new BistSpiReporter
    rpt.io.spiSck  := io.rp_sck
    rpt.io.spiMosi := io.rp_mosi
    rpt.io.spiCsN  := io.rp_csn
    io.rp_miso     := rpt.io.spiMiso
    rpt.io.state := bist.io.state; rpt.io.phase := bist.io.phase
    rpt.io.progress := bist.io.progress; rpt.io.errCnt := bist.io.errCnt
    rpt.io.firstAddr := bist.io.firstAddr; rpt.io.firstGot := bist.io.firstGot
    rpt.io.firstExp := bist.io.firstExp; rpt.io.firstPhase := bist.io.firstPhase
    bist.io.restart := rpt.io.restart

    val hb = Reg(UInt(27 bits)) init 0
    hb := hb + 1
    val done = bist.io.state =/= 0
    val fail = bist.io.state === 2
    io.led_core(0) := Mux(done, Mux(fail, hb(25), hb(23)), False)

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

object SdramTestArbTopSv extends App {
  SpinalConfig(mode = SystemVerilog, targetDirectory = "generated")
    .generate(new SdramTestArbTop)
}
