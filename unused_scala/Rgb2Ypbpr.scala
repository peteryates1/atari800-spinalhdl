package atari800

import spinal.core._

// Multiplier-based RGB -> YPbPr conversion
class Rgb2Ypbpr(WIDTH: Int = 8) extends Component {
  val io = new Bundle {
    val ena       = in  Bool()

    val red_in    = in  UInt(WIDTH bits)
    val green_in  = in  UInt(WIDTH bits)
    val blue_in   = in  UInt(WIDTH bits)
    val hs_in     = in  Bool()
    val vs_in     = in  Bool()
    val cs_in     = in  Bool()
    val pixel_in  = in  Bool()

    val red_out   = out UInt(WIDTH bits)
    val green_out = out UInt(WIDTH bits)
    val blue_out  = out UInt(WIDTH bits)
    val hs_out    = out Bool()
    val vs_out    = out Bool()
    val cs_out    = out Bool()
    val pixel_out = out Bool()
  }

  val TOTAL = 8 + WIDTH

  // Stage 1 registers (multiply)
  val r_y = Reg(UInt(TOTAL bits)) init 0
  val g_y = Reg(UInt(TOTAL bits)) init 0
  val b_y = Reg(UInt(TOTAL bits)) init 0

  val r_b = Reg(UInt(TOTAL bits)) init 0
  val g_b = Reg(UInt(TOTAL bits)) init 0
  val b_b = Reg(UInt(TOTAL bits)) init 0

  val r_r = Reg(UInt(TOTAL bits)) init 0
  val g_r = Reg(UInt(TOTAL bits)) init 0
  val b_r = Reg(UInt(TOTAL bits)) init 0

  // Stage 2 registers (add)
  val y = Reg(UInt(TOTAL bits)) init 0
  val b_out = Reg(UInt(TOTAL bits)) init 0
  val r_out = Reg(UInt(TOTAL bits)) init 0

  // Sync delay registers
  val hs_d    = Reg(Bool()) init False
  val vs_d    = Reg(Bool()) init False
  val cs_d    = Reg(Bool()) init False
  val pixel_d = Reg(Bool()) init False

  val hs_out_reg    = Reg(Bool()) init False
  val vs_out_reg    = Reg(Bool()) init False
  val cs_out_reg    = Reg(Bool()) init False
  val pixel_out_reg = Reg(Bool()) init False

  // Stage 1: multiply
  hs_d    := io.hs_in
  vs_d    := io.vs_in
  cs_d    := io.cs_in
  pixel_d := io.pixel_in

  when(io.ena) {
    // Y = 0.299*R + 0.587*G + 0.114*B
    r_y := io.red_in   * U(76, 8 bits)
    g_y := io.green_in * U(150, 8 bits)
    b_y := io.blue_in  * U(29, 8 bits)

    // Pb = -0.169*R - 0.331*G + 0.500*B
    r_b := io.red_in   * U(43, 8 bits)
    g_b := io.green_in * U(84, 8 bits)
    b_b := io.blue_in  * U(128, 8 bits)

    // Pr = 0.500*R - 0.419*G - 0.081*B
    r_r := io.red_in   * U(128, 8 bits)
    g_r := io.green_in * U(107, 8 bits)
    b_r := io.blue_in  * U(20, 8 bits)
  } otherwise {
    // Passthrough
    r_r(TOTAL - 1 downto 8) := io.red_in
    g_y(TOTAL - 1 downto 8) := io.green_in
    b_b(TOTAL - 1 downto 8) := io.blue_in
  }

  // Stage 2: adding
  hs_out_reg    := hs_d
  vs_out_reg    := vs_d
  cs_out_reg    := cs_d
  pixel_out_reg := pixel_d

  val midpoint = U(1 << (TOTAL - 1), TOTAL bits)
  when(io.ena) {
    y     := r_y + g_y + b_y
    b_out := midpoint + b_b - r_b - g_b
    r_out := midpoint + r_r - g_r - b_r
  } otherwise {
    // Passthrough
    y     := g_y
    b_out := b_b
    r_out := r_r
  }

  // Output
  io.red_out   := r_out(TOTAL - 1 downto 8)
  io.green_out := y(TOTAL - 1 downto 8)
  io.blue_out  := b_out(TOTAL - 1 downto 8)
  io.hs_out    := hs_out_reg
  io.vs_out    := vs_out_reg
  io.cs_out    := cs_out_reg
  io.pixel_out := pixel_out_reg
}
