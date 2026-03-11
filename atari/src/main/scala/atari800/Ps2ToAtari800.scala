package atari800

import spinal.core._

class Ps2ToAtari800(
  ps2_enable    : Int = 1,
  direct_enable : Int = 0
) extends Component {
  val io = new Bundle {
    val PS2_CLK           = in  Bool()
    val PS2_DAT           = in  Bool()
    val INPUT             = in  Bits(32 bits)
    val ATARI_KEYBOARD_OUT = out Bits(64 bits)

    val KEY_TYPE          = in  Bool() // 0=ISO, 1=ANSI

    val KEYBOARD_SCAN     = in  Bits(6 bits)
    val KEYBOARD_RESPONSE = out Bits(2 bits)

    val CONSOL_START      = out Bool()
    val CONSOL_SELECT     = out Bool()
    val CONSOL_OPTION     = out Bool()

    val FKEYS             = out Bits(12 bits)
    val FREEZER_ACTIVATE  = out Bool()

    val PS2_KEYS          = out Bits(512 bits)
    val PS2_KEYS_NEXT_OUT = out Bits(512 bits)
  }

  val ps2_keys_reg  = Reg(Bits(512 bits)) init 0
  val ps2_keys_next = Bits(512 bits)

  ps2_keys_reg := ps2_keys_next

  // PS/2 keyboard decoder signals
  val ps2_key_event    = Bool()
  val ps2_key_value    = Bits(8 bits)
  val ps2_key_extended = Bool()
  val ps2_key_up       = Bool()

  val direct_key_event    = Bool()
  val direct_key_value    = Bits(8 bits)
  val direct_key_extended = Bool()
  val direct_key_up       = Bool()

  // PS/2 keyboard instantiation
  if (ps2_enable == 1) {
    val keyboard1 = new Ps2Keyboard
    keyboard1.io.PS2_CLK := io.PS2_CLK
    keyboard1.io.PS2_DAT := io.PS2_DAT
    ps2_key_event    := keyboard1.io.KEY_EVENT
    ps2_key_value    := keyboard1.io.KEY_VALUE
    ps2_key_extended := keyboard1.io.KEY_EXTENDED
    ps2_key_up       := keyboard1.io.KEY_UP
  } else {
    ps2_key_event    := False
    ps2_key_value    := B(0, 8 bits)
    ps2_key_extended := False
    ps2_key_up       := False
  }

  // Direct input
  if (direct_enable == 1) {
    direct_key_value    := io.INPUT(7 downto 0)
    direct_key_extended := io.INPUT(12)
    direct_key_up       := ~io.INPUT(16)
    direct_key_event    := True
  } else {
    direct_key_event    := False
    direct_key_value    := B(0, 8 bits)
    direct_key_extended := False
    direct_key_up       := False
  }

  val key_event    = direct_key_event | ps2_key_event
  val key_value    = Mux(ps2_key_event, ps2_key_value, direct_key_value)
  val key_extended = Mux(ps2_key_event, ps2_key_extended, direct_key_extended)
  val key_up       = Mux(ps2_key_event, ps2_key_up, direct_key_up)

  // Update PS/2 keys register
  ps2_keys_next := ps2_keys_reg
  when(key_event) {
    ps2_keys_next((key_extended ## key_value).asUInt) := ~key_up
  }

  // Map to Atari key code
  val atari_keyboard   = Bits(64 bits)
  val shift_pressed    = Bool()
  val control_pressed  = Bool()
  val break_pressed    = Bool()
  val consol_start_int = Bool()
  val consol_select_int = Bool()
  val consol_option_int = Bool()
  val fkeys_int        = Bits(12 bits)
  val freezer_activate_int = Bool()

  atari_keyboard    := B(0, 64 bits)

  atari_keyboard(63) := ps2_keys_reg(0x1C) // A
  atari_keyboard(21) := ps2_keys_reg(0x32) // B
  atari_keyboard(18) := ps2_keys_reg(0x21) // C
  atari_keyboard(58) := ps2_keys_reg(0x23) // D
  atari_keyboard(42) := ps2_keys_reg(0x24) // E
  atari_keyboard(56) := ps2_keys_reg(0x2B) // F
  atari_keyboard(61) := ps2_keys_reg(0x34) // G
  atari_keyboard(57) := ps2_keys_reg(0x33) // H
  atari_keyboard(13) := ps2_keys_reg(0x43) // I
  atari_keyboard(1)  := ps2_keys_reg(0x3B) // J
  atari_keyboard(5)  := ps2_keys_reg(0x42) // K
  atari_keyboard(0)  := ps2_keys_reg(0x4B) // L
  atari_keyboard(37) := ps2_keys_reg(0x3A) // M
  atari_keyboard(35) := ps2_keys_reg(0x31) // N
  atari_keyboard(8)  := ps2_keys_reg(0x44) // O
  atari_keyboard(10) := ps2_keys_reg(0x4D) // P
  atari_keyboard(47) := ps2_keys_reg(0x15) // Q
  atari_keyboard(40) := ps2_keys_reg(0x2D) // R
  atari_keyboard(62) := ps2_keys_reg(0x1B) // S
  atari_keyboard(45) := ps2_keys_reg(0x2C) // T
  atari_keyboard(11) := ps2_keys_reg(0x3C) // U
  atari_keyboard(16) := ps2_keys_reg(0x2A) // V
  atari_keyboard(46) := ps2_keys_reg(0x1D) // W
  atari_keyboard(22) := ps2_keys_reg(0x22) // X
  atari_keyboard(43) := ps2_keys_reg(0x35) // Y
  atari_keyboard(23) := ps2_keys_reg(0x1A) // Z
  atari_keyboard(50) := ps2_keys_reg(0x45) // 0
  atari_keyboard(31) := ps2_keys_reg(0x16) // 1
  atari_keyboard(30) := ps2_keys_reg(0x1E) // 2
  atari_keyboard(26) := ps2_keys_reg(0x26) // 3
  atari_keyboard(24) := ps2_keys_reg(0x25) // 4
  atari_keyboard(29) := ps2_keys_reg(0x2E) // 5
  atari_keyboard(27) := ps2_keys_reg(0x36) // 6
  atari_keyboard(51) := ps2_keys_reg(0x3D) // 7
  atari_keyboard(53) := ps2_keys_reg(0x3E) // 8
  atari_keyboard(48) := ps2_keys_reg(0x46) // 9
  atari_keyboard(17) := ps2_keys_reg(0x16C) | ps2_keys_reg(0x03) // HELP
  atari_keyboard(52) := ps2_keys_reg(0x66) // BACKSPACE
  atari_keyboard(28) := ps2_keys_reg(0x76) // ESCAPE
  atari_keyboard(39) := ps2_keys_reg(0x111) // INVERSE
  atari_keyboard(60) := ps2_keys_reg(0x58) // CAPS
  atari_keyboard(44) := ps2_keys_reg(0x0D) // TAB
  atari_keyboard(12) := ps2_keys_reg(0x5A) // RETURN
  atari_keyboard(33) := ps2_keys_reg(0x29) // SPACE
  atari_keyboard(54) := ps2_keys_reg(0x4E) // LESS THAN
  atari_keyboard(55) := ps2_keys_reg(0x55) // GREATER THAN
  atari_keyboard(15) := ps2_keys_reg(0x5B) // EQUAL
  atari_keyboard(14) := ps2_keys_reg(0x54) // MINUS
  atari_keyboard(38) := ps2_keys_reg(0x4A) // FORWARD SLASH
  atari_keyboard(32) := ps2_keys_reg(0x41) // COMMA
  atari_keyboard(34) := ps2_keys_reg(0x49) // PERIOD

  when(~io.KEY_TYPE) { // ISO
    atari_keyboard(6)  := ps2_keys_reg(0x52) // PLUS
    atari_keyboard(7)  := ps2_keys_reg(0x5D) // ASTERIX
    atari_keyboard(2)  := ps2_keys_reg(0x4C) // SEMI-COLON
  } otherwise { // ANSI
    atari_keyboard(6)  := ps2_keys_reg(0x4C)
    atari_keyboard(7)  := ps2_keys_reg(0x52)
    atari_keyboard(2)  := ps2_keys_reg(0x5D)
  }

  atari_keyboard(3)  := ps2_keys_reg(0x05) // 1200XL F1
  atari_keyboard(4)  := ps2_keys_reg(0x06) // 1200XL F2
  atari_keyboard(19) := ps2_keys_reg(0x04) // 1200XL F3
  atari_keyboard(20) := ps2_keys_reg(0x0C) // 1200XL F4

  consol_start_int  := ps2_keys_reg(0x0B)
  consol_select_int := ps2_keys_reg(0x83)
  consol_option_int := ps2_keys_reg(0x0A)
  shift_pressed     := ps2_keys_reg(0x12) | ps2_keys_reg(0x59)
  control_pressed   := ps2_keys_reg(0x14) | ps2_keys_reg(0x114)
  break_pressed     := ps2_keys_reg(0x0E) | ps2_keys_reg(0x77)

  fkeys_int := ps2_keys_reg(0x07) ## ps2_keys_reg(0x78) ## ps2_keys_reg(0x09) ## ps2_keys_reg(0x01) ##
               ps2_keys_reg(0x0A) ## ps2_keys_reg(0x83) ## ps2_keys_reg(0x0B) ## ps2_keys_reg(0x03) ##
               ps2_keys_reg(0x0C) ## ps2_keys_reg(0x04) ## ps2_keys_reg(0x06) ## ps2_keys_reg(0x05)

  freezer_activate_int := ps2_keys_reg(0x7E) | ps2_keys_reg(0x171)

  // Provide results as if grid to POKEY
  io.KEYBOARD_RESPONSE := B"11"

  val scanIdx = (~io.KEYBOARD_SCAN).asUInt
  when(atari_keyboard(scanIdx)) {
    io.KEYBOARD_RESPONSE(0) := False
  }
  when(io.KEYBOARD_SCAN(5 downto 4) === B"00" & break_pressed) {
    io.KEYBOARD_RESPONSE(1) := False
  }
  when(io.KEYBOARD_SCAN(5 downto 4) === B"10" & shift_pressed) {
    io.KEYBOARD_RESPONSE(1) := False
  }
  when(io.KEYBOARD_SCAN(5 downto 4) === B"11" & control_pressed) {
    io.KEYBOARD_RESPONSE(1) := False
  }

  // Outputs
  io.CONSOL_START      := consol_start_int
  io.CONSOL_SELECT     := consol_select_int
  io.CONSOL_OPTION     := consol_option_int
  io.FKEYS             := fkeys_int
  io.FREEZER_ACTIVATE  := freezer_activate_int
  io.PS2_KEYS          := ps2_keys_reg
  io.PS2_KEYS_NEXT_OUT := ps2_keys_next
  io.ATARI_KEYBOARD_OUT := atari_keyboard
}
