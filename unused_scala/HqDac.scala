package atari800

import spinal.core._

// Third-order delta/sigma modulator DAC
// Uses no multiplies, only shifts by 1, 2 or 13
class HqDac extends Component {
  val io = new Bundle {
    val reset   = in  Bool()
    val clk_ena = in  Bool()
    val pcm_in  = in  Bits(20 bits)
    val dac_out = out Bool()
  }

  // ======================================
  // Stage #1
  // ======================================
  val w_data_in_p0  = SInt(24 bits)
  val w_data_err_p0 = SInt(24 bits)
  val w_data_int_p0 = SInt(24 bits)
  val r_data_fwd_p1 = Reg(SInt(24 bits)) init 0

  // PCM input sign-extended to 24 bits
  w_data_in_p0 := io.pcm_in.asSInt.resize(24)

  // Forward declaration of quantizer output
  val w_data_qt_p2 = SInt(24 bits)

  // Error
  w_data_err_p0 := w_data_in_p0 - w_data_qt_p2

  // First integrator: divide by 4 (arithmetic right shift by 2) + forward delay
  w_data_int_p0 := (w_data_err_p0 >> 2) + r_data_fwd_p1

  when(io.reset) {
    r_data_fwd_p1 := S(0, 24 bits)
  } elsewhen (io.clk_ena) {
    r_data_fwd_p1 := w_data_int_p0
  }

  // ======================================
  // Stage #2
  // ======================================
  val w_data_fb1_p1 = SInt(24 bits)
  val w_data_fb2_p1 = SInt(24 bits)
  val w_data_lpf_p1 = SInt(24 bits)
  val r_data_lpf_p2 = Reg(SInt(24 bits)) init 0

  val r_data_fwd_p2 = Reg(SInt(24 bits)) init 0

  // Feedback from quantizer
  w_data_fb1_p1 := (r_data_fwd_p1 >> 2) - (w_data_qt_p2 >> 2)

  // Feedback from third stage (divide by 8192)
  w_data_fb2_p1 := w_data_fb1_p1 - (r_data_fwd_p2 >> 13)

  // Low pass filter
  w_data_lpf_p1 := w_data_fb2_p1 + r_data_lpf_p2

  when(io.reset) {
    r_data_lpf_p2 := S(0, 24 bits)
  } elsewhen (io.clk_ena) {
    r_data_lpf_p2 := w_data_lpf_p1
  }

  // ======================================
  // Stage #3
  // ======================================
  val w_data_fb3_p1 = SInt(24 bits)
  val w_data_int_p1 = SInt(24 bits)

  // Feedback from quantizer (divide by 2)
  w_data_fb3_p1 := (w_data_lpf_p1 >> 1) - (w_data_qt_p2 >> 1)

  // Second integrator
  w_data_int_p1 := w_data_fb3_p1 + r_data_fwd_p2

  when(io.reset) {
    r_data_fwd_p2 := S(0, 24 bits)
  } elsewhen (io.clk_ena) {
    r_data_fwd_p2 := w_data_int_p1
  }

  // =====================================
  // 1-bit quantizer
  // =====================================
  w_data_qt_p2 := Mux(r_data_fwd_p2.msb, S(0xF00000, 24 bits), S(0x100000, 24 bits))

  val dac_out_reg = Reg(Bool()) init False
  when(io.reset) {
    dac_out_reg := False
  } elsewhen (io.clk_ena) {
    dac_out_reg := ~r_data_fwd_p2.msb
  }

  io.dac_out := dac_out_reg
}
