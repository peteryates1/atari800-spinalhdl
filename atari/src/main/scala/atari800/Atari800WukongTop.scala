package atari800

import spinal.core._
import spinal.lib._

// ============================================================================
// Phase 1: Atari 800 on the QMTech Wukong (XC7A100T) at native 1080p60.
//
// Standalone (no supervisor yet): the 800 OS + a cartridge are baked into BRAM
// (internal_rom=3), 48 KB Atari RAM in BRAM, so the machine boots on its own.
// Video is captured to the on-board W9825 SDRAM framebuffer and upscaled to
// 1920x1080 -> Digilent rgb2dvi -> HDMI. Reuses the LG pipeline as-is
// (SdramStatemachine / SdramArbiter3 / VideoFbWrite / VideoFbRead2 / GtiaPalette);
// the Xilinx layer is just MMCM clocking + rgb2dvi (from Phase 0).
//
// Board tunings that may need a hardware iteration: the SDRAM chip-clock phase
// (wukong_atari_mmcm CLKOUT2_PHASE) and the HSync/VSync polarity into rgb2dvi.
// ============================================================================

// MMCM: 50 MHz -> Atari sys (56.25) + SDRAM controller (112.5) + phase-shifted
// SDRAM chip clock. Verilog blackbox (source added by the Vivado project).
class WukongAtariMmcm extends BlackBox {
  setDefinitionName("wukong_atari_mmcm")
  val clk_in       = in  Bool()
  val clk_sys      = out Bool()
  val clk_sdram    = out Bool()
  val clk_sdram_ps = out Bool()
  val locked       = out Bool()
}

class Atari800WukongTop extends Component {
  val io = new Bundle {
    val clk_in      = in  Bool()             // 50 MHz oscillator (M21)
    val rst_n       = in  Bool()             // reset button, active-low (H7)
    // HDMI
    val tmds_clk_p  = out Bool()
    val tmds_clk_n  = out Bool()
    val tmds_data_p = out Bits(3 bits)
    val tmds_data_n = out Bits(3 bits)
    // W9825 SDRAM (16-bit)
    val sdram_clk   = out Bool()
    val sdram_cke   = out Bool()
    val sdram_csn   = out Bool()
    val sdram_rasn  = out Bool()
    val sdram_casn  = out Bool()
    val sdram_wen   = out Bool()
    val sdram_ba    = out Bits(2 bits)
    val sdram_addr  = out Bits(13 bits)
    val sdram_dqm   = out Bits(2 bits)
    val sdram_dq    = inout(Analog(Bits(16 bits)))
  }
  noIoPrefix()

  // ---- clocks ----
  val amc = new WukongAtariMmcm
  amc.clk_in := io.clk_in
  val clkSys   = amc.clk_sys
  val clkSdram = amc.clk_sdram
  io.sdram_clk := amc.clk_sdram_ps

  val vmc = new WukongHdmiMmcm            // Phase 0 MMCM: 50 -> 148.4375 pixel
  vmc.clk_in := io.clk_in
  val clkPixel = vmc.clk_pix

  // ---- system reset: async assert, release synced to clkSys ----
  val sysResetRawN = amc.locked & io.rst_n
  val rstSyncArea = new ClockingArea(ClockDomain(clkSys, config = ClockDomainConfig(resetKind = BOOT))) {
    val r0 = RegNext(sysResetRawN) init False addTag(crossClockDomain)
    val r1 = RegNext(r0) init False
    val rstN = sysResetRawN & r1
  }
  val sysDomain = ClockDomain(
    clock = clkSys, reset = rstSyncArea.rstN,
    config = ClockDomainConfig(clockEdge = RISING, resetKind = ASYNC, resetActiveLevel = LOW)
  )
  val sysResetN = rstSyncArea.rstN

  val sysArea = new ClockingArea(sysDomain) {
    // ---- Atari core: 800 OS + Star Raiders baked into BRAM, 48 KB RAM in BRAM ----
    val atari = new Atari800CoreSimpleSdram(
      cycle_length   = 32,
      video_bits     = 8,
      palette        = 0,
      internal_rom   = 3,                 // 800 OS embedded (+ cart below)
      internal_ram   = 49152,             // 48 KB RAM in BRAM
      basic_in_sdram = false,
      cartridge_rom  = "roms/Star Raiders.rom"
    )
    atari.io.PAL                := True
    atari.io.RAM_SELECT         := B"011"
    atari.io.TURBO_VBLANK_ONLY  := False
    atari.io.THROTTLE_COUNT_6502 := B(0, 6 bits)   // real 1.79 MHz
    atari.io.freezer_enable     := False
    atari.io.freezer_activate   := False
    atari.io.atari800mode       := True
    atari.io.HIRES_ENA          := False
    atari.io.emulated_cartridge_select := B(0, 6 bits)
    atari.io.LOAD_REQUEST       := False           // nothing to load (ROM baked)

    // No inputs yet (Phase 2 = supervisor). Joysticks/console idle-high, no key.
    atari.io.JOY1_n := B"11111"; atari.io.JOY2_n := B"11111"
    atari.io.JOY3_n := B"11111"; atari.io.JOY4_n := B"11111"
    atari.io.PADDLE0 := S(0, 8 bits); atari.io.PADDLE1 := S(0, 8 bits)
    atari.io.PADDLE2 := S(0, 8 bits); atari.io.PADDLE3 := S(0, 8 bits)
    atari.io.PADDLE4 := S(0, 8 bits); atari.io.PADDLE5 := S(0, 8 bits)
    atari.io.PADDLE6 := S(0, 8 bits); atari.io.PADDLE7 := S(0, 8 bits)
    atari.io.KEYBOARD_RESPONSE := B"11"            // no key pressed
    atari.io.CONSOL_OPTION := True
    atari.io.CONSOL_SELECT := True
    atari.io.CONSOL_START  := True
    atari.io.SIO_RXD := True                       // no SIO disk

    atari.io.DMA_FETCH := False
    atari.io.DMA_READ_ENABLE := False
    atari.io.DMA_32BIT_WRITE_ENABLE := False
    atari.io.DMA_16BIT_WRITE_ENABLE := False
    atari.io.DMA_8BIT_WRITE_ENABLE := False
    atari.io.DMA_ADDR := B(0, 24 bits)
    atari.io.DMA_WRITE_DATA := B(0, 32 bits)

    // Atari core's SDRAM port: unused (fully in BRAM), self-complete + zero data.
    atari.io.SDRAM_REQUEST_COMPLETE := atari.io.SDRAM_REQUEST
    atari.io.SDRAM_DO := B(0, 32 bits)

    // ---- SDRAM controller (W9825) + 3-port arbiter ----
    val sdramPor = Reg(UInt(16 bits)) init 0
    when(sdramPor =/= sdramPor.maxValue) { sdramPor := sdramPor + 1 }

    val sdramCtrl = new SdramStatemachine(ADDRESS_WIDTH = 24, ROW_WIDTH = 13, COLUMN_WIDTH = 9, AP_BIT = 10)
    sdramCtrl.io.CLK_SYSTEM := clkSys
    sdramCtrl.io.CLK_SDRAM  := clkSdram
    sdramCtrl.io.RESET_N    := sysResetN & sdramPor.msb

    val arb = new SdramArbiter3

    // Port A — unused Atari path; refresh only during vertical blank.
    arb.io.a.request := False; arb.io.a.readEnable := False; arb.io.a.writeEnable := False
    arb.io.a.addr := B(0, 24 bits); arb.io.a.dataIn := B(0, 32 bits)
    arb.io.a.byteAccess := False; arb.io.a.wordAccess := False; arb.io.a.longwordAccess := False
    val vblankCnt = Reg(UInt(12 bits)) init 0
    when(atari.io.VIDEO_BLANK) { when(vblankCnt =/= vblankCnt.maxValue) { vblankCnt := vblankCnt + 1 } }
      .otherwise { vblankCnt := 0 }
    arb.io.a.refresh := vblankCnt >= 2048

    val sdramReady = BufferCC(sdramCtrl.io.reset_client_n, False)
    atari.io.HALT := ~sdramReady

    // Port B — framebuffer write (capture raw 8-bit GTIA index).
    val fbWrite = new VideoFbWrite(fbBase = 0x100000, width = 384, strideLog2 = 9, height = 288, addrWidth = 24, clearOnReset = true)
    fbWrite.io.enable := sdramReady
    val capHsPrev = Reg(Bool()) init False
    capHsPrev := atari.io.VIDEO_HS
    val pixDiv = Reg(UInt(3 bits)) init 0
    pixDiv := pixDiv + 1
    when(atari.io.VIDEO_HS && !capHsPrev) { pixDiv := 0 }
    fbWrite.io.pixStrobe := pixDiv === 7
    fbWrite.io.colour := atari.io.VIDEO_B
    fbWrite.io.hsync  := atari.io.VIDEO_HS
    fbWrite.io.vsync  := atari.io.VIDEO_VS
    fbWrite.io.blank  := atari.io.VIDEO_BLANK
    fbWrite.io.hStart := U(4, 9 bits)
    fbWrite.io.vSkip  := U(21, 9 bits)
    arb.io.b.request := fbWrite.io.wrReq
    fbWrite.io.wrComplete := arb.io.b.complete
    arb.io.b.readEnable := False; arb.io.b.writeEnable := True
    arb.io.b.addr := fbWrite.io.wrAddr; arb.io.b.dataIn := fbWrite.io.wrData
    arb.io.b.byteAccess := False; arb.io.b.wordAccess := False
    arb.io.b.longwordAccess := fbWrite.io.wrLong && !fbWrite.io.wrWide
    arb.io.b.wideAccess := fbWrite.io.wrWide
    arb.io.b.wideIn := fbWrite.io.wrWideData

    // Port C — framebuffer read/scaler, upscaled to 1920x1080.
    val fbRead = new VideoFbRead2(
      srcW = 384, srcH = 288, strideLog2 = 9, fbBase = 0x100000,
      hActive = 1920, hFront = 88, hSync = 44, hBack = 148,
      vActive = 1080, vFront = 4,  vSync = 5,  vBack = 36, addrWidth = 24)
    fbRead.io.clkFetch := clkSys
    fbRead.io.clkPixel := clkPixel
    fbRead.io.enable   := sdramReady
    fbRead.io.readBuf  := fbWrite.io.readyBuf
    arb.io.c.request := fbRead.io.rdReq
    fbRead.io.rdComplete := arb.io.c.complete
    arb.io.c.readEnable := True; arb.io.c.writeEnable := False
    arb.io.c.addr := fbRead.io.rdAddr; arb.io.c.dataIn := B(0, 32 bits)
    arb.io.c.byteAccess := False; arb.io.c.wordAccess := False
    arb.io.c.longwordAccess := !fbRead.io.rdWide
    arb.io.c.wideAccess := fbRead.io.rdWide
    fbRead.io.rdData := arb.io.c.wideOut

    // Port D — unused (no supervisor loader).
    arb.io.d.request := False; arb.io.d.readEnable := False; arb.io.d.writeEnable := False
    arb.io.d.addr := B(0, 24 bits); arb.io.d.dataIn := B(0, 32 bits)
    arb.io.d.byteAccess := False; arb.io.d.wordAccess := False; arb.io.d.longwordAccess := False

    // Arbiter -> SdramStatemachine
    sdramCtrl.io.READ_EN := arb.io.sdram.readEnable
    sdramCtrl.io.WRITE_EN := arb.io.sdram.writeEnable
    sdramCtrl.io.REQUEST := arb.io.sdram.request
    sdramCtrl.io.BYTE_ACCESS := arb.io.sdram.byteAccess
    sdramCtrl.io.WORD_ACCESS := arb.io.sdram.wordAccess
    sdramCtrl.io.LONGWORD_ACCESS := arb.io.sdram.longwordAccess
    sdramCtrl.io.REFRESH := arb.io.sdram.refresh
    sdramCtrl.io.ADDRESS_IN := arb.io.sdram.addr
    sdramCtrl.io.WIDE_ACCESS := arb.io.sdram.wideAccess
    sdramCtrl.io.WIDE_IN := arb.io.sdram.wideIn
    arb.io.sdram.wideOut := sdramCtrl.io.WIDE_OUT
    sdramCtrl.io.DATA_IN := arb.io.sdram.dataIn
    arb.io.sdram.complete := sdramCtrl.io.COMPLETE
    arb.io.sdram.dataOut := sdramCtrl.io.DATA_OUT

    // SDRAM chip pins
    io.sdram_addr := sdramCtrl.io.SDRAM_ADDR
    io.sdram_ba   := sdramCtrl.io.SDRAM_BA1 ## sdramCtrl.io.SDRAM_BA0
    io.sdram_cke  := sdramCtrl.io.SDRAM_CKE
    io.sdram_csn  := sdramCtrl.io.SDRAM_CS_N
    io.sdram_rasn := sdramCtrl.io.SDRAM_RAS_N
    io.sdram_casn := sdramCtrl.io.SDRAM_CAS_N
    io.sdram_wen  := sdramCtrl.io.SDRAM_WE_N
    io.sdram_dqm  := sdramCtrl.io.SDRAM_udqm ## sdramCtrl.io.SDRAM_ldqm
    sdramCtrl.io.SDRAM_DQ_IN := io.sdram_dq
    when(sdramCtrl.io.SDRAM_DQ_OE) { io.sdram_dq := sdramCtrl.io.SDRAM_DQ_OUT }

    // scaler pixel-domain outputs, crossed to pixelArea below
    val vidPix = fbRead.io.pix; val vidDe = fbRead.io.de
    val vidHs  = fbRead.io.hs;  val vidVs = fbRead.io.vs
    Seq(vidPix, vidDe, vidHs, vidVs).foreach(_.addTag(crossClockDomain))
  }

  // ---- pixel domain: palette + register -> rgb2dvi ----
  val pixelArea = new ClockingArea(ClockDomain(clkPixel, config = ClockDomainConfig(resetKind = BOOT))) {
    val palette = new GtiaPalette
    palette.io.atariColour := sysArea.vidPix
    palette.io.pal := True
    val r  = RegNext(palette.io.rNext)
    val g  = RegNext(palette.io.gNext)
    val b  = RegNext(palette.io.bNext)
    val de = RegNext(sysArea.vidDe) init False
    val hs = RegNext(sysArea.vidHs) init False
    val vs = RegNext(sysArea.vidVs) init False
    val por = Reg(UInt(9 bits)) init 0
    when(por =/= por.maxValue) { por := por + 1 }
    val rstN = por.msb
  }

  val dvi = new Rgb2dviWrapper
  dvi.PixelClk := clkPixel
  dvi.aRst_n   := pixelArea.rstN
  // {R, B, G} byte order for this board; VideoFbRead2 sync is active-low so invert
  // for rgb2dvi (which wants active-high, per the Phase 0 colour-bar bring-up).
  dvi.vid_pData  := pixelArea.r ## pixelArea.b ## pixelArea.g
  dvi.vid_pVDE   := pixelArea.de
  dvi.vid_pHSync := ~pixelArea.hs
  dvi.vid_pVSync := ~pixelArea.vs

  io.tmds_clk_p  := dvi.TMDS_Clk_p
  io.tmds_clk_n  := dvi.TMDS_Clk_n
  io.tmds_data_p := dvi.TMDS_Data_p
  io.tmds_data_n := dvi.TMDS_Data_n
}

object Atari800WukongSv extends App {
  SpinalConfig(mode = SystemVerilog, targetDirectory = "generated")
    .generate(new Atari800WukongTop)
}
