package atari800

import spinal.core._

class Scandoubler(video_bits: Int = 4) extends Component {
  val io = new Bundle {
    val VGA                = in  Bool()
    val COMPOSITE_ON_HSYNC = in  Bool()
    val colour_enable      = in  Bool()
    val doubled_enable     = in  Bool()
    val scanlines_on       = in  Bool()

    // GTIA interface
    val pal       = in  Bool()
    val colour_in = in  Bits(8 bits)
    val vsync_in  = in  Bool()
    val hsync_in  = in  Bool()
    val csync_in  = in  Bool()

    // TO TV
    val R     = out Bits(video_bits bits)
    val G     = out Bits(video_bits bits)
    val B     = out Bits(video_bits bits)
    val VSYNC = out Bool()
    val HSYNC = out Bool()
  }

  val colour_reg = Reg(Bits(8 bits)) init 0
  val vsync_reg  = Reg(Bool()) init False
  val hsync_reg  = Reg(Bool()) init False

  val r_next = Bits(8 bits)
  val g_next = Bits(8 bits)
  val b_next = Bits(8 bits)
  val r_reg  = Reg(Bits(8 bits)) init 0
  val g_reg  = Reg(Bits(8 bits)) init 0
  val b_reg  = Reg(Bits(8 bits)) init 0

  val linea_address         = Bits(11 bits)
  val linea_write_enable    = Bool()
  val linea_out             = Bits(8 bits)

  val lineb_address         = Bits(11 bits)
  val lineb_write_enable    = Bool()
  val lineb_out             = Bits(8 bits)

  val input_address_reg  = Reg(Bits(11 bits)) init 0
  val output_address_reg = Reg(Bits(11 bits)) init 0
  val buffer_select_reg  = Reg(Bool()) init False
  val hsync_in_reg       = Reg(Bool())
  val vga_hsync_reg      = Reg(Bool()) init False
  val vga_odd_reg        = Reg(Bool()) init False

  val input_address_next  = Bits(11 bits)
  val output_address_next = Bits(11 bits)
  val buffer_select_next  = Bool()
  val colour_next         = Bits(8 bits)
  val vsync_next          = Bool()
  val hsync_next          = Bool()
  val vga_hsync_next      = Bool()
  val vga_hsync_start     = Bool()
  val vga_hsync_end       = Bool()
  val vga_odd_next        = Bool()
  val reset_output_address = Bool()

  // Registers
  r_reg       := r_next
  g_reg       := g_next
  b_reg       := b_next
  colour_reg  := colour_next
  hsync_reg   := hsync_next
  vsync_reg   := vsync_next
  input_address_reg  := input_address_next
  output_address_reg := output_address_next
  buffer_select_reg  := buffer_select_next
  hsync_in_reg       := io.hsync_in
  vga_hsync_reg      := vga_hsync_next
  vga_odd_reg        := vga_odd_next

  // Line buffers
  val lineaRam = new ScandoubleRamInfer
  lineaRam.io.data    := io.colour_in
  lineaRam.io.address := linea_address.asUInt
  lineaRam.io.we      := linea_write_enable
  linea_out           := lineaRam.io.q

  val linebRam = new ScandoubleRamInfer
  linebRam.io.data    := io.colour_in
  linebRam.io.address := lineb_address.asUInt
  linebRam.io.we      := lineb_write_enable
  lineb_out           := linebRam.io.q

  // Capture
  input_address_next  := input_address_reg
  buffer_select_next  := buffer_select_reg
  linea_write_enable  := False
  lineb_write_enable  := False
  reset_output_address := False

  when(io.colour_enable) {
    input_address_next := (input_address_reg.asUInt + 1).asBits
    linea_write_enable := buffer_select_reg
    lineb_write_enable := ~buffer_select_reg
  }
  when(io.hsync_in & ~hsync_in_reg) {
    input_address_next := B(0, 11 bits)
    buffer_select_next := ~buffer_select_reg
    reset_output_address := True
  }

  // Output
  output_address_next := output_address_reg
  vga_hsync_start     := False
  vga_hsync_next      := vga_hsync_reg
  vga_odd_next        := vga_odd_reg

  when(io.doubled_enable) {
    output_address_next := (output_address_reg.asUInt + 1).asBits
    when(output_address_reg === (B"111" ## B"x1F")) { // "111" & X"1F"
      output_address_next := B(0, 11 bits)
      vga_hsync_start     := True
      vga_hsync_next      := True
    }
  }
  when(vga_hsync_end) {
    vga_hsync_next := False
    vga_odd_next   := ~vga_odd_reg
  }
  when(reset_output_address) {
    output_address_next := B(0, 11 bits)
    vga_odd_next        := True
  }

  // Address mux
  linea_address := Mux(buffer_select_reg, input_address_reg, output_address_reg)
  lineb_address := Mux(~buffer_select_reg, input_address_reg, output_address_reg)

  // Hsync delay
  val hsync_delay = new DelayLine(128)
  hsync_delay.io.syncReset := False
  hsync_delay.io.dataIn    := vga_hsync_start
  hsync_delay.io.enable    := io.doubled_enable
  vga_hsync_end            := hsync_delay.io.dataOut

  // Display logic
  colour_next := colour_reg
  vsync_next  := vsync_reg
  hsync_next  := hsync_reg

  when(~io.VGA) {
    colour_next := io.colour_in
    when(io.COMPOSITE_ON_HSYNC) {
      hsync_next := ~io.csync_in
      vsync_next := True
    } otherwise {
      hsync_next := ~io.hsync_in
      vsync_next := ~io.vsync_in
    }
  } otherwise {
    when(~buffer_select_reg) {
      when(io.scanlines_on & vga_odd_reg) {
        colour_next(7 downto 4) := linea_out(7 downto 4)
        colour_next(3)          := False
        colour_next(2 downto 0) := linea_out(3 downto 1)
      } otherwise {
        colour_next := linea_out
      }
    } otherwise {
      when(io.scanlines_on & vga_odd_reg) {
        colour_next(7 downto 4) := lineb_out(7 downto 4)
        colour_next(3)          := False
        colour_next(2 downto 0) := lineb_out(3 downto 1)
      } otherwise {
        colour_next := lineb_out
      }
    }

    when(io.COMPOSITE_ON_HSYNC) {
      hsync_next := ~(vga_hsync_reg ^ io.vsync_in)
      vsync_next := True
    } otherwise {
      hsync_next := ~vga_hsync_reg
      vsync_next := ~io.vsync_in
    }
  }

  // Colour palette
  val palette4 = new GtiaPalette
  palette4.io.pal         := io.pal
  palette4.io.atariColour := colour_reg
  r_next := palette4.io.rNext
  g_next := palette4.io.gNext
  b_next := palette4.io.bNext

  // Output
  io.R     := r_reg(7 downto 8 - video_bits)
  io.G     := g_reg(7 downto 8 - video_bits)
  io.B     := b_reg(7 downto 8 - video_bits)
  io.VSYNC := vsync_reg
  io.HSYNC := hsync_reg
}
