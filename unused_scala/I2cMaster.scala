package atari800

import spinal.core._

class I2cMaster(
  input_clk : Int = 50000000,
  bus_clk   : Int = 400000
) extends Component {
  val io = new Bundle {
    val ena       = in  Bool()
    val addr      = in  Bits(7 bits)
    val rw        = in  Bool()
    val data_wr   = in  Bits(8 bits)
    val busy      = out Bool()
    val data_rd   = out Bits(8 bits)
    val ack_error = out Bool()

    val scl_in    = in  Bool()
    val sda_in    = in  Bool()
    val scl_wen   = out Bool()
    val sda_wen   = out Bool()
  }

  val divider = (input_clk / bus_clk) / 4

  object I2cState extends SpinalEnum {
    val ready, start, command, slv_ack1, wr, rd, slv_ack2, mstr_ack, stop = newElement()
  }

  val state         = Reg(I2cState()) init I2cState.ready
  val data_clk      = Reg(Bool()) init False
  val data_clk_prev = Reg(Bool()) init False
  val scl_clk       = Reg(Bool()) init False
  val scl_ena       = Reg(Bool()) init False
  val sda_int       = Reg(Bool()) init True
  val sda_ena_n     = Bool()
  val addr_rw       = Reg(Bits(8 bits)) init 0
  val data_tx       = Reg(Bits(8 bits)) init 0
  val data_rx       = Reg(Bits(8 bits)) init 0
  val bit_cnt       = Reg(UInt(3 bits)) init 7
  val stretch       = Reg(Bool()) init False
  val busyReg       = Reg(Bool()) init True
  val ack_error_reg = Reg(Bool()) init False
  val data_rd_reg   = Reg(Bits(8 bits)) init 0

  // Clock generation
  val count = Reg(UInt(log2Up(divider * 4 + 1) bits)) init 0

  data_clk_prev := data_clk

  when(count === U(divider * 4 - 1)) {
    count := U(0)
  } elsewhen (~stretch) {
    count := count + 1
  }

  when(count < U(divider)) {
    scl_clk  := False
    data_clk := False
  } elsewhen (count < U(divider * 2)) {
    scl_clk  := False
    data_clk := True
  } elsewhen (count < U(divider * 3)) {
    scl_clk  := True
    when(~io.scl_in) {
      stretch := True
    } otherwise {
      stretch := False
    }
    data_clk := True
  } otherwise {
    scl_clk  := True
    data_clk := False
  }

  // State machine - data_clk rising edge
  when(data_clk & ~data_clk_prev) {
    switch(state) {
      is(I2cState.ready) {
        when(io.ena) {
          busyReg  := True
          addr_rw  := io.addr ## io.rw
          data_tx  := io.data_wr
          state    := I2cState.start
        } otherwise {
          busyReg  := False
          state    := I2cState.ready
        }
      }
      is(I2cState.start) {
        busyReg  := True
        sda_int  := addr_rw(bit_cnt)
        state    := I2cState.command
      }
      is(I2cState.command) {
        when(bit_cnt === 0) {
          sda_int := True
          bit_cnt := U(7)
          state   := I2cState.slv_ack1
        } otherwise {
          bit_cnt := bit_cnt - 1
          sda_int := addr_rw(bit_cnt - 1)
          state   := I2cState.command
        }
      }
      is(I2cState.slv_ack1) {
        when(~addr_rw(0)) { // write
          sda_int := data_tx(bit_cnt)
          state   := I2cState.wr
        } otherwise { // read
          sda_int := True
          state   := I2cState.rd
        }
      }
      is(I2cState.wr) {
        busyReg := True
        when(bit_cnt === 0) {
          sda_int := True
          bit_cnt := U(7)
          state   := I2cState.slv_ack2
        } otherwise {
          bit_cnt := bit_cnt - 1
          sda_int := data_tx(bit_cnt - 1)
          state   := I2cState.wr
        }
      }
      is(I2cState.rd) {
        busyReg := True
        when(bit_cnt === 0) {
          when(io.ena & (addr_rw === (io.addr ## io.rw))) {
            sda_int := False
          } otherwise {
            sda_int := True
          }
          bit_cnt    := U(7)
          data_rd_reg := data_rx
          state      := I2cState.mstr_ack
        } otherwise {
          bit_cnt := bit_cnt - 1
          state   := I2cState.rd
        }
      }
      is(I2cState.slv_ack2) {
        when(io.ena) {
          busyReg := False
          addr_rw := io.addr ## io.rw
          data_tx := io.data_wr
          when(addr_rw === (io.addr ## io.rw)) {
            sda_int := io.data_wr(bit_cnt)
            state   := I2cState.wr
          } otherwise {
            state := I2cState.start
          }
        } otherwise {
          state := I2cState.stop
        }
      }
      is(I2cState.mstr_ack) {
        when(io.ena) {
          busyReg := False
          addr_rw := io.addr ## io.rw
          data_tx := io.data_wr
          when(addr_rw === (io.addr ## io.rw)) {
            sda_int := True
            state   := I2cState.rd
          } otherwise {
            state := I2cState.start
          }
        } otherwise {
          state := I2cState.stop
        }
      }
      is(I2cState.stop) {
        busyReg := False
        state   := I2cState.ready
      }
    }
  } elsewhen (~data_clk & data_clk_prev) { // data_clk falling edge
    switch(state) {
      is(I2cState.start) {
        when(~scl_ena) {
          scl_ena       := True
          ack_error_reg := False
        }
      }
      is(I2cState.slv_ack1) {
        when(io.sda_in | ack_error_reg) {
          ack_error_reg := True
        }
      }
      is(I2cState.rd) {
        data_rx(bit_cnt) := io.sda_in
      }
      is(I2cState.slv_ack2) {
        when(io.sda_in | ack_error_reg) {
          ack_error_reg := True
        }
      }
      is(I2cState.stop) {
        scl_ena := False
      }
      default { /* nop */ }
    }
  }

  // SDA output control
  sda_ena_n := state.mux(
    I2cState.start    -> data_clk_prev,
    I2cState.stop     -> ~data_clk_prev,
    default           -> sda_int
  )

  // Outputs
  io.sda_wen   := ~sda_ena_n
  io.scl_wen   := scl_ena & ~scl_clk
  io.busy      := busyReg
  io.data_rd   := data_rd_reg
  io.ack_error := ack_error_reg
}
