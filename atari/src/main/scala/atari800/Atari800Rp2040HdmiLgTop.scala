package atari800

import spinal.core._
import spinal.lib._

// Atari 800 top for ATARI-800-QMTechCB-RP2040-STAMP-HDMI-LG base board
// with QMTECH 10CL025 core (Cyclone 10 LP).
//
// vs Atari800LgV1Top:
//  * VGA  → HDMI (DvidOut TMDS encoder + DDR serializer, bank-4 cluster)
//  * CH376T USB keyboard removed; keyboard data will come from the RP2040
//    over an SPI slave port (stubbed here — sanity-check pass).
//  * Adds RP2040 ↔ SD card SPI-mode pass-through (4 lines + card detect).
//  * Adds RP2040 ↔ Raspberry Pi Radio Module 2 SPI pass-through
//    (4 lines + IRQ + WIFI_ON + BT_ON).
//  * 13-line general-purpose RP2040 GPIO bus (most carry the SD/RM2 pass-
//    through; rest are spare).
//  * Single core LED (no base-board LED bus).
//
// Clock plan (3-output ALTPLL atari_pll, 50 MHz in):
//  c0  = 56.67 MHz  Atari system clock (×17/÷15, unchanged from V1.1)
//  c1  = 28.33 MHz  HDMI pixel clock (sys/2)
//  c2  = 141.67 MHz HDMI TMDS clock (5× pixel for DDR 10-bit serialize)
class Atari800Rp2040HdmiLgTop extends Component {
  val io = new Bundle {
    val clk_in = in Bool()

    // ----- HDMI (4× TMDS pairs, pseudo-differential LVCMOS33) -----
    val hdmi_clk_p = out Bool()
    val hdmi_clk_n = out Bool()
    val hdmi_d0_p  = out Bool()
    val hdmi_d0_n  = out Bool()
    val hdmi_d1_p  = out Bool()
    val hdmi_d1_n  = out Bool()
    val hdmi_d2_p  = out Bool()
    val hdmi_d2_n  = out Bool()

    // ----- Audio (sigma-delta 1-bit DAC) -----
    val audio_l = out Bool()
    val audio_r = out Bool()

    // ----- Joystick 1 / 2 (active low DB9) -----
    val joy1Up    = in Bool()
    val joy1Down  = in Bool()
    val joy1Left  = in Bool()
    val joy1Right = in Bool()
    val joy1Fire  = in Bool()
    val joy2Up    = in Bool()
    val joy2Down  = in Bool()
    val joy2Left  = in Bool()
    val joy2Right = in Bool()
    val joy2Fire  = in Bool()

    // ----- Console switches (active low) -----
    val consolOption = in Bool()
    val consolSelect = in Bool()
    val consolStart  = in Bool()
    val consolReset  = in Bool()

    // ----- SD card (J7, SDIO 4-bit wiring; used in SPI mode here) -----
    val sd_clk    = out Bool()
    val sd_cmd    = out Bool()                // MOSI
    val sd_dat0   = in  Bool()                // MISO
    val sd_dat1   = in  Bool()                // unused in SPI mode
    val sd_dat2   = in  Bool()                // unused in SPI mode
    val sd_dat3   = out Bool()                // CS
    val sd_cd     = in  Bool()                // card detect

    // ----- Raspberry Pi Radio Module 2 (U6) -----
    val rm2_sck     = out Bool()
    val rm2_mosi    = out Bool()
    val rm2_miso    = in  Bool()
    val rm2_cs      = out Bool()
    val rm2_irq_n   = in  Bool()
    val rm2_wifi_on = out Bool()
    val rm2_bt_on   = out Bool()

    // ----- RP2040 ↔ FPGA dedicated SPI slave port -----
    val rp_sck  = in  Bool()
    val rp_mosi = in  Bool()
    val rp_csn  = in  Bool()
    val rp_miso = out Bool()

    // ----- RP2040 GPIO bus (named per RP2040 GPIO number) -----
    //   GPIO4/5: RM2 enable lines (in to FPGA, out to RM2)
    //   GPIO10..13: SD SPI lines (CLK/MOSI/MISO/CS)
    //   GPIO14: SD card-detect (FPGA out → RP2040)
    //   GPIO15: spare
    //   GPIO20..24: RM2 SPI lines (CLK/MOSI/MISO/CS/IRQ)
    //   GPIO25: spare
    val rp_gpio4_in  = in  Bool()             // → rm2_bt_on
    val rp_gpio5_in  = in  Bool()             // → rm2_wifi_on
    val rp_gpio10_in = in  Bool()             // → sd_clk
    val rp_gpio11_in = in  Bool()             // → sd_cmd (MOSI)
    val rp_gpio12_out = out Bool()            // ← sd_dat0 (MISO)
    val rp_gpio13_in = in  Bool()             // → sd_dat3 (CS)
    val rp_gpio14_out = out Bool()            // ← sd_cd
    val rp_gpio15_out = out Bool()            // spare; drives 0
    val rp_gpio20_in = in  Bool()             // → rm2_sck
    val rp_gpio21_in = in  Bool()             // → rm2_mosi
    val rp_gpio22_out = out Bool()            // ← rm2_miso
    val rp_gpio23_in = in  Bool()             // → rm2_cs
    val rp_gpio24_out = out Bool()            // ← rm2_irq_n
    val rp_gpio25_out = out Bool()            // spare; drives 0

    // ----- Core-board user LED -----
    val led_core = out Bits(1 bits)

    // ----- SDRAM (QMTech 10CL025 on-module, 16-bit) -----
    val sdram_clk  = out Bool()
    val sdram_cke  = out Bool()
    val sdram_csn  = out Bool()
    val sdram_rasn = out Bool()
    val sdram_casn = out Bool()
    val sdram_wen  = out Bool()
    val sdram_ba   = out Bits(2 bits)
    val sdram_addr = out Bits(13 bits)
    val sdram_dqm  = out Bits(2 bits)
    val sdram_dq   = inout(Analog(Bits(16 bits)))
  }

  // =========================================================================
  // 3-output ALTPLL: 50 MHz → 56.67 / 28.33 / 141.67 MHz
  // =========================================================================
  val pll = new AtariPll
  pll.io.areset := False
  pll.io.inclk0 := io.clk_in

  val clkSys   = pll.io.c0      // 56.67 MHz Atari system

  // HDMI PLL (pll_hdmi.v): 50 MHz -> 74.25 MHz pixel + 371.25 MHz TMDS (720p).
  val hdmiPll = new PllHdmi
  hdmiPll.inclk0 := io.clk_in
  val clkPixel = hdmiPll.c0     // 74.25 MHz 720p pixel clock
  val clkTmds  = hdmiPll.c1     // 371.25 MHz TMDS (5x pixel)

  // SDRAM clocks from the SAME PLL as the Atari sys clock (2x, phase-locked):
  // every sys<->sdram crossing in SdramStatemachine is then a TIMED path that
  // STA must close, instead of an unverifiable async crossing. (Two separate
  // PLLs left the whole controller CDC surface untimed - the last hiding
  // place for the rare RAM-corruption crashes.)
  val clkSdram = pll.io.c1       // 115.38 MHz controller clock (2x sys)
  io.sdram_clk := pll.io.c2      // 115.38 MHz @ 180deg to the SDRAM chip
  val pllLocked = pll.io.locked

  // System reset: high when PLL locks and the console-reset button is unpressed.
  // The RELEASE must be synchronised to clkSys: a raw async release lets each
  // register leave reset on a different clock edge (reset-tree routing skew),
  // so state machines (SDRAM POR, arbiter, core) start inconsistent - boot
  // corruption that varied per BUILD (routing) and per BUTTON PRESS (phase).
  val rpResetReq = Bool()   // supervisor reset request (assigned in sysArea)
  val sysResetRawN = pllLocked & io.consolReset
  val rstSyncArea = new ClockingArea(ClockDomain(clkSys, config = ClockDomainConfig(resetKind = BOOT))) {
    val r0 = RegNext(sysResetRawN) init False addTag(crossClockDomain)
    val r1 = RegNext(r0) init False
    // Supervisor reset: the request register lives in the sys domain and is
    // cleared by the very reset it triggers, so stretch it here (~1.1 ms,
    // like a button press) in the un-resettable BOOT domain.
    val rpReq  = BufferCC(rpResetReq, False)
    val rpHold = Reg(UInt(17 bits)) init 0
    when(rpReq) { rpHold := U(0x1FFFF) }
      .elsewhen(rpHold =/= 0) { rpHold := rpHold - 1 }
    val rstN = sysResetRawN & r1 & (rpHold === 0)   // async assert, sync release
  }
  val sysResetN = rstSyncArea.rstN
  val sysDomain = ClockDomain(
    clock  = clkSys,
    reset  = sysResetN,
    config = ClockDomainConfig(
      clockEdge        = RISING,
      resetKind        = ASYNC,
      resetActiveLevel = LOW
    )
  )

  val sysArea = new ClockingArea(sysDomain) {

    val colourEnable  = Reg(Bool()) init False
    val doubledEnable = Reg(Bool()) init False
    colourEnable := ~colourEnable
    when(colourEnable) { doubledEnable := ~doubledEnable }

    // -----------------------------------------------------------------
    // Atari core — same configuration as V1.1's Star Raiders build.
    // -----------------------------------------------------------------
    // internal_rom = 0: OS (and cartridge) come from SDRAM, loaded by the
    // RP2040 supervisor from SD card before it releases the Atari's reset.
    // SDRAM map (AddressDecoder, low_memory=0): OS window 0x704000 (16 KB,
    // offset = atariAddr[13:0] -> os2 @ 0x705800, osb @ 0x706000), BASIC
    // 0x700000, emulated cartridge region 0x500000. Nothing proprietary
    // remains in the bitstream.
    val atari = new Atari800CoreSimpleSdram(
      cycle_length   = 32,
      video_bits     = 8,
      palette        = 0,
      internal_rom   = 5,          // 800 OS in BLANK loadable BRAM (SD-loaded, not embedded)
      internal_ram   = 49152,      // 48 KB Atari RAM in BRAM (blank, non-proprietary)
      basic_in_sdram = false,      //   -> ANTIC display DMA off the contended SDRAM
      cartridge_rom  = ""
    )

    atari.io.PAL                       := True
    atari.io.RAM_SELECT                := B"011"
    // atari.io.HALT is driven below (held until SDRAM init completes)
    atari.io.TURBO_VBLANK_ONLY         := False
    atari.io.THROTTLE_COUNT_6502       := B(31, 6 bits)
    // emulated_cartridge_select driven from cfgArea (BOOT domain) below
    atari.io.freezer_enable            := False
    atari.io.freezer_activate          := False
    atari.io.atari800mode              := True
    atari.io.HIRES_ENA                 := False

    atari.io.JOY1_n := io.joy1Fire ## io.joy1Right ## io.joy1Left ## io.joy1Down ## io.joy1Up
    atari.io.JOY2_n := io.joy2Fire ## io.joy2Right ## io.joy2Left ## io.joy2Down ## io.joy2Up
    atari.io.JOY3_n := B"11111"
    atari.io.JOY4_n := B"11111"

    atari.io.PADDLE0 := S(0, 8 bits)
    atari.io.PADDLE1 := S(0, 8 bits)
    atari.io.PADDLE2 := S(0, 8 bits)
    atari.io.PADDLE3 := S(0, 8 bits)
    atari.io.PADDLE4 := S(0, 8 bits)
    atari.io.PADDLE5 := S(0, 8 bits)
    atari.io.PADDLE6 := S(0, 8 bits)
    atari.io.PADDLE7 := S(0, 8 bits)

    // Keyboard: not connected yet — will be driven by the RP2040 supervisor.
    // Keyboard from the RP2040 supervisor: raw HID boot reports over the
    // dedicated SPI link; mapping + response generation in RpAtariKeyboard.
    // so the Atari core doesn't see phantom inputs.
    val kbd = new RpAtariKeyboard
    kbd.io.spiSck  := io.rp_sck
    kbd.io.spiMosi := io.rp_mosi
    kbd.io.spiCsN  := io.rp_csn
    kbd.io.keyboardScan := atari.io.KEYBOARD_SCAN
    atari.io.KEYBOARD_RESPONSE := kbd.io.keyboardResponse

    // SIO disk drive emulator: the RP2040 monitors the SIO command bus and
    // injects drive responses through the SioBridge (hardware serializer),
    // driven over the same SPI link ('Q' write / 'S' read register access).
    val sioBridge = new SioBridge
    sioBridge.io.sioCommand  := atari.io.SIO_COMMAND
    sioBridge.io.sioTxd      := atari.io.SIO_TXD
    sioBridge.io.sioClockout := atari.io.SIO_CLOCKOUT
    atari.io.SIO_RXD         := sioBridge.io.sioRxd
    sioBridge.bus.addr   := kbd.io.sioAddr
    sioBridge.bus.rd     := kbd.io.sioRd
    sioBridge.bus.wr     := kbd.io.sioWr
    sioBridge.bus.wrData := kbd.io.sioWrData.resize(32)
    kbd.io.sioRdData     := sioBridge.bus.rdData

    atari.io.CONSOL_OPTION := ~io.consolOption | kbd.io.consolOption | kbd.io.ctrlOption
    atari.io.CONSOL_SELECT := ~io.consolSelect | kbd.io.consolSelect | kbd.io.ctrlSelect
    atari.io.CONSOL_START  := ~io.consolStart  | kbd.io.consolStart  | kbd.io.ctrlStart

    atari.io.DMA_FETCH              := False
    atari.io.DMA_READ_ENABLE        := False
    atari.io.DMA_32BIT_WRITE_ENABLE := False
    atari.io.DMA_16BIT_WRITE_ENABLE := False
    atari.io.DMA_8BIT_WRITE_ENABLE  := False
    atari.io.DMA_ADDR               := B(0, 24 bits)
    atari.io.DMA_WRITE_DATA         := B(0, 32 bits)

    // -----------------------------------------------------------------
    // SDRAM controller — Atari RAM lives in SDRAM (internal_ram=0).
    // Sole master (no JOP): the MiST/AC608 pattern. SdramStatemachine
    // handles the CLK_SYSTEM(57.69) <-> CLK_SDRAM(100) crossing internally.
    // -----------------------------------------------------------------
    // Hold controller reset low for SDRAM power-up (~568 us @57.69 MHz).
    val sdramPor = Reg(UInt(16 bits)) init 0
    when(sdramPor =/= sdramPor.maxValue) { sdramPor := sdramPor + 1 }

    // Geometry proven by the full-range SDRAM BIST (boards/.../sdram_test):
    // 13-bit rows, 9-bit columns, 32 MB. (The 10-column QMTech Test04 config
    // fails the BIST walk instantly - that reference is for a different
    // module variant.)
    val sdramCtrl = new SdramStatemachine(
      ADDRESS_WIDTH = 24, ROW_WIDTH = 13, COLUMN_WIDTH = 9, AP_BIT = 10
    )
    sdramCtrl.io.CLK_SYSTEM      := clkSys
    sdramCtrl.io.CLK_SDRAM       := clkSdram
    sdramCtrl.io.RESET_N         := sysResetN & sdramPor.msb
    // 3-port SDRAM arbiter: A = Atari RAM (priority), B = framebuffer write,
    // C = framebuffer read (720p scaler). B/C are latency-tolerant.
    val arb = new SdramArbiter3

    // Port A — Atari core
    // Step 3: the Atari is 100% in BRAM (RAM+OS+cart), so it never touches SDRAM
    // (verified: portA maxStall=0). Sever port A's data path - only its refresh
    // input is kept (below), for framebuffer retention. SDRAM is framebuffer-only.
    arb.io.a.request        := False
    arb.io.a.readEnable     := False
    arb.io.a.writeEnable    := False
    arb.io.a.addr           := B(0, 24 bits)
    arb.io.a.dataIn         := B(0, 32 bits)
    arb.io.a.byteAccess     := False
    arb.io.a.wordAccess     := False
    arb.io.a.longwordAccess := False
    // Refresh only during the Atari's VERTICAL blank. VIDEO_BLANK is high for a
    // per-line HBLANK stretch (~740 sys cyc) during visible lines, but stays
    // high across WHOLE scanlines during vertical blank - so a sustained-high
    // run is our "in VBLANK" flag. Refreshing there: no sprite DMA (no smear),
    // no visible-line framebuffer capture/display traffic to starve (no jitter),
    // and ~40 blank lines is ample to walk all 2048 used rows every frame.
    val vblankCnt = Reg(UInt(12 bits)) init 0
    when(atari.io.VIDEO_BLANK) { when(vblankCnt =/= U(vblankCnt.maxValue)) { vblankCnt := vblankCnt + 1 } }
      .otherwise { vblankCnt := 0 }
    arb.io.a.refresh := vblankCnt >= 2048
    // Atari SDRAM interface tied off: it never requests, but self-complete any
    // stray request so nothing could ever hang. Data unused.
    atari.io.SDRAM_REQUEST_COMPLETE := atari.io.SDRAM_REQUEST
    atari.io.SDRAM_DO               := B(0, 32 bits)

    // Port B — framebuffer write: capture raw Atari video (8-bit GTIA index)
    // fbBase must be ABOVE the Atari's RAM in SDRAM (internal_ram=0 -> RAM at
    // low addresses). 0x100000 = 1 MB, well clear of the Atari's 64 KB.
    val fbWrite = new VideoFbWrite(fbBase = 0x100000, width = 384, strideLog2 = 9, height = 288, addrWidth = 24, clearOnReset = true)
    fbWrite.io.enable    := BufferCC(sdramCtrl.io.reset_client_n, False)  // SDRAM chip init COMPLETE (not just controller reset release) && !kbd.io.ctrlHalt   // quiesce during supervisor loads
    // Sample at the Atari hi-res pixel rate (sys/8 ~ 7.2 MHz), phase-locked to
    // each line by hsync. colourEnable (sys/2 ~ 28.8 MHz) is 4x too fast: the
    // 384-wide buffer filled after ~96 real pixels (image squashed to the left).
    // Lock the capture strobe to the core's real hi-res pixel clock (one pulse
    // per displayed pixel) instead of a free-running sys/8 counter - the free
    // divider drifts within a line, so no fixed phase samples cleanly (residual
    // speckle on fine detail). pixPhase now selects a small settling delay
    // (cycles after the pixel-clock edge) so we sample VIDEO_B once it's stable.
    // DIAGNOSTIC: revert to fba3b03's free-running sys/8 capture strobe to test
    // whether the pixel-clock-locked strobe is the chunky-jitter source. (May
    // bring back the fine-detail "speckle" the hires clock was meant to cure.)
    val capHsPrev = Reg(Bool()) init False
    capHsPrev := atari.io.VIDEO_HS
    val pixDiv = Reg(UInt(3 bits)) init 0
    pixDiv := pixDiv + 1
    when(atari.io.VIDEO_HS && !capHsPrev) { pixDiv := 0 }
    fbWrite.io.pixStrobe := pixDiv === 7
    fbWrite.io.colour    := atari.io.VIDEO_B
    fbWrite.io.hsync     := atari.io.VIDEO_HS
    fbWrite.io.vsync     := atari.io.VIDEO_VS
    fbWrite.io.blank     := atari.io.VIDEO_BLANK
    arb.io.b.request        := fbWrite.io.wrReq
    fbWrite.io.wrComplete   := arb.io.b.complete
    arb.io.b.readEnable     := False
    arb.io.b.writeEnable    := True
    arb.io.b.addr           := fbWrite.io.wrAddr
    arb.io.b.dataIn         := fbWrite.io.wrData
    arb.io.b.byteAccess     := False
    arb.io.b.wordAccess     := False
    arb.io.b.longwordAccess := fbWrite.io.wrLong && !fbWrite.io.wrWide
    arb.io.b.wideAccess     := fbWrite.io.wrWide
    arb.io.b.wideIn         := fbWrite.io.wrWideData

    // Port C — framebuffer read/scaler (dual-clock: fetch in sys, output at pixel)
    val fbRead = new VideoFbRead2(
      srcW = 384, srcH = 288, strideLog2 = 9, fbBase = 0x100000,
      hActive = 1280, hFront = 110, hSync = 40, hBack = 220,
      vActive = 720,  vFront = 5,   vSync = 5,  vBack = 20, addrWidth = 24)
    fbRead.io.clkFetch := clkSys
    fbRead.io.clkPixel := clkPixel
    // Same ready condition as the SDRAM controller's reset: no fetch requests
    // until the arbiter + SDRAM are live (BufferCC inside fbRead syncs it).
    fbRead.io.enable   := BufferCC(sdramCtrl.io.reset_client_n, False)   // SDRAM chip init COMPLETE && !kbd.io.ctrlHalt
    arb.io.c.request        := fbRead.io.rdReq
    fbRead.io.rdComplete    := arb.io.c.complete
    arb.io.c.readEnable     := True
    arb.io.c.writeEnable    := False
    arb.io.c.addr           := fbRead.io.rdAddr
    arb.io.c.dataIn         := B(0, 32 bits)
    arb.io.c.byteAccess     := False
    arb.io.c.wordAccess     := False
    arb.io.c.longwordAccess := !fbRead.io.rdWide
    arb.io.c.wideAccess     := fbRead.io.rdWide
    fbRead.io.rdData        := arb.io.c.wideOut
    // Double buffering: display reads the buffer capture last completed.
    fbRead.io.readBuf       := fbWrite.io.readyBuf

    // Sticky probe: has the Atari (port A) ever addressed the upper SDRAM
    // regions (bit 22 set = OS/cart windows at 0x50xxxx/0x70xxxx)?
    val osRegion = arb.io.a.addr(20) && arb.io.a.addr(18)   // 0x14xxxx window
    val dbgStickyOsFetch = RegInit(False) setWhen (arb.io.a.request && osRegion)

    // Starvation meters, armed a moment after reset so boot transients don't
    // count: latch any fb-read late event / fb-write drop since arming.
    val meterArm = Reg(UInt(26 bits)) init 0
    when(meterArm =/= meterArm.maxValue) { meterArm := meterArm + 1 }
    val lateSync = BufferCC(fbRead.io.dbgLateTgl, False)   // pixel -> sys domain
    val dropSync = BufferCC(fbWrite.io.dbgDropTgl, False)
    val latePrev = RegNext(lateSync) init False
    val dropPrev = RegNext(dropSync) init False
    val lateCnt  = Reg(UInt(16 bits)) init 0
    val dropCnt  = Reg(UInt(16 bits)) init 0
    when(meterArm.msb && (lateSync ^ latePrev) && lateCnt =/= lateCnt.maxValue) { lateCnt := lateCnt + 1 }
    when(meterArm.msb && (dropSync ^ dropPrev) && dropCnt =/= dropCnt.maxValue) { dropCnt := dropCnt + 1 }
    val dbgStickyLate = lateCnt =/= 0
    val dbgStickyDrop = dropCnt =/= 0
    kbd.io.meterLate := lateCnt
    kbd.io.meterDrop := dropCnt

    // Port-A (Atari CPU+ANTIC) SDRAM stall meter: the arbiter cannot preempt an
    // in-flight (now wide, 8-beat) framebuffer transaction, so port A can be
    // blocked past a single-access time. Track the worst stall (cycles the
    // Atari waited for one SDRAM access) since arming - if it's ~a wide-burst
    // length, head-of-line blocking is starving ANTIC's display DMA.
    // Latch on the port-A request, count cycles until complete, record the
    // worst - the true stall the Atari (incl. ANTIC sprite DMA) sees, which
    // the previous "request && !complete" version missed (request is a pulse).
    val aWaiting = RegInit(False)
    val aWaitCnt = Reg(UInt(10 bits)) init 0
    val aMaxWait = Reg(UInt(10 bits)) init 0
    when(arb.io.a.request && !aWaiting) { aWaiting := True; aWaitCnt := 1 }
    when(aWaiting) {
      aWaitCnt := aWaitCnt + 1
      when(arb.io.a.complete) {
        aWaiting := False
        when(meterArm.msb && aWaitCnt > aMaxWait) { aMaxWait := aWaitCnt }
      }
    }
    kbd.io.aMaxWait := aMaxWait
    kbd.io.bbMinX := fbWrite.io.bbMinX
    kbd.io.bbMaxX := fbWrite.io.bbMaxX
    kbd.io.bbMinY := fbWrite.io.bbMinY
    kbd.io.bbMaxY := fbWrite.io.bbMaxY


    // Supervisor SDRAM loader -> arbiter port D (lowest priority)
    // Loader destination (kbd 'B' command): 0 = SDRAM (port D), 1 = BRAM OS-ROM,
    // 2 = BRAM RAM. BRAM targets drive the core's LOAD port instead of port D.
    val ldToBram = kbd.io.ldDest =/= B(0, 2 bits)
    // Step 3: port D (loader -> SDRAM) severed. OS/cart now load into BRAM via
    // the core LOAD port (ldDest 1/2); an SDRAM load (ldDest 0) would just
    // self-complete as a no-op. SDRAM is framebuffer-only.
    arb.io.d.request        := False
    arb.io.d.readEnable     := False
    arb.io.d.writeEnable    := False
    arb.io.d.addr           := B(0, 24 bits)
    arb.io.d.dataIn         := B(0, 32 bits)
    kbd.io.ldRdData         := B(0, 32 bits)
    arb.io.d.byteAccess     := False
    arb.io.d.wordAccess     := False
    arb.io.d.longwordAccess := False

    // Core BRAM load port (OS ROM / RAM), fed by the same loader when ldToBram.
    atari.io.LOAD_ENABLE     := ldToBram && kbd.io.ctrlHalt   // only drive the bus while halted
    atari.io.LOAD_TARGET_ROM := kbd.io.ldDest === B(1, 2 bits)
    atari.io.LOAD_ADDR       := kbd.io.ldAddr(21 downto 0)
    atari.io.LOAD_DATA       := kbd.io.ldData(7 downto 0)
    atari.io.LOAD_WE         := kbd.io.ldReq && kbd.io.ldWrite && ldToBram
    atari.io.LOAD_REQUEST    := kbd.io.ldReq && ldToBram
    kbd.io.ldComplete        := Mux(ldToBram, atari.io.LOAD_COMPLETE, kbd.io.ldReq)  // dest 0 = no-op self-complete


    // Arbiter -> SdramStatemachine
    sdramCtrl.io.READ_EN         := arb.io.sdram.readEnable
    sdramCtrl.io.WRITE_EN        := arb.io.sdram.writeEnable
    sdramCtrl.io.REQUEST         := arb.io.sdram.request
    sdramCtrl.io.BYTE_ACCESS     := arb.io.sdram.byteAccess
    sdramCtrl.io.WORD_ACCESS     := arb.io.sdram.wordAccess
    sdramCtrl.io.LONGWORD_ACCESS := arb.io.sdram.longwordAccess
    sdramCtrl.io.REFRESH         := arb.io.sdram.refresh
    sdramCtrl.io.ADDRESS_IN      := arb.io.sdram.addr
    sdramCtrl.io.WIDE_ACCESS     := arb.io.sdram.wideAccess
    sdramCtrl.io.WIDE_IN         := arb.io.sdram.wideIn
    arb.io.sdram.wideOut         := sdramCtrl.io.WIDE_OUT
    sdramCtrl.io.DATA_IN         := arb.io.sdram.dataIn
    arb.io.sdram.complete := sdramCtrl.io.COMPLETE
    arb.io.sdram.dataOut  := sdramCtrl.io.DATA_OUT

    // Hold the Atari CPU halted until SDRAM init completes so its first RAM
    // accesses (page zero / stack / OS RAM clear) don't hit uninitialised SDRAM.
    val sdramReady = BufferCC(sdramCtrl.io.reset_client_n, False)
    atari.io.HALT := ~sdramReady | kbd.io.ctrlHalt   // supervisor halts the 6502 during SDRAM loads

    // DEBUG: heartbeat that only ticks once SDRAM init completes.
    //   blink  => reset_client_n asserted (SDRAM init OK, Atari released)
    //   steady => init never completes (SDRAM/clocking/handshake problem)
    val dbgHb = Reg(UInt(24 bits)) init 0
    when(sdramReady) { dbgHb := dbgHb + 1 }

    // SDRAM pin wiring
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

    // Video now goes Atari -> VideoFbWrite -> SDRAM framebuffer -> VideoFbRead2
    // (720p scaler) -> GtiaPalette -> DvidOut. No scandoubler in the HDMI path.

    // -----------------------------------------------------------------
    // Audio: 16-bit signed → 1-bit sigma-delta
    // -----------------------------------------------------------------
    val sigmaDeltaL = Reg(UInt(17 bits)) init 0
    val sigmaDeltaR = Reg(UInt(17 bits)) init 0
    val audioUnsignedL = (atari.io.AUDIO_L.asUInt ^ U(0x8000, 16 bits)).resize(17)
    val audioUnsignedR = (atari.io.AUDIO_R.asUInt ^ U(0x8000, 16 bits)).resize(17)
    sigmaDeltaL := sigmaDeltaL(15 downto 0).resize(17) + audioUnsignedL
    sigmaDeltaR := sigmaDeltaR(15 downto 0).resize(17) + audioUnsignedR

    // Framebuffer read/scaler pixel-domain outputs (crossed to DvidOut below).
    val vidPix = fbRead.io.pix
    val vidDe  = fbRead.io.de
    val vidHs  = fbRead.io.hs
    val vidVs  = fbRead.io.vs
    vidPix.addTag(crossClockDomain)
    vidDe.addTag(crossClockDomain)
    vidHs.addTag(crossClockDomain)
    vidVs.addTag(crossClockDomain)
  }

  // Capture-window centring offset. Lives in a BOOT-reset domain (same clkSys,
  // but only reset at FPGA config) so it survives the Atari/console reset that
  // resets sysArea. Defaults centre the standard 320x192 playfield; the SPI 'G'
  // command (offsetWr pulse from the keyboard bridge, same clock) overrides it
  // — the hook a future SD config file writes through.
  val cfgArea = new ClockingArea(ClockDomain(clkSys, config = ClockDomainConfig(resetKind = BOOT))) {
    val hOff = Reg(UInt(9 bits)) init 4
    val vOff = Reg(UInt(9 bits)) init 21
    val cart = Reg(Bits(6 bits)) init 0        // 0 = no cart; set by supervisor 'X'
    val pixPhase = Reg(UInt(3 bits)) init 7    // capture sample phase (sweepable)
    when(sysArea.kbd.io.offsetWr) {
      hOff := sysArea.kbd.io.offsetH
      vOff := sysArea.kbd.io.offsetV
    }
    when(sysArea.kbd.io.cartWr) { cart := sysArea.kbd.io.cartSel }
    when(sysArea.kbd.io.pixPhaseWr) { pixPhase := sysArea.kbd.io.pixPhase }
  }
  sysArea.fbWrite.io.hStart := cfgArea.hOff
  sysArea.fbWrite.io.vSkip  := cfgArea.vOff
  sysArea.atari.io.emulated_cartridge_select := cfgArea.cart

  // =========================================================================
  // HDMI 720p output: the framebuffer scaler's 8-bit GTIA colour index goes
  // through GtiaPalette -> DvidOut (own pixel + TMDS clock domains). Video is
  // fully re-clocked through the SDRAM framebuffer, so no async shimmer.
  // =========================================================================
  // Palette + a pixel-domain pipeline register so DvidOut's TMDS encoder gets
  // REGISTERED RGB (as the reference does) — splits the cache-read + palette LUT
  // path from the 8b/10b encoder so both meet the 74.25 MHz pixel clock.
  val pixelArea = new ClockingArea(ClockDomain(clkPixel, config = ClockDomainConfig(resetKind = BOOT))) {
    // Real framebuffer path: palette the scaled Atari pixel; sync/DE come from
    // VideoFbRead2 (now active-low). One pixel-domain register so the encoder
    // gets registered RGB, matched by de/hs/vs delays.
    val palette = new GtiaPalette
    palette.io.atariColour := sysArea.vidPix
    palette.io.pal         := True
    val r  = RegNext(palette.io.rNext)
    val g  = RegNext(palette.io.gNext)
    val b  = RegNext(palette.io.bNext)
    val de = RegNext(sysArea.vidDe) init False
    val hs = RegNext(sysArea.vidHs) init False
    val vs = RegNext(sysArea.vidVs) init False
    // Power-on reset for the DVI encoder (held low ~256 pixel clocks).
    val por = Reg(UInt(9 bits)) init 0
    when(por =/= por.maxValue) { por := por + 1 }
    val rstN = por.msb
  }

  // Proven corecourse 720p encoder (replaces our DvidOut, which fails at 371 MHz).
  val dvi = new DviEncoder
  dvi.pixelclk   := clkPixel
  dvi.pixelclk5x := clkTmds
  dvi.rst_n      := pixelArea.rstN
  dvi.red_din    := pixelArea.r
  dvi.green_din  := pixelArea.g
  dvi.blue_din   := pixelArea.b
  dvi.hsync      := pixelArea.hs
  dvi.vsync      := pixelArea.vs
  dvi.de         := pixelArea.de

  io.hdmi_clk_p := dvi.tmds_clk_p
  io.hdmi_clk_n := dvi.tmds_clk_n
  io.hdmi_d0_p  := dvi.tmds_data_p(0)   // blue
  io.hdmi_d0_n  := dvi.tmds_data_n(0)
  io.hdmi_d1_p  := dvi.tmds_data_p(1)   // green
  io.hdmi_d1_n  := dvi.tmds_data_n(1)
  io.hdmi_d2_p  := dvi.tmds_data_p(2)   // red
  io.hdmi_d2_n  := dvi.tmds_data_n(2)

  // =========================================================================
  // RP2040 ↔ peripheral pass-throughs (direct combinational wires)
  // =========================================================================
  io.sd_clk          := io.rp_gpio10_in
  io.sd_cmd          := io.rp_gpio11_in
  io.sd_dat3         := io.rp_gpio13_in
  io.rm2_sck         := io.rp_gpio20_in
  io.rm2_mosi        := io.rp_gpio21_in
  io.rm2_cs          := io.rp_gpio23_in
  io.rm2_bt_on       := io.rp_gpio4_in
  io.rm2_wifi_on     := io.rp_gpio5_in

  io.rp_gpio12_out := io.sd_dat0                           // SD passthrough (MISO -> RP2040)
  io.rp_gpio14_out := io.sd_cd                             // SD passthrough (card detect -> RP2040)
  // RM2 return lines: complete the RP2040 <-> Radio Module 2 SPI pass-through so
  // the RP2040 can read the CYW43439 (previously these two carried framebuffer
  // bring-up debug for the logic analyzer, no longer needed).
  io.rp_gpio22_out := io.rm2_miso                          // RM2 MISO  -> RP2040 GPIO22
  io.rp_gpio24_out := io.rm2_irq_n                         // RM2 IRQ_n -> RP2040 GPIO24
  // Spare framebuffer debug outputs (free to repurpose):
  io.rp_gpio15_out := sysArea.dbgStickyLate                // GPIO15: fb READ ran late (cache underrun)
  io.rp_gpio25_out := sysArea.fbRead.io.dbgBeat            // GPIO25: spare

  // =========================================================================
  // RP2040 ↔ FPGA SPI slave: keyboard/control bridge (RpAtariKeyboard).
  // =========================================================================
  io.rp_miso := sysArea.kbd.io.spiMiso
  rpResetReq := sysArea.kbd.io.ctrlReset

  // -----------------------------------------------------------------
  // Audio out
  // -----------------------------------------------------------------
  io.audio_l := sysArea.sigmaDeltaL(16)
  io.audio_r := sysArea.sigmaDeltaR(16)

  // -----------------------------------------------------------------
  // Core LED — PLL lock status
  // -----------------------------------------------------------------
  // DEBUG: heartbeat off the 371.25 MHz TMDS clock — blink => clkTmds alive.
  val tmdsArea = new ClockingArea(ClockDomain(clkTmds, config = ClockDomainConfig(resetKind = BOOT))) {
    val hb = Reg(UInt(30 bits)) init 0
    hb := hb + 1
  }
  // DEBUG: LED = fb-write FIFO overflow (sticky). In debugFill mode writes are
  // continuous: LED ON => port-B writes never complete (arbiter/SDRAM stuck);
  // LED OFF => writes ARE draining, so the fault is the read/fetch side.
  io.led_core(0) := sysArea.fbWrite.io.overflow
}

object Atari800Rp2040HdmiLgSv extends App {
  SpinalConfig(
    mode            = SystemVerilog,
    targetDirectory = "generated"
  ).generate(new Atari800Rp2040HdmiLgTop)
}
