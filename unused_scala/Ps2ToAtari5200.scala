package atari800

import spinal.core._

class Ps2ToAtari5200(
  ps2_enable    : Int = 1,
  direct_enable : Int = 0
) extends Component {
  val io = new Bundle {
    val PS2_CLK           = in  Bool()
    val PS2_DAT           = in  Bool()
    val INPUT             = in  Bits(32 bits)

    val KEYBOARD_SCAN     = in  Bits(6 bits)
    val KEYBOARD_RESPONSE = out Bits(2 bits)

    val FIRE2             = in  Bits(4 bits)
    val CONTROLLER_SELECT = in  Bits(2 bits)

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

  ps2_keys_next := ps2_keys_reg
  when(key_event) {
    ps2_keys_next((key_extended ## key_value).asUInt) := ~key_up
  }

  // Map to Atari key code
  val atari_keyboard    = Bits(16 bits)
  val fire_pressed_sel  = Bool()
  val fkeys_int         = Bits(12 bits)
  val freezer_activate_int = Bool()
  val ctrl = ps2_keys_reg(0x14) // control key

  atari_keyboard   := B(0, 16 bits)
  fire_pressed_sel := False

  switch(io.CONTROLLER_SELECT) {
    is(B"00") {
      atari_keyboard(12) := ps2_keys_reg(0x05) & ~ctrl
      atari_keyboard(8)  := ps2_keys_reg(0x06) & ~ctrl
      atari_keyboard(4)  := ps2_keys_reg(0x04) & ~ctrl
      atari_keyboard(15) := ps2_keys_reg(0x16) & ~ctrl
      atari_keyboard(14) := ps2_keys_reg(0x1E) & ~ctrl
      atari_keyboard(13) := ps2_keys_reg(0x26) & ~ctrl
      atari_keyboard(11) := ps2_keys_reg(0x15) & ~ctrl
      atari_keyboard(10) := ps2_keys_reg(0x1D) & ~ctrl
      atari_keyboard(9)  := ps2_keys_reg(0x24) & ~ctrl
      atari_keyboard(7)  := ps2_keys_reg(0x1C) & ~ctrl
      atari_keyboard(6)  := ps2_keys_reg(0x1B) & ~ctrl
      atari_keyboard(5)  := ps2_keys_reg(0x23) & ~ctrl
      atari_keyboard(3)  := ps2_keys_reg(0x1A) & ~ctrl
      atari_keyboard(2)  := ps2_keys_reg(0x22) & ~ctrl
      atari_keyboard(1)  := ps2_keys_reg(0x21) & ~ctrl
      fire_pressed_sel   := io.FIRE2(0)
    }
    is(B"01") {
      atari_keyboard(12) := ps2_keys_reg(0x0C) & ~ctrl
      atari_keyboard(8)  := ps2_keys_reg(0x03) & ~ctrl
      atari_keyboard(4)  := ps2_keys_reg(0x0B) & ~ctrl
      atari_keyboard(15) := ps2_keys_reg(0x25) & ~ctrl
      atari_keyboard(14) := ps2_keys_reg(0x2E) & ~ctrl
      atari_keyboard(13) := ps2_keys_reg(0x36) & ~ctrl
      atari_keyboard(11) := ps2_keys_reg(0x2D) & ~ctrl
      atari_keyboard(10) := ps2_keys_reg(0x2C) & ~ctrl
      atari_keyboard(9)  := ps2_keys_reg(0x35) & ~ctrl
      atari_keyboard(7)  := ps2_keys_reg(0x2B) & ~ctrl
      atari_keyboard(6)  := ps2_keys_reg(0x34) & ~ctrl
      atari_keyboard(5)  := ps2_keys_reg(0x33) & ~ctrl
      atari_keyboard(3)  := ps2_keys_reg(0x2A) & ~ctrl
      atari_keyboard(2)  := ps2_keys_reg(0x32) & ~ctrl
      atari_keyboard(1)  := ps2_keys_reg(0x31) & ~ctrl
      fire_pressed_sel   := io.FIRE2(1)
    }
    is(B"10") {
      atari_keyboard(12) := ps2_keys_reg(0x05) & ctrl
      atari_keyboard(8)  := ps2_keys_reg(0x06) & ctrl
      atari_keyboard(4)  := ps2_keys_reg(0x04) & ctrl
      atari_keyboard(15) := ps2_keys_reg(0x16) & ctrl
      atari_keyboard(14) := ps2_keys_reg(0x1E) & ctrl
      atari_keyboard(13) := ps2_keys_reg(0x26) & ctrl
      atari_keyboard(11) := ps2_keys_reg(0x15) & ctrl
      atari_keyboard(10) := ps2_keys_reg(0x1D) & ctrl
      atari_keyboard(9)  := ps2_keys_reg(0x24) & ctrl
      atari_keyboard(7)  := ps2_keys_reg(0x1C) & ctrl
      atari_keyboard(6)  := ps2_keys_reg(0x1B) & ctrl
      atari_keyboard(5)  := ps2_keys_reg(0x23) & ctrl
      atari_keyboard(3)  := ps2_keys_reg(0x1A) & ctrl
      atari_keyboard(2)  := ps2_keys_reg(0x22) & ctrl
      atari_keyboard(1)  := ps2_keys_reg(0x21) & ctrl
      fire_pressed_sel   := io.FIRE2(2)
    }
    is(B"11") {
      atari_keyboard(12) := ps2_keys_reg(0x0C) & ctrl
      atari_keyboard(8)  := ps2_keys_reg(0x03) & ctrl
      atari_keyboard(4)  := ps2_keys_reg(0x0B) & ctrl
      atari_keyboard(15) := ps2_keys_reg(0x25) & ctrl
      atari_keyboard(14) := ps2_keys_reg(0x2E) & ctrl
      atari_keyboard(13) := ps2_keys_reg(0x36) & ctrl
      atari_keyboard(11) := ps2_keys_reg(0x2D) & ctrl
      atari_keyboard(10) := ps2_keys_reg(0x2C) & ctrl
      atari_keyboard(9)  := ps2_keys_reg(0x35) & ctrl
      atari_keyboard(7)  := ps2_keys_reg(0x2B) & ctrl
      atari_keyboard(6)  := ps2_keys_reg(0x34) & ctrl
      atari_keyboard(5)  := ps2_keys_reg(0x33) & ctrl
      atari_keyboard(3)  := ps2_keys_reg(0x2A) & ctrl
      atari_keyboard(2)  := ps2_keys_reg(0x32) & ctrl
      atari_keyboard(1)  := ps2_keys_reg(0x31) & ctrl
      fire_pressed_sel   := io.FIRE2(3)
    }
  }

  fkeys_int := ps2_keys_reg(0x07) ## ps2_keys_reg(0x78) ## ps2_keys_reg(0x09) ## ps2_keys_reg(0x01) ##
               ps2_keys_reg(0x0A) ## ps2_keys_reg(0x83) ## ps2_keys_reg(0x0B) ## ps2_keys_reg(0x03) ##
               ps2_keys_reg(0x0C) ## ps2_keys_reg(0x04) ## ps2_keys_reg(0x06) ## ps2_keys_reg(0x05)

  freezer_activate_int := ps2_keys_reg(0x7E) | ps2_keys_reg(0x171)

  // Provide results as if grid to POKEY
  io.KEYBOARD_RESPONSE := B"11"

  val scanBits = (~io.KEYBOARD_SCAN(4 downto 1)).asUInt
  when(atari_keyboard(scanBits)) {
    io.KEYBOARD_RESPONSE(0) := False
  }
  io.KEYBOARD_RESPONSE(1) := ~fire_pressed_sel

  // Outputs
  io.FKEYS             := fkeys_int
  io.FREEZER_ACTIVATE  := freezer_activate_int
  io.PS2_KEYS          := ps2_keys_reg
  io.PS2_KEYS_NEXT_OUT := ps2_keys_next
}
