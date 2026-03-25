package atari800

import spinal.core._
import jop.io.HasBusIo

// JOP I/O peripheral for controlling the Atari 800 core.
// Replaces PicoBus functionality: config registers, paddle injection,
// keyboard matrix, joystick overrides, OSD control, cold reset,
// physical cartridge slot access.
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
//   0x3  R/W: paddle0[7:0] | paddle1[15:8]   (port 1, signed 8-bit each)
//   0x4  R/W: paddle2[7:0] | paddle3[15:8]   (port 2)
//   0x5  R/W: paddle4[7:0] | paddle5[15:8]   (port 3)
//   0x6  R/W: paddle6[7:0] | paddle7[15:8]   (port 4)
//   0x7  R/W: joy1_n[4:0] | joy2_n[12:8]     (active low: fire,right,left,down,up)
//   0x8  R/W: joy3_n[4:0] | joy4_n[12:8]
//   0x9  R/W: console[4:2]={start,select,option}, throttle[13:8]
//   0xA  W: cart slot addr[12:0], bit13=s5_n, bit14=s4_n, bit15=cctl_n
//        R: same (readback)
//   0xB  R: cart slot data[7:0], bit8=rd4, bit9=rd5
//   0xC  R/W: keyboard — [5:0]=scanCode, [8]=pressed, [9]=shift, [10]=ctrl, [11]=break
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
    val paddle4 = out SInt(8 bits)
    val paddle5 = out SInt(8 bits)
    val paddle6 = out SInt(8 bits)
    val paddle7 = out SInt(8 bits)

    // Joystick outputs (active low, directly to Atari core)
    val joy1_n = out Bits(5 bits)
    val joy2_n = out Bits(5 bits)
    val joy3_n = out Bits(5 bits)
    val joy4_n = out Bits(5 bits)

    // Keyboard — hardware-generated response from scan + key register
    val keyboardScan     = in  Bits(6 bits)
    val keyboardResponse = out Bits(2 bits)

    // Key state outputs (slow-changing, safe for CDC to Atari domain)
    val keyScanCode = out Bits(6 bits)
    val keyPressed  = out Bool()
    val keyShift    = out Bool()
    val keyCtrl     = out Bool()
    val keyBreak    = out Bool()

    // Console keys (active high: 1 = pressed)
    val consolOption = out Bool()
    val consolSelect = out Bool()
    val consolStart  = out Bool()

    // Physical cartridge slot
    val cartSlotAddr  = out Bits(13 bits)
    val cartSlotS4N   = out Bool()
    val cartSlotS5N   = out Bool()
    val cartSlotCctlN = out Bool()
    val cartSlotData  = in  Bits(8 bits)
    val cartSlotRd4   = in  Bool()
    val cartSlotRd5   = in  Bool()

    // Status inputs
    val pllLocked = in Bool()
  }

  // Registers with defaults
  val osdEnableReg   = RegInit(True)                       // OSD on at boot
  val coldResetReg   = RegInit(False)                      // pulse
  val cartSelectReg  = Reg(Bits(6 bits)) init 0            // no cartridge
  val configReg      = Reg(Bits(8 bits)) init B"01100111"  // PAL, ramSelect=011 (48K), atari800mode, hiresEna — matches AtariSupervisor

  // Paddle pairs packed: [15:8]=odd, [7:0]=even
  val paddlePair0Reg = Reg(Bits(16 bits)) init 0  // paddle0, paddle1
  val paddlePair1Reg = Reg(Bits(16 bits)) init 0  // paddle2, paddle3
  val paddlePair2Reg = Reg(Bits(16 bits)) init 0  // paddle4, paddle5
  val paddlePair3Reg = Reg(Bits(16 bits)) init 0  // paddle6, paddle7

  // Joystick pairs packed: [12:8]=joy_odd, [4:0]=joy_even
  val joyPair0Reg = Reg(Bits(16 bits)) init B(0x1F1F, 16 bits)  // joy1, joy2
  val joyPair1Reg = Reg(Bits(16 bits)) init B(0x1F1F, 16 bits)  // joy3, joy4

  // Console + throttle packed: [13:8]=throttle, [4]=start, [3]=select, [2]=option
  val consolThrottleReg = Reg(Bits(16 bits)) init B(0x1F00, 16 bits) // throttle=31, console not pressed

  // Keyboard state: [5:0]=scanCode, [8]=pressed, [9]=shift, [10]=ctrl, [11]=break
  val keyReg = Reg(Bits(16 bits)) init 0

  // Cart slot: addr[12:0], s5_n, s4_n, cctl_n packed into 16 bits
  val cartSlotReg = Reg(Bits(16 bits)) init B(0xE000, 16 bits) // all selects high (deasserted)

  // Cold reset is a pulse — clear after one cycle
  coldResetReg := False

  // Write handling
  when(bus.wr) {
    switch(bus.addr) {
      is(0x0) {
        osdEnableReg := bus.wrData(0)
        when(bus.wrData(7)) { coldResetReg := True }
      }
      is(0x1) { cartSelectReg  := bus.wrData(5 downto 0) }
      is(0x2) { configReg      := bus.wrData(7 downto 0) }
      is(0x3) { paddlePair0Reg := bus.wrData(15 downto 0) }
      is(0x4) { paddlePair1Reg := bus.wrData(15 downto 0) }
      is(0x5) { paddlePair2Reg := bus.wrData(15 downto 0) }
      is(0x6) { paddlePair3Reg := bus.wrData(15 downto 0) }
      is(0x7) { joyPair0Reg    := bus.wrData(15 downto 0) }
      is(0x8) { joyPair1Reg    := bus.wrData(15 downto 0) }
      is(0x9) { consolThrottleReg := bus.wrData(15 downto 0) }
      is(0xA) { cartSlotReg      := bus.wrData(15 downto 0) }
      is(0xC) { keyReg           := bus.wrData(15 downto 0) }
    }
  }

  // Read handling
  bus.rdData := B(0, 32 bits)
  switch(bus.addr) {
    is(0x0) { bus.rdData := B(0, 30 bits) ## io.pllLocked ## osdEnableReg }
    is(0x1) { bus.rdData := B(0, 26 bits) ## cartSelectReg }
    is(0x2) { bus.rdData := B(0, 24 bits) ## configReg }
    is(0x3) { bus.rdData := B(0, 16 bits) ## paddlePair0Reg }
    is(0x4) { bus.rdData := B(0, 16 bits) ## paddlePair1Reg }
    is(0x5) { bus.rdData := B(0, 16 bits) ## paddlePair2Reg }
    is(0x6) { bus.rdData := B(0, 16 bits) ## paddlePair3Reg }
    is(0x7) { bus.rdData := B(0, 16 bits) ## joyPair0Reg }
    is(0x8) { bus.rdData := B(0, 16 bits) ## joyPair1Reg }
    is(0x9) { bus.rdData := B(0, 16 bits) ## consolThrottleReg }
    is(0xA) { bus.rdData := B(0, 16 bits) ## cartSlotReg }
    is(0xB) { bus.rdData := B(0, 22 bits) ## io.cartSlotRd5 ## io.cartSlotRd4 ## io.cartSlotData }
    is(0xC) { bus.rdData := B(0, 16 bits) ## keyReg }
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
  io.throttleCount   := consolThrottleReg(13 downto 8)

  // Console keys from reg 0x9 (active high: 1 = pressed)
  io.consolOption := consolThrottleReg(2)
  io.consolSelect := consolThrottleReg(3)
  io.consolStart  := consolThrottleReg(4)

  // Unpack paddle pairs
  io.paddle0 := paddlePair0Reg( 7 downto 0).asSInt
  io.paddle1 := paddlePair0Reg(15 downto 8).asSInt
  io.paddle2 := paddlePair1Reg( 7 downto 0).asSInt
  io.paddle3 := paddlePair1Reg(15 downto 8).asSInt
  io.paddle4 := paddlePair2Reg( 7 downto 0).asSInt
  io.paddle5 := paddlePair2Reg(15 downto 8).asSInt
  io.paddle6 := paddlePair3Reg( 7 downto 0).asSInt
  io.paddle7 := paddlePair3Reg(15 downto 8).asSInt

  // Unpack joystick pairs
  io.joy1_n := joyPair0Reg( 4 downto 0)
  io.joy2_n := joyPair0Reg(12 downto 8)
  io.joy3_n := joyPair1Reg( 4 downto 0)
  io.joy4_n := joyPair1Reg(12 downto 8)

  // Hardware keyboard response — matches VHDL ps2_to_atari800 pattern.
  // POKEY scans keyboard_scan[5:0] = ~bincnt at ~15 kHz.
  // KR1 (bit 0): main key match (active low: 0 = key pressed at this scan position).
  // KR2 (bit 1): modifier match on keyboard_scan[5:4] (active low).
  val keyResponse = B"11"  // default: nothing pressed
  when(keyReg(8) && (~io.keyboardScan).asUInt === keyReg(5 downto 0).asUInt) {
    keyResponse(0) := False
  }
  when(keyReg(11) && io.keyboardScan(5 downto 4) === B"00") {
    keyResponse(1) := False  // break
  }
  when(keyReg(9) && io.keyboardScan(5 downto 4) === B"10") {
    keyResponse(1) := False  // shift
  }
  when(keyReg(10) && io.keyboardScan(5 downto 4) === B"11") {
    keyResponse(1) := False  // control
  }
  io.keyboardResponse := keyResponse

  // Key state outputs for CDC (these change only on JOP writes, very slow)
  io.keyScanCode := keyReg(5 downto 0)
  io.keyPressed  := keyReg(8)
  io.keyShift    := keyReg(9)
  io.keyCtrl     := keyReg(10)
  io.keyBreak    := keyReg(11)

  // Cart slot outputs from packed register
  io.cartSlotAddr  := cartSlotReg(12 downto 0)
  io.cartSlotS5N   := cartSlotReg(13)
  io.cartSlotS4N   := cartSlotReg(14)
  io.cartSlotCctlN := cartSlotReg(15)

  // HasBusIo implementation
  override def busAddr: UInt   = bus.addr
  override def busRd: Bool     = bus.rd
  override def busWr: Bool     = bus.wr
  override def busWrData: Bits = bus.wrData
  override def busRdData: Bits = bus.rdData
  override def busExternalIo: Option[Bundle] = Some(io)
}
