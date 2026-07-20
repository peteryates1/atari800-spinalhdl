package atari800

import spinal.core._

// ============================================================================
// Phase 0 bring-up for the QMTech Wukong (XC7A100T): a SpinalHDL 1080p60
// colour-bar generator -> Digilent rgb2dvi -> on-board HDMI. Proves the
// SpinalHDL -> Vivado -> OSERDESE2 -> native 1080p60 flow before the Atari core
// is layered on. Completely parallel to the 10CL025 / ECP5 builds (own top,
// own generated SV, own Vivado project).
// ============================================================================

// Xilinx MMCM: 50 MHz -> 148.4375 MHz pixel clock (1080p60). Verilog blackbox
// (MMCME2_BASE); source added by the Vivado project.
class WukongHdmiMmcm extends BlackBox {
  setDefinitionName("wukong_hdmi_mmcm")
  val clk_in  = in  Bool()
  val clk_pix = out Bool()
  val locked  = out Bool()
}

// Digilent rgb2dvi (VHDL) wrapped with fixed generics: self-generates the 5x
// TMDS serial clock, active-low reset. VHDL blackbox; sources added by Vivado.
//   vid_pData = R[23:16], G[15:8], B[7:0]
class Rgb2dviWrapper extends BlackBox {
  setDefinitionName("rgb2dvi_wrapper")
  val PixelClk    = in  Bool()
  val aRst_n      = in  Bool()
  val vid_pData   = in  Bits(24 bits)
  val vid_pVDE    = in  Bool()
  val vid_pHSync  = in  Bool()
  val vid_pVSync  = in  Bool()
  val TMDS_Clk_p  = out Bool()
  val TMDS_Clk_n  = out Bool()
  val TMDS_Data_p = out Bits(3 bits)
  val TMDS_Data_n = out Bits(3 bits)
}

class Atari800Wukong1080Top extends Component {
  val io = new Bundle {
    val clk_in      = in  Bool()            // 50 MHz oscillator (M21)
    val tmds_clk_p  = out Bool()
    val tmds_clk_n  = out Bool()
    val tmds_data_p = out Bits(3 bits)
    val tmds_data_n = out Bits(3 bits)
  }
  noIoPrefix()

  val mmcm = new WukongHdmiMmcm
  mmcm.clk_in := io.clk_in

  // Pixel-clock domain, held in reset until the MMCM locks.
  val pixCd = ClockDomain(
    clock  = mmcm.clk_pix,
    reset  = !mmcm.locked,
    config = ClockDomainConfig(resetKind = ASYNC, resetActiveLevel = HIGH)
  )

  val pix = new ClockingArea(pixCd) {
    // CEA-861 1080p60 timing: 2200 x 1125, positive HSync / VSync.
    val H_ACT = 1920; val H_FP = 88; val H_SYNC = 44; val H_BP = 148
    val V_ACT = 1080; val V_FP = 4;  val V_SYNC = 5;  val V_BP = 36
    val H_TOT = H_ACT + H_FP + H_SYNC + H_BP   // 2200
    val V_TOT = V_ACT + V_FP + V_SYNC + V_BP   // 1125

    val hc = Reg(UInt(12 bits)) init 0
    val vc = Reg(UInt(11 bits)) init 0
    when(hc === H_TOT - 1) {
      hc := 0
      when(vc === V_TOT - 1) { vc := 0 } otherwise { vc := vc + 1 }
    } otherwise {
      hc := hc + 1
    }

    val de    = (hc < H_ACT) && (vc < V_ACT)
    val hSync = (hc >= H_ACT + H_FP) && (hc < H_ACT + H_FP + H_SYNC)
    val vSync = (vc >= V_ACT + V_FP) && (vc < V_ACT + V_FP + V_SYNC)

    // 8 colour bars, 256 px wide (hc[10:8]). R[23:16] G[15:8] B[7:0].
    val bars = Vec(Bits(24 bits), 8)
    bars(0) := B(0xFFFFFF, 24 bits)   // white
    bars(1) := B(0xFFFF00, 24 bits)   // yellow
    bars(2) := B(0x00FFFF, 24 bits)   // cyan
    bars(3) := B(0x00FF00, 24 bits)   // green
    bars(4) := B(0xFF00FF, 24 bits)   // magenta
    bars(5) := B(0xFF0000, 24 bits)   // red
    bars(6) := B(0x0000FF, 24 bits)   // blue
    bars(7) := B(0x000000, 24 bits)   // black
    val rgb = de ? bars(hc(10 downto 8)) | B(0, 24 bits)

    // One pixel-domain register before the encoder.
    val rgbR   = RegNext(rgb)   init 0
    val deR    = RegNext(de)    init False
    val hSyncR = RegNext(hSync) init False
    val vSyncR = RegNext(vSync) init False
  }

  val dvi = new Rgb2dviWrapper
  dvi.PixelClk   := mmcm.clk_pix
  dvi.aRst_n     := mmcm.locked
  // This board's rgb2dvi + TMDS pin mapping wants byte order {R, B, G} (QMTech's
  // proven top.v fed {video_r, video_b, video_g}); plain {R,G,B} shows green/blue
  // (and yellow/magenta) swapped. Reorder R ## B ## G here.
  dvi.vid_pData  := pix.rgbR(23 downto 16) ## pix.rgbR(7 downto 0) ## pix.rgbR(15 downto 8)
  dvi.vid_pVDE   := pix.deR
  dvi.vid_pHSync := pix.hSyncR
  dvi.vid_pVSync := pix.vSyncR

  io.tmds_clk_p  := dvi.TMDS_Clk_p
  io.tmds_clk_n  := dvi.TMDS_Clk_n
  io.tmds_data_p := dvi.TMDS_Data_p
  io.tmds_data_n := dvi.TMDS_Data_n
}

object Atari800Wukong1080Sv extends App {
  SpinalConfig(mode = SystemVerilog, targetDirectory = "generated")
    .generate(new Atari800Wukong1080Top)
}
