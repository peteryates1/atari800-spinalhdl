package atari800

import spinal.core._
import jop.io.HasBusIo

// JOP I/O peripheral for controlling the Atari 800 core.
// Replaces PicoBus functionality: config registers, paddle injection,
// keyboard matrix, joystick overrides, OSD control, cold reset.
//
// Uses the same simple register interface as VgaText (addr/rd/wr/data).
// JopCore's I/O decoder routes accesses to this peripheral.
//
// Register map (4-bit address, 32-bit data):
//   0x0  R: status — bit0=osdEnabled, bit1=pllLocked
//        W: control — bit0=osdEnable, bit7=coldReset (pulse)
//   0x1  R/W: cartSelect[5:0]
//   0x2  R/W: config — bit0=PAL, bit[3:1]=ramSelect, bit4=turboVblankOnly,
//                       bit5=atari800mode, bit6=hiresEna
//   0x3  R/W: paddle0 (signed 8-bit)
//   0x4  R/W: paddle1
//   0x5  R/W: paddle2
//   0x6  R/W: paddle3
//   0x7  R/W: joy1_n[4:0] (active low: fire, right, left, down, up)
//   0x8  R/W: joy2_n[4:0]
//   0x9  R/W: joy3_n[4:0]
//   0xA  R/W: joy4_n[4:0]
//   0xB  R/W: keyboard response[1:0]
//   0xC  R/W: throttle count[5:0]
class AtariCtrl extends Component with HasBusIo {
  val bus = new Bundle {
    val addr   = in  UInt(4 bits)
    val rd     = in  Bool()
    val wr     = in  Bool()
    val wrData = in  Bits(32 bits)
    val rdData = out Bits(32 bits)
  }

  val io = new Bundle {
    // Atari core control outputs
    val osdEnable              = out Bool()
    val coldReset              = out Bool()
    val cartSelect             = out Bits(6 bits)
    val pal                    = out Bool()
    val ramSelect              = out Bits(3 bits)
    val turboVblankOnly        = out Bool()
    val atari800mode           = out Bool()
    val hiresEna               = out Bool()
    val throttleCount          = out Bits(6 bits)

    // Paddle outputs (to Atari pot counters)
    val paddle0 = out SInt(8 bits)
    val paddle1 = out SInt(8 bits)
    val paddle2 = out SInt(8 bits)
    val paddle3 = out SInt(8 bits)

    // Joystick outputs (active low, directly to Atari core)
    val joy1_n = out Bits(5 bits)
    val joy2_n = out Bits(5 bits)
    val joy3_n = out Bits(5 bits)
    val joy4_n = out Bits(5 bits)

    // Keyboard
    val keyboardResponse = out Bits(2 bits)

    // Status inputs
    val pllLocked = in Bool()
  }

  // Registers with defaults
  val osdEnableReg   = RegInit(True)                       // OSD on at boot
  val coldResetReg   = RegInit(False)                      // pulse
  val cartSelectReg  = Reg(Bits(6 bits)) init 0            // no cartridge
  val configReg      = Reg(Bits(8 bits)) init B"00100001"  // PAL, 64K RAM, atari800mode
  val throttleReg    = Reg(Bits(6 bits)) init B"011111"    // 31

  val paddle0Reg = Reg(SInt(8 bits)) init 0
  val paddle1Reg = Reg(SInt(8 bits)) init 0
  val paddle2Reg = Reg(SInt(8 bits)) init 0
  val paddle3Reg = Reg(SInt(8 bits)) init 0

  val joy1Reg = Reg(Bits(5 bits)) init B"11111"  // all released
  val joy2Reg = Reg(Bits(5 bits)) init B"11111"
  val joy3Reg = Reg(Bits(5 bits)) init B"11111"
  val joy4Reg = Reg(Bits(5 bits)) init B"11111"

  val kbResponseReg = Reg(Bits(2 bits)) init B"11"         // no key pressed

  // Cold reset is a pulse — clear after one cycle
  coldResetReg := False

  // Write handling
  when(bus.wr) {
    switch(bus.addr) {
      is(0x0) {
        osdEnableReg := bus.wrData(0)
        when(bus.wrData(7)) { coldResetReg := True }
      }
      is(0x1) { cartSelectReg := bus.wrData(5 downto 0) }
      is(0x2) { configReg     := bus.wrData(7 downto 0) }
      is(0x3) { paddle0Reg    := bus.wrData(7 downto 0).asSInt }
      is(0x4) { paddle1Reg    := bus.wrData(7 downto 0).asSInt }
      is(0x5) { paddle2Reg    := bus.wrData(7 downto 0).asSInt }
      is(0x6) { paddle3Reg    := bus.wrData(7 downto 0).asSInt }
      is(0x7) { joy1Reg       := bus.wrData(4 downto 0) }
      is(0x8) { joy2Reg       := bus.wrData(4 downto 0) }
      is(0x9) { joy3Reg       := bus.wrData(4 downto 0) }
      is(0xA) { joy4Reg       := bus.wrData(4 downto 0) }
      is(0xB) { kbResponseReg := bus.wrData(1 downto 0) }
      is(0xC) { throttleReg   := bus.wrData(5 downto 0) }
    }
  }

  // Read handling
  bus.rdData := B(0, 32 bits)
  switch(bus.addr) {
    is(0x0) { bus.rdData := B(0, 30 bits) ## io.pllLocked ## osdEnableReg }
    is(0x1) { bus.rdData := B(0, 26 bits) ## cartSelectReg }
    is(0x2) { bus.rdData := B(0, 24 bits) ## configReg }
    is(0x3) { bus.rdData := B(0, 24 bits) ## paddle0Reg.asBits }
    is(0x4) { bus.rdData := B(0, 24 bits) ## paddle1Reg.asBits }
    is(0x5) { bus.rdData := B(0, 24 bits) ## paddle2Reg.asBits }
    is(0x6) { bus.rdData := B(0, 24 bits) ## paddle3Reg.asBits }
    is(0x7) { bus.rdData := B(0, 27 bits) ## joy1Reg }
    is(0x8) { bus.rdData := B(0, 27 bits) ## joy2Reg }
    is(0x9) { bus.rdData := B(0, 27 bits) ## joy3Reg }
    is(0xA) { bus.rdData := B(0, 27 bits) ## joy4Reg }
    is(0xB) { bus.rdData := B(0, 30 bits) ## kbResponseReg }
    is(0xC) { bus.rdData := B(0, 26 bits) ## throttleReg }
  }

  // Output assignments
  io.osdEnable       := osdEnableReg
  io.coldReset       := coldResetReg
  io.cartSelect      := cartSelectReg
  io.pal             := configReg(0)
  io.ramSelect       := configReg(3 downto 1)
  io.turboVblankOnly := configReg(4)
  io.atari800mode    := configReg(5)
  io.hiresEna        := configReg(6)
  io.throttleCount   := throttleReg

  io.paddle0 := paddle0Reg
  io.paddle1 := paddle1Reg
  io.paddle2 := paddle2Reg
  io.paddle3 := paddle3Reg

  io.joy1_n := joy1Reg
  io.joy2_n := joy2Reg
  io.joy3_n := joy3Reg
  io.joy4_n := joy4Reg

  io.keyboardResponse := kbResponseReg

  // HasBusIo implementation
  override def busAddr: UInt   = bus.addr
  override def busRd: Bool     = bus.rd
  override def busWr: Bool     = bus.wr
  override def busWrData: Bits = bus.wrData
  override def busRdData: Bits = bus.rdData
  override def busExternalIo: Option[Bundle] = Some(io)
}
