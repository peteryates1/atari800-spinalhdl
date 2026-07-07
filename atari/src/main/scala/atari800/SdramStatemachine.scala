package atari800

import spinal.core._

class SdramStatemachine(
  ADDRESS_WIDTH : Int = 22,
  ROW_WIDTH     : Int = 12,
  AP_BIT        : Int = 10,
  COLUMN_WIDTH  : Int = 8
) extends Component {
  val io = new Bundle {
    val CLK_SYSTEM      = in  Bool()
    val CLK_SDRAM       = in  Bool()
    val RESET_N         = in  Bool()

    val DATA_IN         = in  Bits(32 bits)
    val ADDRESS_IN      = in  Bits((ADDRESS_WIDTH + 1) bits) // 1 extra bit for byte alignment
    val READ_EN         = in  Bool()
    val WRITE_EN        = in  Bool()
    val REQUEST         = in  Bool()
    val BYTE_ACCESS     = in  Bool()
    val WORD_ACCESS     = in  Bool()
    val LONGWORD_ACCESS = in  Bool()
    // 256-bit access: 8 sequential longwords (32-byte aligned) in ONE
    // transaction - one ACTIVATE, 8 back-to-back BL2 CAS bursts in the open
    // row, auto-precharge on the last. Same toggle handshake as every other
    // access; data crosses the domains as one wide snapshot.
    val WIDE_ACCESS     = in  Bool()   default(False)
    val WIDE_IN         = in  Bits(256 bits) default(B(0, 256 bits))
    val WIDE_OUT        = out Bits(256 bits)
    val REFRESH         = in  Bool()

    val COMPLETE        = out Bool()
    val DATA_OUT        = out Bits(32 bits)

    val SDRAM_ADDR      = out Bits(ROW_WIDTH bits)
    val SDRAM_DQ_IN     = in  Bits(16 bits)
    val SDRAM_DQ_OUT    = out Bits(16 bits)
    val SDRAM_DQ_OE     = out Bool()
    val SDRAM_BA0       = out Bool()
    val SDRAM_BA1       = out Bool()
    val SDRAM_CKE       = out Bool()
    val SDRAM_CS_N      = out Bool()
    val SDRAM_RAS_N     = out Bool()
    val SDRAM_CAS_N     = out Bool()
    val SDRAM_WE_N      = out Bool()
    val SDRAM_ldqm      = out Bool()
    val SDRAM_udqm      = out Bool()
    val reset_client_n  = out Bool()
  }

  // Helper function
  def repeat(n: Int, b: Bool): Bits = {
    val result = Bits(n bits)
    result.setAll()
    when(!b) { result.clearAll() }
    result
  }

  // SDRAM commands: CS_n, RAS_n, CAS_n, WE_n
  val sdram_command_inhibit          = B"1000"
  val sdram_command_no_operation     = B"0111"
  val sdram_command_device_burst_stop = B"0110"
  val sdram_command_read             = B"0101"
  val sdram_command_write            = B"0100"
  val sdram_command_bank_activate    = B"0011"
  val sdram_command_precharge        = B"0010"
  val sdram_command_mode_register    = B"0000"
  val sdram_command_refresh          = B"0001"

  // SDRAM states
  val sdram_state_powerup        = B"0000"
  val sdram_state_init           = B"0001"
  val sdram_state_idle           = B"0010"
  val sdram_state_refresh        = B"0011"
  val sdram_state_read           = B"0100"
  val sdram_state_write          = B"0101"
  val sdram_state_init_precharge = B"0110"
  val sdram_state_wide_read      = B"0111"
  val sdram_state_wide_write     = B"1000"

  // ---- SDRAM clock domain ----
  val sdramClockDomain = ClockDomain(
    clock = io.CLK_SDRAM,
    reset = io.RESET_N,
    config = ClockDomainConfig(
      clockEdge = RISING,
      resetKind = ASYNC,
      resetActiveLevel = LOW
    )
  )

  // ---- System clock domain ----
  val systemClockDomain = ClockDomain(
    clock = io.CLK_SYSTEM,
    reset = io.RESET_N,
    config = ClockDomainConfig(
      clockEdge = RISING,
      resetKind = ASYNC,
      resetActiveLevel = LOW
    )
  )

  // Signals between domains
  val command_next           = Bits(4 bits)
  val sdram_state_next       = Bits(4 bits)
  val sdram_state_reg        = Bits(4 bits)
  val delay_next             = Bits(16 bits)
  val delay_reg              = Bits(16 bits)
  val cycles_since_refresh_next = Bits(11 bits)
  val cycles_since_refresh_reg  = Bits(11 bits)
  val refresh_pending_next   = Bits(12 bits)
  val refresh_pending_reg    = Bits(12 bits)
  // Targeted-refresh row pointer. We only refresh the USED rows (Atari RAM +
  // the framebuffer, all in bank 0, top used row ~1679) instead of the chip's
  // whole 8192-row array. 11 bits => rows 0..2047, 4x fewer refreshes than the
  // blind auto-refresh, so the ANTIC/HBLANK-gated refresh can actually keep up.
  val refreshRow_next        = Bits(11 bits)
  val refreshRow_reg         = Bits(11 bits)
  val suggest_refresh        = Bool()
  val force_refresh          = Bool()
  val require_refresh        = Bool()
  val refreshing_now         = Bool()
  val idle_priority          = Bits(4 bits)
  val data_out_next          = Bits(32 bits)
  val data_out_reg           = Bits(32 bits)
  val reply_next             = Bool()
  val reply_reg              = Bool()

  // Capture inputs
  val DATA_IN_snext        = Bits(32 bits)
  val DATA_IN_sreg         = Bits(32 bits)
  val WIDE_IN_snext        = Bits(256 bits)
  val WIDE_IN_sreg         = Bits(256 bits)
  val WIDE_ACCESS_snext    = Bool()
  val WIDE_ACCESS_sreg     = Bool()
  val ADDRESS_IN_snext     = Bits(ADDRESS_WIDTH bits)
  val ADDRESS_IN_sreg      = Bits(ADDRESS_WIDTH bits)
  val READ_EN_snext        = Bool()
  val READ_EN_sreg         = Bool()
  val WRITE_EN_snext       = Bool()
  val WRITE_EN_sreg        = Bool()
  val dqm_mask_snext       = Bits(4 bits)
  val dqm_mask_sreg        = Bits(4 bits)
  val request_snext        = Bool()
  val request_sreg         = Bool()
  val refresh_snext        = Bool()
  val refresh_sreg         = Bool()

  // Slow clock output regs
  val DATA_OUT_snext       = Bits(32 bits)
  val DATA_OUT_sreg        = Bits(32 bits)
  val WIDE_OUT_snext       = Bits(256 bits)
  val WIDE_OUT_sreg        = Bits(256 bits)
  val wide_out_next        = Bits(256 bits)
  val wide_out_reg         = Bits(256 bits)
  val reply_snext          = Bool()
  val reply_sreg           = Bool()

  // SDRAM output registers
  val addr_next            = Bits(ROW_WIDTH bits)
  val dq_out_next          = Bits(16 bits)
  val dq_output_next       = Bool()
  val dq_in_next           = Bits(16 bits)
  val dq_in_reg            = Bits(16 bits)
  val ba_next              = Bits(2 bits)
  val cs_n_next            = Bool()
  val ras_n_next           = Bool()
  val cas_n_next           = Bool()
  val we_n_next            = Bool()
  val ldqm_next            = Bool()
  val udqm_next            = Bool()
  val cke_next             = Bool()

  val addr_reg             = Bits(ROW_WIDTH bits)
  val dq_out_reg           = Bits(16 bits)
  val dq_output_reg        = Bool()
  val ba_reg               = Bits(2 bits)
  val cs_n_reg             = Bool()
  val ras_n_reg            = Bool()
  val cas_n_reg            = Bool()
  val we_n_reg             = Bool()
  val ldqm_reg             = Bool()
  val udqm_reg             = Bool()
  val cke_reg              = Bool()

  val sdram_request_reg    = Bool()
  val sdram_request_next   = Bool()
  val reset_client_n_reg   = Bool()
  val reset_client_n_next  = Bool()

  // ---- SDRAM clock domain registers ----
  val sdramArea = new ClockingArea(sdramClockDomain) {
    val r_dq_in_reg       = Reg(Bits(16 bits)) init 0
    val r_sdram_state_reg = Reg(Bits(4 bits)) init sdram_state_init addTag(crossClockDomain)
    val r_delay_reg       = Reg(Bits(16 bits)) init 0
    val r_refresh_pending_reg = Reg(Bits(12 bits)) init 0
    val r_cycles_since_refresh_reg = Reg(Bits(11 bits)) init 0
    val r_refreshRow_reg  = Reg(Bits(11 bits)) init 0
    val r_data_out_reg    = Reg(Bits(32 bits)) init 0 addTag(crossClockDomain)
    val r_wide_out_reg    = Reg(Bits(256 bits)) init 0 addTag(crossClockDomain)
    val r_reply_reg       = Reg(Bool()) init False addTag(crossClockDomain)

    val r_addr_reg        = Reg(Bits(ROW_WIDTH bits)) init 0
    val r_dq_out_reg      = Reg(Bits(16 bits)) init 0
    val r_dq_output_reg   = Reg(Bool()) init False
    val r_ba_reg          = Reg(Bits(2 bits)) init 0
    val r_cs_n_reg        = Reg(Bool()) init False
    val r_ras_n_reg       = Reg(Bool()) init False
    val r_cas_n_reg       = Reg(Bool()) init False
    val r_we_n_reg        = Reg(Bool()) init False
    val r_ldqm_reg        = Reg(Bool()) init False
    val r_udqm_reg        = Reg(Bool()) init False
    val r_cke_reg         = Reg(Bool()) init False

    r_dq_in_reg       := dq_in_next
    r_sdram_state_reg := sdram_state_next
    r_delay_reg       := delay_next
    r_refresh_pending_reg := refresh_pending_next
    r_cycles_since_refresh_reg := cycles_since_refresh_next
    r_refreshRow_reg  := refreshRow_next
    r_data_out_reg    := data_out_next
    r_wide_out_reg    := wide_out_next
    r_reply_reg       := reply_next

    r_addr_reg        := addr_next
    r_dq_out_reg      := dq_out_next
    r_dq_output_reg   := dq_output_next
    r_ba_reg          := ba_next
    r_cs_n_reg        := cs_n_next
    r_ras_n_reg       := ras_n_next
    r_cas_n_reg       := cas_n_next
    r_we_n_reg        := we_n_next
    r_ldqm_reg        := ldqm_next
    r_udqm_reg        := udqm_next
    r_cke_reg         := cke_next

    // Export to cross-domain signals
    dq_in_reg                := r_dq_in_reg
    sdram_state_reg          := r_sdram_state_reg
    delay_reg                := r_delay_reg
    refresh_pending_reg      := r_refresh_pending_reg
    cycles_since_refresh_reg := r_cycles_since_refresh_reg
    refreshRow_reg    := r_refreshRow_reg
    data_out_reg             := r_data_out_reg
    wide_out_reg             := r_wide_out_reg
    reply_reg                := r_reply_reg
    addr_reg                 := r_addr_reg
    dq_out_reg               := r_dq_out_reg
    dq_output_reg            := r_dq_output_reg
    ba_reg                   := r_ba_reg
    cs_n_reg                 := r_cs_n_reg
    ras_n_reg                := r_ras_n_reg
    cas_n_reg                := r_cas_n_reg
    we_n_reg                 := r_we_n_reg
    ldqm_reg                 := r_ldqm_reg
    udqm_reg                 := r_udqm_reg
    cke_reg                  := r_cke_reg
  }

  // ---- System clock domain registers ----
  val systemArea = new ClockingArea(systemClockDomain) {
    val r_data_in_sreg    = Reg(Bits(32 bits)) init 0 addTag(crossClockDomain)
    val r_wide_in_sreg    = Reg(Bits(256 bits)) init 0 addTag(crossClockDomain)
    val r_wide_acc_sreg   = Reg(Bool()) init False addTag(crossClockDomain)
    val r_address_in_sreg = Reg(Bits(ADDRESS_WIDTH bits)) init 0 addTag(crossClockDomain)
    val r_read_en_sreg    = Reg(Bool()) init False addTag(crossClockDomain)
    val r_write_en_sreg   = Reg(Bool()) init False addTag(crossClockDomain)
    val r_request_sreg    = Reg(Bool()) init False addTag(crossClockDomain)
    val r_dqm_mask_sreg   = Reg(Bits(4 bits)) init B"1111" addTag(crossClockDomain)
    val r_refresh_sreg    = Reg(Bool()) init False addTag(crossClockDomain)

    val r_data_out_sreg   = Reg(Bits(32 bits)) init 0 addTag(crossClockDomain)
    val r_wide_out_sreg   = Reg(Bits(256 bits)) init 0 addTag(crossClockDomain)
    val r_reply_sreg      = Reg(Bool()) init False addTag(crossClockDomain)

    val r_sdram_request_reg = Reg(Bool()) init False
    val r_reset_client_n_reg = Reg(Bool()) init False

    r_data_in_sreg      := DATA_IN_snext
    r_wide_in_sreg      := WIDE_IN_snext
    r_wide_acc_sreg     := WIDE_ACCESS_snext
    r_address_in_sreg   := ADDRESS_IN_snext
    r_read_en_sreg      := READ_EN_snext
    r_write_en_sreg     := WRITE_EN_snext
    r_request_sreg      := request_snext
    r_dqm_mask_sreg     := dqm_mask_snext
    r_refresh_sreg      := refresh_snext

    r_data_out_sreg     := DATA_OUT_snext
    r_wide_out_sreg     := WIDE_OUT_snext
    r_reply_sreg        := reply_snext

    r_sdram_request_reg := sdram_request_next
    r_reset_client_n_reg := reset_client_n_next

    DATA_IN_sreg        := r_data_in_sreg
    WIDE_IN_sreg        := r_wide_in_sreg
    WIDE_ACCESS_sreg    := r_wide_acc_sreg
    ADDRESS_IN_sreg     := r_address_in_sreg
    READ_EN_sreg        := r_read_en_sreg
    WRITE_EN_sreg       := r_write_en_sreg
    request_sreg        := r_request_sreg
    dqm_mask_sreg       := r_dqm_mask_sreg
    refresh_sreg        := r_refresh_sreg

    DATA_OUT_sreg       := r_data_out_sreg
    WIDE_OUT_sreg       := r_wide_out_sreg
    reply_sreg          := r_reply_sreg

    sdram_request_reg   := r_sdram_request_reg
    reset_client_n_reg  := r_reset_client_n_reg
  }

  // ---- Inputs: snap inputs on new request ----
  DATA_IN_snext      := DATA_IN_sreg
  WIDE_IN_snext      := WIDE_IN_sreg
  WIDE_ACCESS_snext  := WIDE_ACCESS_sreg
  ADDRESS_IN_snext   := ADDRESS_IN_sreg
  READ_EN_snext      := READ_EN_sreg
  WRITE_EN_snext     := WRITE_EN_sreg
  request_snext      := request_sreg
  dqm_mask_snext     := dqm_mask_sreg
  refresh_snext      := io.REFRESH

  when((sdram_request_next ^ request_sreg) === True) {
    DATA_IN_snext      := io.DATA_IN
    WIDE_IN_snext      := io.WIDE_IN
    WIDE_ACCESS_snext  := io.WIDE_ACCESS
    ADDRESS_IN_snext   := io.ADDRESS_IN(ADDRESS_WIDTH downto 1)
    READ_EN_snext      := io.READ_EN
    WRITE_EN_snext     := io.WRITE_EN
    request_snext      := sdram_request_next

    dqm_mask_snext(0)  := (io.BYTE_ACCESS | io.WORD_ACCESS) & io.ADDRESS_IN(0)
    dqm_mask_snext(1)  := io.BYTE_ACCESS & ~io.ADDRESS_IN(0)
    dqm_mask_snext(2)  := io.BYTE_ACCESS | (io.WORD_ACCESS & ~io.ADDRESS_IN(0))
    dqm_mask_snext(3)  := ~(io.LONGWORD_ACCESS | io.WIDE_ACCESS)
    when(io.WIDE_ACCESS) {
      dqm_mask_snext := B"0000"
    }
  }

  // ---- Refresh scheduling ----
  // Normal refresh is driven directly by the vertical-blank gate (refresh_sreg,
  // high only during the Atari's VBLANK). The idle dispatch runs it at LOWEST
  // priority, so it only fills gaps behind client reads/writes - refresh never
  // blocks the framebuffer capture/display ports (no jitter) and never lands on
  // a visible sprite-DMA line (no smear). A VBLANK is ~2.5 ms; walking all 2048
  // used rows costs ~142 us, so every row is refreshed many times per frame.
  //
  // HALT fallback: while the Atari is halted for a supervisor load there is no
  // VBLANK, so reuse the counters as a "cycles since last VBLANK" detector -
  // refresh_pending counts ~17.7 us ticks, reset by each VBLANK. Past ~30 ms
  // (well over one 20 ms frame) we assume halted and force refresh so loaded
  // data doesn't decay before the Atari is released.
  cycles_since_refresh_next := (cycles_since_refresh_reg.asUInt + 1).asBits.resized
  refresh_pending_next      := refresh_pending_reg
  refreshRow_next           := refreshRow_reg
  suggest_refresh           := False
  force_refresh             := refresh_pending_reg.asUInt >= 1695   // ~30 ms with no VBLANK

  require_refresh := refresh_sreg | force_refresh

  when(refresh_sreg) {
    // In VBLANK: refresh is running, so clear the halt detector.
    refresh_pending_next      := B(0, 12 bits)
    cycles_since_refresh_next := B(0, 11 bits)
  } otherwise {
    when(cycles_since_refresh_reg.asUInt === 2047) {
      cycles_since_refresh_next := B(0, 11 bits)
      when(refresh_pending_reg.asUInt =/= 4095) {
        refresh_pending_next := (refresh_pending_reg.asUInt + 1).asBits.resized
      }
    }
  }

  // ---- Main state machine ----
  idle_priority    := B(0, 4 bits)
  refreshing_now   := False
  reset_client_n_next := reset_client_n_reg
  sdram_state_next := sdram_state_reg
  command_next     := sdram_command_no_operation
  delay_next       := (delay_reg.asUInt + 1).asBits.resized
  data_out_next    := data_out_reg
  wide_out_next    := wide_out_reg
  reply_next       := reply_reg

  // Defaults for NOP
  dq_out_next      := B(0, 16 bits)
  dq_output_next   := False
  cke_next         := True
  ldqm_next        := True
  udqm_next        := True
  ba_next          := B(0, 2 bits)
  addr_next        := B(ROW_WIDTH bits, default -> true)

  switch(sdram_state_reg) {
    is(sdram_state_powerup) {
      when(delay_reg(15)) {   // 32768 cycles: >= 200 us power-up pause at any clock we use
        sdram_state_next := sdram_state_init_precharge
        delay_next := B(0, 16 bits)
      }
    }
    is(sdram_state_init) {
      switch(delay_reg(5 downto 3) ## delay_reg(0)) {
        is(B"0001") {
          command_next := sdram_command_precharge
          addr_next(AP_BIT) := True
        }
        is(B"0010") {
          command_next := sdram_command_refresh
        }
        is(B"0100") {
          command_next := sdram_command_refresh
        }
        is(B"1000") {
          command_next := sdram_command_mode_register
          addr_next(2 downto 0) := B"001"
          addr_next(3) := False
          addr_next(6 downto 4) := B"011"
          addr_next(8 downto 7) := B"00"
          addr_next(9) := False
          if (ROW_WIDTH > 10) addr_next(ROW_WIDTH - 1 downto 10) := B(0, ROW_WIDTH - 10 bits)
        }
        is(B"1010") {
          sdram_state_next := sdram_state_idle
          delay_next := B(0, 16 bits)
        }
      }
    }
    is(sdram_state_idle) {
      reset_client_n_next := True
      delay_next := B(0, 16 bits)

      // Priority: force_refresh (emergency, backlog high) > pending client
      // read/write > ordinary (gated) refresh. Letting a pending access beat a
      // non-forced refresh keeps the display reads (port C) from being blocked
      // by refresh bursts - the underrun that showed up as picture jitter -
      // while refresh still fills the idle gaps (bus is <30% utilised) and
      // force_refresh guarantees retention if the backlog ever builds.
      val idlePending = request_sreg ^ reply_reg
      when(force_refresh) {
        sdram_state_next := sdram_state_refresh
      }.elsewhen(idlePending && WRITE_EN_sreg) {
        sdram_state_next := sdram_state_write
        when(WIDE_ACCESS_sreg) { sdram_state_next := sdram_state_wide_write }
      }.elsewhen(idlePending && READ_EN_sreg) {
        sdram_state_next := sdram_state_read
        when(WIDE_ACCESS_sreg) { sdram_state_next := sdram_state_wide_read }
      }.elsewhen(require_refresh) {
        sdram_state_next := sdram_state_refresh
      }
    }
    is(sdram_state_read) {
      switch(delay_reg(3 downto 0)) {
        is(B"x0") {
          command_next := sdram_command_bank_activate
          ba_next := ADDRESS_IN_sreg(ADDRESS_WIDTH - 1 downto ADDRESS_WIDTH - 2)
          addr_next := ADDRESS_IN_sreg(ADDRESS_WIDTH - 3 downto ADDRESS_WIDTH - 3 - ROW_WIDTH + 1)
        }
        is(B"x3") {
          command_next := sdram_command_read
          ba_next := ADDRESS_IN_sreg(ADDRESS_WIDTH - 1 downto ADDRESS_WIDTH - 2)
          addr_next(COLUMN_WIDTH - 1 downto 0) := ADDRESS_IN_sreg(ADDRESS_WIDTH - 3 - ROW_WIDTH downto 0)
          addr_next(AP_BIT) := True
        }
        is(B"x4") {
          ldqm_next := dqm_mask_sreg(0)
          udqm_next := dqm_mask_sreg(1)
        }
        is(B"x5") {
          ldqm_next := dqm_mask_sreg(2)
          udqm_next := dqm_mask_sreg(3)
        }
        is(B"x8") {
          data_out_next(7 downto 0) := (dq_in_reg(7 downto 0) & ~repeat(8, dqm_mask_sreg(0))) |
                                       (dq_in_reg(15 downto 8) & repeat(8, dqm_mask_sreg(0)))
          data_out_next(15 downto 8) := dq_in_reg(15 downto 8)
        }
        is(B"x9") {
          data_out_next(15 downto 8) := (dq_in_reg(7 downto 0) & repeat(8, dqm_mask_sreg(0))) |
                                        (data_out_reg(15 downto 8) & ~repeat(8, dqm_mask_sreg(0)))
          data_out_next(31 downto 16) := dq_in_reg(15 downto 0)
          delay_next := B(0, 16 bits)
          reply_next := request_sreg
          sdram_state_next := sdram_state_idle
        }
      }
    }
    is(sdram_state_write) {
      switch(delay_reg(3 downto 0)) {
        is(B"x0") {
          command_next := sdram_command_bank_activate
          ba_next := ADDRESS_IN_sreg(ADDRESS_WIDTH - 1 downto ADDRESS_WIDTH - 2)
          addr_next := ADDRESS_IN_sreg(ADDRESS_WIDTH - 3 downto ADDRESS_WIDTH - 3 - ROW_WIDTH + 1)
        }
        is(B"x3") {
          command_next := sdram_command_write
          ba_next := ADDRESS_IN_sreg(ADDRESS_WIDTH - 1 downto ADDRESS_WIDTH - 2)
          addr_next(COLUMN_WIDTH - 1 downto 0) := ADDRESS_IN_sreg(ADDRESS_WIDTH - 3 - ROW_WIDTH downto 0)
          addr_next(AP_BIT) := True

          dq_output_next := True
          dq_out_next(7 downto 0) := DATA_IN_sreg(7 downto 0)
          dq_out_next(15 downto 8) := (DATA_IN_sreg(15 downto 8) & ~repeat(8, dqm_mask_sreg(0))) |
                                      (DATA_IN_sreg(7 downto 0) & repeat(8, dqm_mask_sreg(0)))
          ldqm_next := dqm_mask_sreg(0)
          udqm_next := dqm_mask_sreg(1)
        }
        is(B"x4") {
          dq_output_next := True
          dq_out_next(7 downto 0) := (DATA_IN_sreg(23 downto 16) & ~repeat(8, dqm_mask_sreg(0))) |
                                     (DATA_IN_sreg(15 downto 8) & repeat(8, dqm_mask_sreg(0)))
          dq_out_next(15 downto 8) := DATA_IN_sreg(31 downto 24)
          ldqm_next := dqm_mask_sreg(2)
          udqm_next := dqm_mask_sreg(3)

          reply_next := request_sreg
        }
        is(B"x6") {
          sdram_state_next := sdram_state_idle
        }
      }
    }
    is(sdram_state_wide_read) {
      // 8 pipelined BL2 READ bursts in one open row: ACTIVATE @0, CAS @2,4,
      // ..,16 (auto-precharge on the last), 16 data words captured @7..22
      // (same CAS->capture offset the proven single-beat read uses).
      val d = delay_reg(4 downto 0).asUInt
      when(d === 0) {
        command_next := sdram_command_bank_activate
        ba_next := ADDRESS_IN_sreg(ADDRESS_WIDTH - 1 downto ADDRESS_WIDTH - 2)
        addr_next := ADDRESS_IN_sreg(ADDRESS_WIDTH - 3 downto ADDRESS_WIDTH - 3 - ROW_WIDTH + 1)
      }
      when(d >= 2 && d <= 16 && d(0) === False) {
        command_next := sdram_command_read
        ba_next := ADDRESS_IN_sreg(ADDRESS_WIDTH - 1 downto ADDRESS_WIDTH - 2)
        addr_next(COLUMN_WIDTH - 1 downto 0) := ADDRESS_IN_sreg(ADDRESS_WIDTH - 3 - ROW_WIDTH downto 0)
        addr_next(3 downto 0) := (d - 2).asBits.resize(4)     // 32-byte aligned base
        // the addr_next default is ALL-ONES incl. bit 10 = auto-precharge:
        // it MUST be cleared on every CAS but the last, or the row closes
        // after the first pair and beats 1-7 hit a closed row (JEDEC-illegal;
        // manifested as only word 0 of each group landing on hardware)
        addr_next(AP_BIT) := (d === 16)
      }
      when(d >= 3 && d <= 18) { ldqm_next := False; udqm_next := False }
      when(d >= 7 && d <= 22) {
        val k = (d - 7).resize(4)
        wide_out_next.subdivideIn(16 slices)(k) := dq_in_reg
      }
      when(d === 23) {
        reply_next := request_sreg
        delay_next := B(0, 16 bits)
        sdram_state_next := sdram_state_idle
      }
    }
    is(sdram_state_wide_write) {
      // ACTIVATE @0, 8 pipelined BL2 WRITE bursts @2,4,..,16 (AP on last),
      // data words driven continuously @2..17 (cmd+data alignment identical
      // to the single-beat write's x3/x4 pattern).
      val d = delay_reg(4 downto 0).asUInt
      when(d === 0) {
        command_next := sdram_command_bank_activate
        ba_next := ADDRESS_IN_sreg(ADDRESS_WIDTH - 1 downto ADDRESS_WIDTH - 2)
        addr_next := ADDRESS_IN_sreg(ADDRESS_WIDTH - 3 downto ADDRESS_WIDTH - 3 - ROW_WIDTH + 1)
      }
      when(d >= 2 && d <= 16 && d(0) === False) {
        command_next := sdram_command_write
        ba_next := ADDRESS_IN_sreg(ADDRESS_WIDTH - 1 downto ADDRESS_WIDTH - 2)
        addr_next(COLUMN_WIDTH - 1 downto 0) := ADDRESS_IN_sreg(ADDRESS_WIDTH - 3 - ROW_WIDTH downto 0)
        addr_next(3 downto 0) := (d - 2).asBits.resize(4)
        addr_next(AP_BIT) := (d === 16)                       // see wide-read note
      }
      when(d >= 2 && d <= 17) {
        val k = (d - 2).resize(4)
        dq_output_next := True
        dq_out_next := WIDE_IN_sreg.subdivideIn(16 slices)(k)
        ldqm_next := False
        udqm_next := False
      }
      when(d === 19) {
        reply_next := request_sreg
        delay_next := B(0, 16 bits)
        sdram_state_next := sdram_state_idle
      }
    }
    is(sdram_state_refresh) {
      // Targeted RAS-only refresh of one USED row: ACTIVATE (opens the row,
      // which reads it into the sense amps and restores it = a refresh) then
      // PRECHARGE. Only bank 0, rows 0..2047 - the region the Atari and the
      // framebuffer actually occupy - so we refresh 4x fewer rows than a blind
      // whole-chip auto-refresh and can keep up inside the HBLANK-gated window.
      switch(delay_reg(3 downto 0)) {
        is(B"x0") {
          command_next := sdram_command_bank_activate
          ba_next      := B"00"
          addr_next    := refreshRow_reg.resize(ROW_WIDTH bits)
          refreshing_now := True
        }
        is(B"x5") {
          command_next := sdram_command_precharge   // addr default all-ones -> A10=1 = precharge all
        }
        is(B"x8") {
          sdram_state_next := sdram_state_idle
          refreshRow_next  := (refreshRow_reg.asUInt + 1).asBits   // 11-bit wrap: rows 0..2047
        }
      }
    }
    default {
      sdram_state_next := sdram_state_init
    }
  }

  // Command decode
  cs_n_next  := command_next(3)
  ras_n_next := command_next(2)
  cas_n_next := command_next(1)
  we_n_next  := command_next(0)

  // Outputs to SDRAM
  io.SDRAM_ADDR  := addr_reg
  io.SDRAM_BA0   := ba_reg(0)
  io.SDRAM_BA1   := ba_reg(1)
  io.SDRAM_CS_N  := cs_n_reg
  io.SDRAM_RAS_N := ras_n_reg
  io.SDRAM_CAS_N := cas_n_reg
  io.SDRAM_WE_N  := we_n_reg
  io.SDRAM_ldqm  := ldqm_reg
  io.SDRAM_udqm  := udqm_reg
  io.SDRAM_CKE   := cke_reg

  // Tristate handling for SDRAM_DQ (exposed as separate signals)
  io.SDRAM_DQ_OUT := dq_out_reg
  io.SDRAM_DQ_OE  := dq_output_reg

  // Input from SDRAM
  dq_in_next := io.SDRAM_DQ_IN

  // Back to slower clock
  reply_snext    := reply_reg
  DATA_OUT_snext := data_out_reg
  WIDE_OUT_snext := wide_out_reg

  // Outputs to rest of system
  io.DATA_OUT := DATA_OUT_sreg
  io.WIDE_OUT := WIDE_OUT_sreg
  io.COMPLETE := (~(reply_sreg ^ sdram_request_reg)) & ~io.REQUEST
  sdram_request_next := sdram_request_reg ^ io.REQUEST
  io.reset_client_n := reset_client_n_reg
}
