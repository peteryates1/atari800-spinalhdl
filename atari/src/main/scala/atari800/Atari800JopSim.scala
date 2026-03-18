package atari800

import spinal.core._
import spinal.lib._
import spinal.lib.bus.bmb._
import jop.system._
import jop.io._

object Atari800JopSim {
  // Board-specific config for simulation.
  // Matches ep4cgx150 hardware: 32 MB SDRAM, small JOP caches (simulation uses same sizing).
  // JOP:   physical bytes 0x000000 .. 0x7FFFFF  (lower 8 MB, loaded from .jop)
  // Atari: physical bytes 0x800000 .. 0xFFFFFF  (upper 8 MB)
  val boardConfig = AtariBoardConfig(
    sdramBytes     = 32L * 1024 * 1024,
    atariSdramBase = 0x800000L,
    jopConfig      = JopCoreForAtari.configForSim
  )
}

// Combined Atari 800 + JOP simulation wrapper.
//
// Structurally identical to Atari800JopTop but simulation-friendly:
//   - No PLL (uses implicit clock domain)
//   - No SdramStatemachine (arbiter SDRAM side exposed to testbench)
//   - No HDMI, no audio DAC (raw PCM out)
//   - No Analog inout signals
//   - VGA text clock driven externally from testbench
//
// JOP uses simulation boot config (pre-loaded SDRAM, no UART download wait).
// Atari runs normally with behavioral SDRAM model in testbench.
// Arbiter mediates all SDRAM access.
// Memory map defined in Atari800JopSim.boardConfig.
class Atari800JopSim(boardConfig: AtariBoardConfig = Atari800JopSim.boardConfig) extends Component {
  val io = new Bundle {
    // VGA output (scandoubled + overlay mux, 8-bit for sim)
    val vga_r     = out Bits(8 bits)
    val vga_g     = out Bits(8 bits)
    val vga_b     = out Bits(8 bits)
    val vga_hsync = out Bool()
    val vga_vsync = out Bool()

    // Raw Atari video (pre-scandoubler, 15kHz)
    val atariVideoVs = out Bool()
    val atariVideoHs = out Bool()

    // Atari scandoubled video (bypasses VGA mux, for frame capture)
    val atariVgaR     = out Bits(8 bits)
    val atariVgaG     = out Bits(8 bits)
    val atariVgaB     = out Bits(8 bits)
    val atariVgaHsync = out Bool()
    val atariVgaVsync = out Bool()

    // Audio PCM (raw 16-bit, no DAC)
    val audioL = out Bits(16 bits)
    val audioR = out Bits(16 bits)

    // Console buttons (active-high for sim convenience)
    val reset_btn  = in Bool()
    val option_btn = in Bool()
    val select_btn = in Bool()
    val start_btn  = in Bool()
    val joy1       = in Bits(5 bits)

    // SDRAM behavioral model interface (arbiter output side)
    val sdramRequest        = out Bool()
    val sdramComplete       = in  Bool()
    val sdramReadEnable     = out Bool()
    val sdramWriteEnable    = out Bool()
    val sdramAddr           = out Bits(25 bits)
    val sdramDi             = out Bits(32 bits)
    val sdramDo             = in  Bits(32 bits)
    val sdramByteAccess     = out Bool()
    val sdramWordAccess     = out Bool()
    val sdramLongwordAccess = out Bool()
    val sdramRefresh        = out Bool()

    // JOP UART (directly exposed for monitoring)
    val jopUartTx = out Bool()

    // VGA text pixel clock (25 MHz, driven from testbench)
    val vgaTextClk = in Bool()

    // Debug
    val led = out Bits(4 bits)

    // Debug: Atari raw SDRAM signals (before arbiter)
    val dbgAtariSdramReq      = out Bool()
    val dbgAtariSdramReadEn   = out Bool()
    val dbgAtariSdramWriteEn  = out Bool()
    val dbgAtariSdramAddr     = out Bits(23 bits)

    // Debug: JOP BMB/SDRAM bridge activity
    val dbgJopBmbCmdValid     = out Bool()
    val dbgJopBmbCmdReady     = out Bool()
    val dbgJopBmbRspValid     = out Bool()
    val dbgJopSdramReq        = out Bool()  // bmbBridge.io.request
    val dbgJopArbBActive      = out Bool()  // arbiter bActive (JOP owns SDRAM bus)

    // Debug: JOP pipeline state
    val dbgJopPc              = out UInt(10 bits)   // fetch stage PC (microcode address)
    val dbgJopMemBusy         = out Bool()           // memory controller busy
    val dbgJopMemState        = out UInt(5 bits)     // memory controller state machine
    val dbgJopIoRdCount       = out UInt(16 bits)    // I/O read counter
    val dbgJopIoWrCount       = out UInt(16 bits)    // I/O write counter
    val dbgJopExc             = out Bool()           // exception fired
    val dbgJopBcFillAddr      = out UInt(24 bits)    // JBC fill start address (word)
    val dbgJopBcRdCapture     = out Bits(32 bits)    // raw packed method struct word at bcRd
    val dbgJopBcFillLen       = out UInt(10 bits)    // JBC fill length (words)

    // Debug: Atari raw video (pre-scandoubler)
    val dbgVideoB     = out Bits(8 bits)
    val dbgVideoBlank = out Bool()

    // Debug: GTIA/ANTIC internals
    val dbgColbk        = out Bits(7 bits)
    val dbgVisibleLive  = out Bool()
    val dbgColourClock  = out Bool()
    val dbgAN           = out Bits(3 bits)
    val dbgGtiaWrEn     = out Bool()
    val dbgAnticWrEn    = out Bool()
    val dbgDmactl       = out Bits(7 bits)
  }

  // VGA text clock domain (25 MHz from testbench)
  val vgaTextDomain = ClockDomain(
    clock  = io.vgaTextClk,
    config = ClockDomainConfig(resetKind = BOOT)
  )

  // Enable dividers (same as real top-level)
  val colourEnable  = Reg(Bool()) init False
  val doubledEnable = Reg(Bool()) init False
  colourEnable := ~colourEnable
  when(colourEnable) { doubledEnable := ~doubledEnable }

  // =================================================================
  // JOP Soft-Core (direct instantiation, same config as real board)
  // =================================================================
  val jopCore = JopCore(config = boardConfig.jopConfig, romInit = Some(JopCoreForAtari.simRomInit), ramInit = Some(JopCoreForAtari.simRamInit), vgaCd = Some(vgaTextDomain))

  // Single core — tie off multicore signals
  jopCore.io.syncIn.halted := False
  jopCore.io.syncIn.s_out  := False
  jopCore.io.syncIn.status := False
  jopCore.io.snoopIn.foreach { si =>
    si.valid := False; si.isArray := False; si.handle := 0; si.index := 0
  }
  jopCore.io.debugHalt    := False
  jopCore.io.debugRamAddr := 0

  // AtariCtrl device pins (same wiring as Atari800JopTop)
  val atariPins = jopCore.devicePins("atariCtrl")
  def atariPin[T <: Data](name: String): T =
    atariPins.elements.find(_._1 == name).get._2.asInstanceOf[T]

  atariPin[Bool]("pllLocked") := True  // sim: always locked

  // No physical cartridge slot in sim
  atariPin[Bits]("cartSlotData") := B(0xFF, 8 bits)
  atariPin[Bool]("cartSlotRd4")  := False  // no cartridge
  atariPin[Bool]("cartSlotRd5")  := False

  // =================================================================
  // Atari 800 Core
  // =================================================================
  val atariCore = new Atari800CoreSimpleSdram(
    cycle_length = 32,
    video_bits   = 8,
    palette      = 0,
    internal_rom = 1,
    internal_ram = 16384,
    low_memory   = 0,
    stereo       = 1,
    covox        = 1
  )

  // Raw video out (pre-scandoubler)
  io.atariVideoVs := atariCore.io.VIDEO_VS
  io.atariVideoHs := atariCore.io.VIDEO_HS

  // Audio PCM
  io.audioL := atariCore.io.AUDIO_L
  io.audioR := atariCore.io.AUDIO_R

  // Joysticks: merge hardware with JOP overrides (AND, active low)
  atariCore.io.JOY1_n := io.joy1 & atariPin[Bits]("joy1_n")
  atariCore.io.JOY2_n := B"11111" & atariPin[Bits]("joy2_n")
  atariCore.io.JOY3_n := B"11111" & atariPin[Bits]("joy3_n")
  atariCore.io.JOY4_n := B"11111" & atariPin[Bits]("joy4_n")

  // Paddles (from JOP AtariCtrl)
  atariCore.io.PADDLE0 := atariPin[SInt]("paddle0")
  atariCore.io.PADDLE1 := atariPin[SInt]("paddle1")
  atariCore.io.PADDLE2 := atariPin[SInt]("paddle2")
  atariCore.io.PADDLE3 := atariPin[SInt]("paddle3")
  atariCore.io.PADDLE4 := atariPin[SInt]("paddle4")
  atariCore.io.PADDLE5 := atariPin[SInt]("paddle5")
  atariCore.io.PADDLE6 := atariPin[SInt]("paddle6")
  atariCore.io.PADDLE7 := atariPin[SInt]("paddle7")

  // Keyboard
  atariCore.io.KEYBOARD_RESPONSE := atariPin[Bits]("keyboardResponse")

  // SIO (unused)
  atariCore.io.SIO_RXD := True

  // Console buttons
  atariCore.io.CONSOL_OPTION := io.option_btn
  atariCore.io.CONSOL_SELECT := io.select_btn
  atariCore.io.CONSOL_START  := io.start_btn

  // Config from AtariCtrl (defaults: PAL, 64K RAM, XL/XE mode)
  atariCore.io.RAM_SELECT                := atariPin[Bits]("ramSelect")
  atariCore.io.PAL                       := atariPin[Bool]("pal")
  atariCore.io.HALT                      := False
  atariCore.io.TURBO_VBLANK_ONLY         := atariPin[Bool]("turboVblankOnly")
  atariCore.io.THROTTLE_COUNT_6502       := atariPin[Bits]("throttleCount")
  atariCore.io.emulated_cartridge_select := atariPin[Bits]("cartSelect")
  atariCore.io.freezer_enable            := False
  atariCore.io.freezer_activate          := False
  atariCore.io.atari800mode              := atariPin[Bool]("atari800mode")
  atariCore.io.HIRES_ENA                 := atariPin[Bool]("hiresEna")

  // No DMA
  atariCore.io.DMA_FETCH              := False
  atariCore.io.DMA_READ_ENABLE        := False
  atariCore.io.DMA_32BIT_WRITE_ENABLE := False
  atariCore.io.DMA_16BIT_WRITE_ENABLE := False
  atariCore.io.DMA_8BIT_WRITE_ENABLE  := False
  atariCore.io.DMA_ADDR               := B(0, 24 bits)
  atariCore.io.DMA_WRITE_DATA         := B(0, 32 bits)

  // =================================================================
  // BmbToSdramReq — JOP BMB -> SDRAM request protocol
  // =================================================================
  val bmbBridge = BmbToSdramReq(boardConfig.jopConfig.memConfig.bmbParameter)
  bmbBridge.io.bmb <> jopCore.io.bmb

  // =================================================================
  // SDRAM Arbiter — Atari (priority) + JOP
  // =================================================================
  val arbiter = new SdramArbiter

  // SDRAM memory map (from boardConfig):
  //   0x000000 .. atariSdramBase-1  JOP code + heap (loaded from .jop at byte 0)
  //   atariSdramBase .. end         Atari RAM + ROM images
  //
  // Atari's 23-bit SDRAM_ADDR is prefixed with boardConfig.atariAddrPrefix to
  // form the 24-bit arbiter port-A address.

  // Port A: Atari core (high priority) — offset to boardConfig.atariSdramBase
  arbiter.io.a.request        := atariCore.io.SDRAM_REQUEST
  arbiter.io.a.readEnable     := atariCore.io.SDRAM_READ_ENABLE
  arbiter.io.a.writeEnable    := atariCore.io.SDRAM_WRITE_ENABLE
  arbiter.io.a.addr           := B(boardConfig.atariAddrPrefix, 1 bit) ## atariCore.io.SDRAM_ADDR
  arbiter.io.a.dataIn         := atariCore.io.SDRAM_DI
  arbiter.io.a.byteAccess     := atariCore.io.SDRAM_8BIT_WRITE_ENABLE
  arbiter.io.a.wordAccess     := atariCore.io.SDRAM_16BIT_WRITE_ENABLE
  arbiter.io.a.longwordAccess := atariCore.io.SDRAM_32BIT_WRITE_ENABLE
  arbiter.io.a.refresh        := atariCore.io.SDRAM_REFRESH

  // Port B: JOP (via BmbToSdramReq bridge) — lower 8MB (bit 23 = 0, direct)
  arbiter.io.b.request        := bmbBridge.io.request
  arbiter.io.b.readEnable     := bmbBridge.io.readEnable
  arbiter.io.b.writeEnable    := bmbBridge.io.writeEnable
  arbiter.io.b.addr           := bmbBridge.io.addr
  arbiter.io.b.dataIn         := bmbBridge.io.dataIn
  arbiter.io.b.byteAccess     := bmbBridge.io.byteAccess
  arbiter.io.b.wordAccess     := bmbBridge.io.wordAccess
  arbiter.io.b.longwordAccess := bmbBridge.io.longwordAccess
  bmbBridge.io.complete       := arbiter.io.b.complete
  bmbBridge.io.dataOut        := arbiter.io.b.dataOut

  atariCore.io.SDRAM_REQUEST_COMPLETE := arbiter.io.a.complete
  atariCore.io.SDRAM_DO               := arbiter.io.a.dataOut

  // Arbiter -> testbench behavioral SDRAM
  io.sdramRequest        := arbiter.io.sdram.request
  io.sdramReadEnable     := arbiter.io.sdram.readEnable
  io.sdramWriteEnable    := arbiter.io.sdram.writeEnable
  io.sdramAddr           := arbiter.io.sdram.addr
  io.sdramDi             := arbiter.io.sdram.dataIn
  io.sdramByteAccess     := arbiter.io.sdram.byteAccess
  io.sdramWordAccess     := arbiter.io.sdram.wordAccess
  io.sdramLongwordAccess := arbiter.io.sdram.longwordAccess
  io.sdramRefresh        := arbiter.io.sdram.refresh
  arbiter.io.sdram.complete := io.sdramComplete
  arbiter.io.sdram.dataOut  := io.sdramDo

  // =================================================================
  // SPI — tie off (no SD card in sim)
  // =================================================================
  jopCore.devicePin[Bool]("sdSpi", "miso") := True
  jopCore.devicePin[Bool]("sdSpi", "cd")   := True  // card always present

  // UART
  io.jopUartTx := jopCore.devicePin[Bool]("uart", "txd")
  jopCore.devicePin[Bool]("uart", "rxd") := True  // idle high

  // =================================================================
  // Scandoubler: 15kHz Atari -> 31kHz VGA
  // =================================================================
  val scandoubler = new Scandoubler(video_bits = 8)
  scandoubler.io.VGA                := True
  scandoubler.io.COMPOSITE_ON_HSYNC := False
  scandoubler.io.colour_enable      := colourEnable
  scandoubler.io.doubled_enable     := doubledEnable
  scandoubler.io.scanlines_on       := False
  scandoubler.io.pal                := atariPin[Bool]("pal")
  scandoubler.io.colour_in          := atariCore.io.VIDEO_B
  scandoubler.io.vsync_in           := atariCore.io.VIDEO_VS
  scandoubler.io.hsync_in           := atariCore.io.VIDEO_HS
  scandoubler.io.csync_in           := atariCore.io.VIDEO_CS

  // =================================================================
  // VGA Overlay Mux — Atari video / JOP OSD text
  // =================================================================
  val vgaMux = new VgaOverlayMux
  vgaMux.io.osdEnable := atariPin[Bool]("osdEnable")

  // Atari scandoubler -> mux
  vgaMux.io.atariR     := scandoubler.io.R
  vgaMux.io.atariG     := scandoubler.io.G
  vgaMux.io.atariB     := scandoubler.io.B
  vgaMux.io.atariHsync := scandoubler.io.HSYNC
  vgaMux.io.atariVsync := scandoubler.io.VSYNC

  // JOP VGA text -> mux
  vgaMux.io.jopR     := jopCore.devicePin[Bits]("vgaText", "vgaR")
  vgaMux.io.jopG     := jopCore.devicePin[Bits]("vgaText", "vgaG")
  vgaMux.io.jopB     := jopCore.devicePin[Bits]("vgaText", "vgaB")
  vgaMux.io.jopHsync := jopCore.devicePin[Bool]("vgaText", "vgaHsync")
  vgaMux.io.jopVsync := jopCore.devicePin[Bool]("vgaText", "vgaVsync")

  // VGA output (after mux — shows OSD when enabled)
  io.vga_r     := vgaMux.io.r
  io.vga_g     := vgaMux.io.g
  io.vga_b     := vgaMux.io.b
  io.vga_hsync := vgaMux.io.hsync
  io.vga_vsync := vgaMux.io.vsync

  // Atari-only scandoubled output (bypasses OSD mux, for direct capture)
  io.atariVgaR     := scandoubler.io.R
  io.atariVgaG     := scandoubler.io.G
  io.atariVgaB     := scandoubler.io.B
  io.atariVgaHsync := scandoubler.io.HSYNC
  io.atariVgaVsync := scandoubler.io.VSYNC

  // =================================================================
  // LEDs
  // =================================================================
  io.led(0) := True  // PLL locked (always in sim)
  io.led(1) := ~atariPin[Bool]("osdEnable")
  io.led(2) := arbiter.io.sdram.request
  io.led(3) := jopCore.devicePin[Bool]("uart", "txd")

  // =================================================================
  // Debug: JOP BMB/SDRAM bridge activity
  io.dbgJopBmbCmdValid := bmbBridge.io.bmb.cmd.valid
  io.dbgJopBmbCmdReady := bmbBridge.io.bmb.cmd.ready
  io.dbgJopBmbRspValid := bmbBridge.io.bmb.rsp.valid
  io.dbgJopSdramReq    := bmbBridge.io.request
  io.dbgJopArbBActive  := arbiter.io.bActive
  io.dbgJopPc          := jopCore.io.pc.resized
  io.dbgJopMemBusy     := jopCore.io.memBusy
  io.dbgJopMemState    := jopCore.io.debugMemState
  io.dbgJopIoRdCount   := jopCore.io.debugIoRdCount
  io.dbgJopIoWrCount   := jopCore.io.debugIoWrCount
  io.dbgJopExc         := jopCore.io.debugExc
  io.dbgJopBcFillAddr  := jopCore.io.debugBcFillAddr
  io.dbgJopBcRdCapture := jopCore.io.debugBcRdCapture
  io.dbgJopBcFillLen   := jopCore.io.debugBcFillLen

  // Debug: Atari raw SDRAM + arbiter state
  // =================================================================
  io.dbgAtariSdramReq      := atariCore.io.SDRAM_REQUEST
  io.dbgAtariSdramReadEn   := atariCore.io.SDRAM_READ_ENABLE
  io.dbgAtariSdramWriteEn  := atariCore.io.SDRAM_WRITE_ENABLE
  io.dbgAtariSdramAddr     := atariCore.io.SDRAM_ADDR
  io.dbgVideoB             := atariCore.io.VIDEO_B
  io.dbgVideoBlank         := atariCore.io.VIDEO_BLANK
  io.dbgColbk              := atariCore.io.dbgColbk
  io.dbgVisibleLive        := atariCore.io.dbgVisibleLive
  io.dbgColourClock        := atariCore.io.dbgColourClock
  io.dbgAN                 := atariCore.io.dbgAN
  io.dbgGtiaWrEn           := atariCore.io.dbgGtiaWrEn
  io.dbgAnticWrEn          := atariCore.io.dbgAnticWrEn
  io.dbgDmactl             := atariCore.io.dbgDmactl
}
