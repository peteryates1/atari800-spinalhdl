package atari800

import spinal.core._

class SpiMaster(
  slaves  : Int = 4,
  d_width : Int = 2
) extends Component {
  val io = new Bundle {
    val enable  = in  Bool()
    val cpol    = in  Bool()
    val cpha    = in  Bool()
    val cont    = in  Bool()
    val clk_div = in  UInt(log2Up(d_width * 4 + 2) max 8 bits) // integer input
    val addr    = in  UInt(log2Up(slaves) max 1 bits)
    val tx_data = in  Bits(d_width bits)
    val miso    = in  Bool()
    val sclk    = out Bool()
    val ss_n    = out Bits(slaves bits)
    val mosi    = out Bool()
    val busy    = out Bool()
    val rx_data = out Bits(d_width bits)
  }

  object SpiState extends SpinalEnum {
    val ready, execute = newElement()
  }

  val state        = Reg(SpiState()) init SpiState.ready
  val slave_sel    = Reg(UInt(log2Up(slaves) max 1 bits)) init 0
  val clk_ratio    = Reg(UInt(io.clk_div.getWidth bits)) init 0
  val count        = Reg(UInt(io.clk_div.getWidth bits)) init 0
  val clk_toggles  = Reg(UInt(log2Up(d_width * 2 + 2) bits)) init 0
  val assert_data  = Reg(Bool()) init False
  val continueFlag = Reg(Bool()) init False
  val rx_buffer    = Reg(Bits(d_width bits)) init 0
  val tx_buffer    = Reg(Bits(d_width bits)) init 0
  val last_bit_rx  = Reg(UInt(log2Up(d_width * 2 + 1) bits)) init 0
  val sclk_reg     = Reg(Bool()) init False
  val ss_n_reg     = Reg(Bits(slaves bits)) init B((slaves bits) -> true, default -> true)
  val mosi_reg     = Reg(Bool()) init False
  val busyReg      = Reg(Bool()) init True
  val rx_data_reg  = Reg(Bits(d_width bits)) init 0

  switch(state) {
    is(SpiState.ready) {
      busyReg      := False
      ss_n_reg     := B((slaves - 1 downto 0) -> true)
      mosi_reg     := False // high-Z mapped to '0'
      continueFlag := False

      when(io.enable) {
        busyReg := True
        when(io.addr < U(slaves)) {
          slave_sel := io.addr
        } otherwise {
          slave_sel := U(0)
        }
        when(io.clk_div === 0) {
          clk_ratio := U(1)
          count     := U(1)
        } otherwise {
          clk_ratio := io.clk_div
          count     := io.clk_div
        }
        sclk_reg    := io.cpol
        assert_data := ~io.cpha
        tx_buffer   := io.tx_data
        clk_toggles := U(0)
        last_bit_rx := U(d_width * 2) + io.cpha.asUInt.resized - U(1)
        state       := SpiState.execute
      } otherwise {
        state := SpiState.ready
      }
    }
    is(SpiState.execute) {
      busyReg := True
      ss_n_reg(slave_sel) := False

      when(count === clk_ratio) {
        count       := U(1)
        assert_data := ~assert_data
        when(clk_toggles === U(d_width * 2 + 1)) {
          clk_toggles := U(0)
        } otherwise {
          clk_toggles := clk_toggles + 1
        }

        // Toggle sclk
        when(clk_toggles <= U(d_width * 2) & ~ss_n_reg(slave_sel)) {
          sclk_reg := ~sclk_reg
        }

        // Receive
        when(~assert_data & clk_toggles < (last_bit_rx + 1).resized & ~ss_n_reg(slave_sel)) {
          rx_buffer := rx_buffer(d_width - 2 downto 0) ## io.miso
        }

        // Transmit
        when(assert_data & clk_toggles < last_bit_rx) {
          mosi_reg  := tx_buffer(d_width - 1)
          tx_buffer := tx_buffer(d_width - 2 downto 0) ## False
        }

        // Continue
        when(clk_toggles === last_bit_rx & io.cont) {
          tx_buffer   := io.tx_data
          clk_toggles := (last_bit_rx - U(d_width * 2) + U(1)).resized
          continueFlag := True
        }

        when(continueFlag) {
          continueFlag := False
          busyReg      := False
          rx_data_reg  := rx_buffer
        }

        // End of transaction
        when(clk_toggles === U(d_width * 2 + 1) & ~io.cont) {
          busyReg     := False
          ss_n_reg    := B((slaves - 1 downto 0) -> true)
          mosi_reg    := False
          rx_data_reg := rx_buffer
          state       := SpiState.ready
        } otherwise {
          state := SpiState.execute
        }
      } otherwise {
        count := count + 1
        state := SpiState.execute
      }
    }
  }

  io.sclk    := sclk_reg
  io.ss_n    := ss_n_reg
  io.mosi    := mosi_reg
  io.busy    := busyReg
  io.rx_data := rx_data_reg
}
