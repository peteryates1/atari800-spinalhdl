package atari800

import spinal.core._

class Ps2Keyboard extends Component {
  val io = new Bundle {
    val PS2_CLK      = in  Bool()
    val PS2_DAT      = in  Bool()

    val KEY_EVENT    = out Bool()
    val KEY_VALUE    = out Bits(8 bits)
    val KEY_EXTENDED = out Bool()
    val KEY_UP       = out Bool()
  }

  // Synchronize PS2 inputs
  val sync_clk = new Synchronizer
  sync_clk.io.raw := io.PS2_CLK
  val ps2_clk_reg = sync_clk.io.sync

  val sync_dat = new Synchronizer
  sync_dat.io.raw := io.PS2_DAT
  val ps2_dat_reg = sync_dat.io.sync

  // Receive raw data from PS/2 serial interface
  val ps2_shiftreg_reg  = Reg(Bits(11 bits)) init 0
  val idle_reg          = Reg(Bits(4 bits)) init 0
  val bitcount_reg      = Reg(Bits(4 bits)) init 0
  val last_ps2_clk_reg  = Reg(Bool()) init False

  val byte_received_reg = Reg(Bool()) init False
  val byte_reg          = Reg(Bits(8 bits)) init 0

  val pending_extended_reg = Reg(Bool()) init False
  val pending_keyup_reg    = Reg(Bool()) init False

  val key_event_reg      = Reg(Bool()) init False
  val key_value_reg      = Reg(Bits(10 bits)) init 0
  val key_value_last_reg = Reg(Bits(10 bits)) init 0

  // Enable divider for PS/2 clock sampling
  val enable_div = new EnableDivider(256)
  enable_div.io.enableIn := True
  val enable_ps2 = enable_div.io.enableOut

  // Parity
  val parity = ~(ps2_shiftreg_reg(8) ^ ps2_shiftreg_reg(7) ^ ps2_shiftreg_reg(6) ^ ps2_shiftreg_reg(5) ^
                 ps2_shiftreg_reg(4) ^ ps2_shiftreg_reg(3) ^ ps2_shiftreg_reg(2) ^ ps2_shiftreg_reg(1))

  // Next signals
  val ps2_shiftreg_next  = Bits(11 bits)
  val last_ps2_clk_next  = Bool()
  val bitcount_next      = Bits(4 bits)
  val idle_next          = Bits(4 bits)
  val byte_received_next = Bool()
  val byte_next          = Bits(8 bits)

  ps2_shiftreg_next := ps2_shiftreg_reg
  last_ps2_clk_next := last_ps2_clk_reg
  bitcount_next     := bitcount_reg
  idle_next         := idle_reg
  byte_received_next := False
  byte_next         := B(0, 8 bits)

  when(enable_ps2) {
    last_ps2_clk_next := ps2_clk_reg

    // Sample on falling edge
    when(~ps2_clk_reg & last_ps2_clk_reg) {
      ps2_shiftreg_next := ps2_dat_reg ## ps2_shiftreg_reg(10 downto 1)
      bitcount_next     := (bitcount_reg.asUInt + 1).asBits
    }

    // Output when done
    when(bitcount_reg === B"xB") {
      byte_received_next := (parity === ps2_shiftreg_reg(9)) & ~ps2_shiftreg_reg(0) & ps2_shiftreg_reg(10)
      byte_next          := ps2_shiftreg_reg(8 downto 1)
      bitcount_next      := B(0, 4 bits)
    }

    // Reset if idle
    idle_next := (idle_reg.asUInt + 1).asBits
    when(idle_reg === B"xF") {
      ps2_shiftreg_next := B(0, 11 bits)
      bitcount_next     := B(0, 4 bits)
    }
    when(~ps2_clk_reg | ~ps2_dat_reg) {
      idle_next := B"x0"
    }
  }

  ps2_shiftreg_reg  := ps2_shiftreg_next
  last_ps2_clk_reg  := last_ps2_clk_next
  bitcount_reg      := bitcount_next
  idle_reg          := idle_next
  byte_received_reg := byte_received_next
  byte_reg          := byte_next

  // Process bytes
  val pending_extended_next = Bool()
  val pending_keyup_next    = Bool()
  val key_event_next        = Bool()
  val key_value_next        = Bits(10 bits)
  val key_value_last_next   = Bits(10 bits)

  pending_extended_next := pending_extended_reg
  pending_keyup_next    := pending_keyup_reg
  key_event_next        := False
  key_value_next        := B(0, 10 bits)
  key_value_last_next   := key_value_last_reg

  when(byte_received_reg) {
    switch(byte_reg) {
      is(B"xE0") { pending_extended_next := True }
      is(B"xE1") { pending_extended_next := True }
      is(B"xF0") { pending_keyup_next := True }
      default {
        pending_extended_next := False
        pending_keyup_next    := False

        val newKeyValue = pending_keyup_reg ## pending_extended_reg ## byte_reg(7 downto 0)
        when(key_value_last_reg =/= newKeyValue) {
          key_event_next      := True
          key_value_next      := newKeyValue
          key_value_last_next := newKeyValue
        }
      }
    }
  }

  pending_extended_reg := pending_extended_next
  pending_keyup_reg    := pending_keyup_next
  key_event_reg        := key_event_next
  key_value_reg        := key_value_next
  key_value_last_reg   := key_value_last_next

  // Output
  io.KEY_EVENT    := key_event_reg
  io.KEY_VALUE    := key_value_reg(7 downto 0)
  io.KEY_EXTENDED := key_value_reg(8)
  io.KEY_UP       := key_value_reg(9)
}
