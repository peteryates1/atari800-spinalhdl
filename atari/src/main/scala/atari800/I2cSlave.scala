package atari800

import spinal.core._

class I2cSlave(
  SLAVE_ADDR : Int = 0x50
) extends Component {
  val io = new Bundle {
    val scl_in           = in  Bool()
    val sda_in           = in  Bool()
    val scl_wen          = out Bool()
    val sda_wen          = out Bool()

    val rst              = in  Bool()

    val read_req         = out Bool()
    val data_to_master   = in  Bits(8 bits)
    val data_valid       = out Bool()
    val data_from_master = out Bits(8 bits)
  }

  val slaveAddrBits = B(SLAVE_ADDR, 7 bits)

  object I2cSlaveState extends SpinalEnum {
    val idle, get_address_and_cmd, answer_ack_start,
        write, read, read_ack_start,
        read_ack_got_rising, read_stop = newElement()
  }

  val state_reg          = Reg(I2cSlaveState()) init I2cSlaveState.idle
  val cmd_reg            = Reg(Bool()) init False
  val bits_processed_reg = Reg(UInt(4 bits)) init 0
  val continue_reg       = Reg(Bool()) init False

  val scl_reg            = Reg(Bool()) init True
  val sda_reg            = Reg(Bool()) init True
  val scl_debounced      = Bool()
  val sda_debounced      = Bool()

  val start_reg          = Reg(Bool()) init False
  val stop_reg           = Reg(Bool()) init False
  val scl_rising_reg     = Reg(Bool()) init False
  val scl_falling_reg    = Reg(Bool()) init False

  val addr_reg             = Reg(Bits(7 bits)) init 0
  val data_reg             = Reg(Bits(7 bits)) init 0
  val data_from_master_reg = Reg(Bits(8 bits)) init 0

  val scl_prev_reg  = Reg(Bool()) init True
  val scl_wen_reg   = Reg(Bool()) init False
  val scl_o_reg     = Reg(Bool()) init False
  val sda_prev_reg  = Reg(Bool()) init True
  val sda_wen_reg   = Reg(Bool()) init False
  val sda_o_reg     = Reg(Bool()) init False

  val data_valid_reg     = Reg(Bool()) init False
  val read_req_reg       = Reg(Bool()) init False
  val data_to_master_reg = Reg(Bits(8 bits)) init 0

  // Debounce: pass through (matching VHDL where 'H' -> '1')
  scl_debounced := scl_reg
  sda_debounced := sda_reg

  // Edge detection and start/stop
  scl_reg      := io.scl_in
  sda_reg      := io.sda_in
  scl_prev_reg := scl_debounced
  sda_prev_reg := sda_debounced

  scl_rising_reg  := False
  when(scl_prev_reg === False & scl_debounced === True) {
    scl_rising_reg := True
  }
  scl_falling_reg := False
  when(scl_prev_reg === True & scl_debounced === False) {
    scl_falling_reg := True
  }

  // Start/Stop detection
  start_reg := False
  stop_reg  := False
  when(scl_debounced & scl_prev_reg & sda_prev_reg & ~sda_debounced) {
    start_reg := True
    stop_reg  := False
  }
  when(scl_prev_reg & scl_debounced & ~sda_prev_reg & sda_debounced) {
    start_reg := False
    stop_reg  := True
  }

  // State machine
  sda_o_reg      := False
  sda_wen_reg    := False
  data_valid_reg := False
  read_req_reg   := False

  switch(state_reg) {
    is(I2cSlaveState.idle) {
      when(start_reg) {
        state_reg          := I2cSlaveState.get_address_and_cmd
        bits_processed_reg := U(0)
      }
    }
    is(I2cSlaveState.get_address_and_cmd) {
      when(scl_rising_reg) {
        when(bits_processed_reg < 7) {
          bits_processed_reg := bits_processed_reg + 1
          addr_reg(6 - bits_processed_reg.resize(3)) := sda_debounced
        } elsewhen (bits_processed_reg === 7) {
          bits_processed_reg := bits_processed_reg + 1
          cmd_reg            := sda_debounced
        }
      }
      when(bits_processed_reg === 8 & scl_falling_reg) {
        bits_processed_reg := U(0)
        when(addr_reg === slaveAddrBits) {
          state_reg := I2cSlaveState.answer_ack_start
          when(cmd_reg) {
            read_req_reg       := True
            data_to_master_reg := io.data_to_master
          }
        } otherwise {
          state_reg := I2cSlaveState.idle
        }
      }
    }
    is(I2cSlaveState.answer_ack_start) {
      sda_wen_reg := True
      sda_o_reg   := False
      when(scl_falling_reg) {
        when(~cmd_reg) {
          state_reg := I2cSlaveState.write
        } otherwise {
          state_reg := I2cSlaveState.read
        }
      }
    }
    is(I2cSlaveState.write) {
      when(scl_rising_reg) {
        bits_processed_reg := bits_processed_reg + 1
        when(bits_processed_reg < 7) {
          data_reg(6 - bits_processed_reg.resize(3)) := sda_debounced
        } otherwise {
          data_from_master_reg := data_reg ## sda_debounced
          data_valid_reg       := True
        }
      }
      when(scl_falling_reg & bits_processed_reg === 8) {
        state_reg          := I2cSlaveState.answer_ack_start
        bits_processed_reg := U(0)
      }
    }
    is(I2cSlaveState.read) {
      sda_wen_reg := True
      sda_o_reg   := data_to_master_reg(7 - bits_processed_reg.resize(3))
      when(scl_falling_reg) {
        when(bits_processed_reg < 7) {
          bits_processed_reg := bits_processed_reg + 1
        } elsewhen (bits_processed_reg === 7) {
          state_reg          := I2cSlaveState.read_ack_start
          bits_processed_reg := U(0)
        }
      }
    }
    is(I2cSlaveState.read_ack_start) {
      when(scl_rising_reg) {
        state_reg := I2cSlaveState.read_ack_got_rising
        when(sda_debounced) { // nack
          continue_reg := False
        } otherwise { // ack
          continue_reg       := True
          read_req_reg       := True
          data_to_master_reg := io.data_to_master
        }
      }
    }
    is(I2cSlaveState.read_ack_got_rising) {
      when(scl_falling_reg) {
        when(continue_reg) {
          when(~cmd_reg) {
            state_reg := I2cSlaveState.write
          } otherwise {
            state_reg := I2cSlaveState.read
          }
        } otherwise {
          state_reg := I2cSlaveState.read_stop
        }
      }
    }
    is(I2cSlaveState.read_stop) {
      // wait for start or stop
    }
  }

  // Reset counter and state on start/stop
  when(start_reg) {
    state_reg          := I2cSlaveState.get_address_and_cmd
    bits_processed_reg := U(0)
  }
  when(stop_reg) {
    state_reg          := I2cSlaveState.idle
    bits_processed_reg := U(0)
  }
  when(io.rst) {
    state_reg := I2cSlaveState.idle
  }

  // Outputs
  io.scl_wen       := scl_wen_reg & ~scl_o_reg
  io.sda_wen       := sda_wen_reg & ~sda_o_reg
  io.data_valid    := data_valid_reg
  io.data_from_master := data_from_master_reg
  io.read_req      := read_req_reg
}
