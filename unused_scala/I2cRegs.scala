package atari800

import spinal.core._

class I2cRegs(
  SLAVE_ADDR : Int = 0x50,
  regs       : Int = 1,
  bits       : Int = 1
) extends Component {
  val io = new Bundle {
    val scl_in  = in  Bool()
    val sda_in  = in  Bool()
    val scl_wen = out Bool()
    val sda_wen = out Bool()

    val rst     = in  Bool()

    val reg_out = out Bits(regs * bits bits)
  }

  def minVal(a: Int, b: Int): Int = if (a < b) a else b
  def ix(r: Int, c: Int): Int = r * bits + c

  val I2C_INIT   = B"000"
  val I2C_READ1  = B"001"
  val I2C_READ2  = B"010"
  val I2C_WRITE1 = B"011"
  val I2C_WRITE2 = B"100"

  val i2c_write      = Bool()
  val i2c_read       = Bool()
  val i2c_write_data = Bits(8 bits)
  val i2c_read_data  = Bits(8 bits)

  val i2c_addr_reg  = Reg(Bits(4 bits)) init 0
  val i2c_state_reg = Reg(Bits(3 bits)) init I2C_INIT
  val reg_reg       = Reg(Bits(regs * bits bits)) init 0

  val i2c_addr_next  = Bits(4 bits)
  val i2c_state_next = Bits(3 bits)
  val reg_next       = Bits(regs * bits bits)

  i2c_addr_reg  := i2c_addr_next
  i2c_state_reg := i2c_state_next
  reg_reg       := reg_next

  // I2C slave
  val i2cslave = new I2cSlave(SLAVE_ADDR)
  i2cslave.io.scl_in         := io.scl_in
  i2cslave.io.sda_in         := io.sda_in
  io.scl_wen                 := i2cslave.io.scl_wen
  io.sda_wen                 := i2cslave.io.sda_wen
  i2cslave.io.rst            := io.rst
  i2cslave.io.data_to_master := i2c_read_data
  i2c_read                   := i2cslave.io.read_req
  i2c_write                  := i2cslave.io.data_valid
  i2c_write_data             := i2cslave.io.data_from_master

  // Combinational logic
  val low_max = minVal(7, bits - 1)
  val i2c_addr_int = i2c_addr_reg.asUInt

  reg_next       := reg_reg
  i2c_addr_next  := i2c_addr_reg
  i2c_state_next := i2c_state_reg
  i2c_read_data  := B(0, 8 bits)

  switch(i2c_state_reg) {
    is(I2C_INIT) {
      when(i2c_write & i2c_write_data(7 downto 5) === B"111") {
        i2c_addr_next := i2c_write_data(3 downto 0)
        when(i2c_write_data(4)) {
          i2c_state_next := I2C_WRITE1
        } otherwise {
          i2c_state_next := I2C_READ1
        }
      }
    }
    is(I2C_WRITE1) {
      when(i2c_write) {
        for (i <- 0 until regs) {
          when(i2c_addr_int === U(i)) {
            reg_next(ix(i, low_max) downto ix(i, 0)) := i2c_write_data(low_max downto 0)
          }
        }
        i2c_state_next := I2C_WRITE2
      }
    }
    is(I2C_WRITE2) {
      when(i2c_write) {
        for (i <- 0 until regs) {
          when(i2c_addr_int === U(i)) {
            reg_next(ix(i, bits - 1) downto ix(i, 8)) := i2c_write_data(bits - 9 downto 0)
          }
        }
        i2c_state_next := I2C_INIT
      }
    }
    is(I2C_READ1) {
      for (i <- 0 until regs) {
        when(i2c_addr_int === U(i)) {
          i2c_read_data(low_max downto 0) := reg_reg(ix(i, low_max) downto ix(i, 0))
        }
      }
      when(i2c_read) {
        i2c_state_next := I2C_READ2
      }
    }
    is(I2C_READ2) {
      for (i <- 0 until regs) {
        when(i2c_addr_int === U(i)) {
          i2c_read_data(bits - 9 downto 0) := reg_reg(ix(i, bits - 1) downto ix(i, 8))
        }
      }
      when(i2c_read) {
        i2c_state_next := I2C_INIT
      }
    }
    default {
      i2c_state_next := I2C_INIT
    }
  }

  io.reg_out := reg_reg
}
