package atari800

import spinal.core._
import spinal.lib._

// SDRAM physical pin bundle (directly to chip)
// SDRAM control signals (no inout — DQ handled separately at top level)
case class SdramCtrlPins() extends Bundle with IMasterSlave {
  val addr  = Bits(13 bits)
  val ba    = Bits(2 bits)
  val cs_n  = Bool()
  val ras_n = Bool()
  val cas_n = Bool()
  val we_n  = Bool()
  val clk   = Bool()
  val cke   = Bool()
  val dqml  = Bool()
  val dqmh  = Bool()

  override def asMaster(): Unit = {
    out(addr, ba, cs_n, ras_n, cas_n, we_n, clk, cke, dqml, dqmh)
  }
}

// VGA output bundle (accent R/G/B DAC)
case class VgaPins(colorBits: Int = 4) extends Bundle with IMasterSlave {
  val r     = Bits(colorBits bits)
  val g     = Bits(colorBits bits)
  val b     = Bits(colorBits bits)
  val hsync = Bool()
  val vsync = Bool()

  override def asMaster(): Unit = out(r, g, b, hsync, vsync)
}

// Joystick port (active low: fire, right, left, down, up)
case class JoystickPins() extends Bundle {
  val up_n    = in Bool()
  val down_n  = in Bool()
  val left_n  = in Bool()
  val right_n = in Bool()
  val fire_n  = in Bool()

  // Pack as 5-bit vector: FRLDU
  def packed: Bits = fire_n ## right_n ## left_n ## down_n ## up_n
}

// Physical cartridge port
case class CartridgePins() extends Bundle with IMasterSlave {
  val addr   = Bits(13 bits)
  val data   = in Bits(8 bits)
  val s4_n   = Bool()
  val s5_n   = Bool()
  val cctl_n = Bool()
  val rd4    = in Bool()
  val rd5    = in Bool()
  val phi2   = Bool()

  override def asMaster(): Unit = {
    out(addr, s4_n, s5_n, cctl_n, phi2)
  }
}

// Console buttons (active low)
case class ConsolePins() extends Bundle {
  val start_n  = in Bool()
  val select_n = in Bool()
  val option_n = in Bool()
  val reset_n  = in Bool()
}

// PS/2 keyboard
case class Ps2Pins() extends Bundle {
  val clk = in Bool()
  val dat = in Bool()
}

// Pico companion bus (physical GPIO)
case class PicoBusPins() extends Bundle {
  val data   = inout(Analog(Bits(8 bits)))
  val addr   = in Bits(3 bits)
  val wr_n   = in Bool()
  val rd_n   = in Bool()
  val irq_n  = out Bool()
}

// SDRAM controller interface (accent accent accent accent accent accent accent accent accent accent accent accent accent accent accent accent accent accent accent accent accent between core and controller)
case class SdramRequest() extends Bundle {
  val request          = Bool()
  val requestComplete  = Bool()
  val readEnable       = Bool()
  val writeEnable      = Bool()
  val addr             = Bits(23 bits)
  val dataOut          = Bits(32 bits)  // data from SDRAM
  val dataIn           = Bits(32 bits)  // data to SDRAM
  val write32          = Bool()
  val write16          = Bool()
  val write8           = Bool()
  val refresh          = Bool()
}

// DMA interface (accent Pico → core → SDRAM)
case class DmaRequest() extends Bundle {
  val fetch      = Bool()
  val readEnable = Bool()
  val write32    = Bool()
  val write16    = Bool()
  val write8     = Bool()
  val addr       = Bits(24 bits)
  val writeData  = Bits(32 bits)
  val memReady   = Bool()
  val memData    = Bits(32 bits)
}
