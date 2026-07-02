package atari800

import spinal.core._
import spinal.lib._
import spinal.lib.fsm._

// QMTech-proven SDRAM PLL (pll.v): 50 MHz in -> c0 = c1 = 100 MHz, phase 0.
class SdramPll extends BlackBox {
  setDefinitionName("pll")
  val areset = in  Bool()
  val inclk0 = in  Bool()
  val c0     = out Bool()
  val c1     = out Bool()
}

// Standalone SDRAM bring-up test for the QMTech 10CL025 (RP2040-HDMI board).
//
// Exercises OUR SdramStatemachine controller (the block Stages 1-2 reuse) against
// the on-module SDRAM at 100 MHz — the vendor-proven QMTech clocking. It writes a
// unique pattern to a range of word addresses, reads them all back, and reports on
// led_core (PIN_N9) by BLINK RATE (rate is polarity-independent):
//   fast blink (~6 Hz)   = PASS  (every word read back correct)
//   slow blink (~1.5 Hz) = FAIL  (at least one mismatch)
//   steady               = still running / stuck before reset_client_n
//
// Self-contained and non-destructive to the rest of the system — safe to reload
// any time to re-verify SDRAM.
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
    val resetN = porCnt.msb   // low for first 32768 cycles

    val ctrl = new SdramStatemachine(
      ADDRESS_WIDTH = 24, ROW_WIDTH = 13, COLUMN_WIDTH = 9, AP_BIT = 10
    )
    ctrl.io.CLK_SYSTEM := clkSys
    ctrl.io.CLK_SDRAM  := clkSdram
    ctrl.io.RESET_N    := resetN
    ctrl.io.REFRESH    := False   // SdramStatemachine auto-refreshes internally

    val N        = 16384                        // words to test
    val addr     = Reg(UInt(15 bits)) init 0
    val fail     = Reg(Bool()) init False
    val done     = Reg(Bool()) init False
    val busySeen = Reg(Bool()) init False

    def pattern(i: UInt): Bits = (i.asBits.resize(16) ^ B"16'x5A5A")

    ctrl.io.WORD_ACCESS     := True
    ctrl.io.BYTE_ACCESS     := False
    ctrl.io.LONGWORD_ACCESS := False
    ctrl.io.ADDRESS_IN      := (addr << 1).asBits.resize(ctrl.io.ADDRESS_IN.getWidth)
    ctrl.io.DATA_IN         := pattern(addr).resize(32)

    val fsm = new StateMachine {
      val Init      = new State with EntryPoint
      val WrIssue   = new State
      val WrWait    = new State
      val RdIssue   = new State
      val RdWait    = new State
      val Done      = new State

      // REQUEST is a 1-cycle pulse (Issue states are active exactly one cycle).
      ctrl.io.REQUEST  := isActive(WrIssue) || isActive(RdIssue)
      ctrl.io.WRITE_EN := isActive(WrIssue) || isActive(WrWait)
      ctrl.io.READ_EN  := isActive(RdIssue) || isActive(RdWait)

      Init.whenIsActive {
        when(resetN && ctrl.io.reset_client_n) { addr := 0; goto(WrIssue) }
      }
      WrIssue.whenIsActive { busySeen := False; goto(WrWait) }
      WrWait.whenIsActive {
        when(!ctrl.io.COMPLETE) { busySeen := True }
        when(busySeen && ctrl.io.COMPLETE) {
          when(addr === (N - 1)) { addr := 0; goto(RdIssue) }
            .otherwise           { addr := addr + 1; goto(WrIssue) }
        }
      }
      RdIssue.whenIsActive { busySeen := False; goto(RdWait) }
      RdWait.whenIsActive {
        when(!ctrl.io.COMPLETE) { busySeen := True }
        when(busySeen && ctrl.io.COMPLETE) {
          when(ctrl.io.DATA_OUT(15 downto 0) =/= pattern(addr)) { fail := True }
          when(addr === (N - 1)) { goto(Done) }
            .otherwise           { addr := addr + 1; goto(RdIssue) }
        }
      }
      Done.whenIsActive { done := True }
    }

    // Heartbeat / result on led_core.
    val hb = Reg(UInt(27 bits)) init 0
    hb := hb + 1
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

    // Bidirectional DQ
    ctrl.io.SDRAM_DQ_IN := io.sdram_dq
    when(ctrl.io.SDRAM_DQ_OE) { io.sdram_dq := ctrl.io.SDRAM_DQ_OUT }
  }
}

object SdramTestTopSv extends App {
  SpinalConfig(mode = SystemVerilog, targetDirectory = "generated")
    .generate(new SdramTestTop)
}
