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
  val sdram_state_powerup        = B"000"
  val sdram_state_init           = B"001"
  val sdram_state_idle           = B"010"
  val sdram_state_refresh        = B"011"
  val sdram_state_read           = B"100"
  val sdram_state_write          = B"101"
  val sdram_state_init_precharge = B"110"

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
  val sdram_state_next       = Bits(3 bits)
  val sdram_state_reg        = Bits(3 bits)
  val delay_next             = Bits(14 bits)
  val delay_reg              = Bits(14 bits)
  val cycles_since_refresh_next = Bits(11 bits)
  val cycles_since_refresh_reg  = Bits(11 bits)
  val refresh_pending_next   = Bits(12 bits)
  val refresh_pending_reg    = Bits(12 bits)
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
    val r_sdram_state_reg = Reg(Bits(3 bits)) init sdram_state_init addTag(crossClockDomain)
    val r_delay_reg       = Reg(Bits(14 bits)) init 0
    val r_refresh_pending_reg = Reg(Bits(12 bits)) init 0
    val r_cycles_since_refresh_reg = Reg(Bits(11 bits)) init 0
    val r_data_out_reg    = Reg(Bits(32 bits)) init 0 addTag(crossClockDomain)
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
    r_data_out_reg    := data_out_next
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
    data_out_reg             := r_data_out_reg
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
    val r_address_in_sreg = Reg(Bits(ADDRESS_WIDTH bits)) init 0 addTag(crossClockDomain)
    val r_read_en_sreg    = Reg(Bool()) init False addTag(crossClockDomain)
    val r_write_en_sreg   = Reg(Bool()) init False addTag(crossClockDomain)
    val r_request_sreg    = Reg(Bool()) init False addTag(crossClockDomain)
    val r_dqm_mask_sreg   = Reg(Bits(4 bits)) init B"1111" addTag(crossClockDomain)
    val r_refresh_sreg    = Reg(Bool()) init False addTag(crossClockDomain)

    val r_data_out_sreg   = Reg(Bits(32 bits)) init 0 addTag(crossClockDomain)
    val r_reply_sreg      = Reg(Bool()) init False addTag(crossClockDomain)

    val r_sdram_request_reg = Reg(Bool()) init False
    val r_reset_client_n_reg = Reg(Bool()) init False

    r_data_in_sreg      := DATA_IN_snext
    r_address_in_sreg   := ADDRESS_IN_snext
    r_read_en_sreg      := READ_EN_snext
    r_write_en_sreg     := WRITE_EN_snext
    r_request_sreg      := request_snext
    r_dqm_mask_sreg     := dqm_mask_snext
    r_refresh_sreg      := refresh_snext

    r_data_out_sreg     := DATA_OUT_snext
    r_reply_sreg        := reply_snext

    r_sdram_request_reg := sdram_request_next
    r_reset_client_n_reg := reset_client_n_next

    DATA_IN_sreg        := r_data_in_sreg
    ADDRESS_IN_sreg     := r_address_in_sreg
    READ_EN_sreg        := r_read_en_sreg
    WRITE_EN_sreg       := r_write_en_sreg
    request_sreg        := r_request_sreg
    dqm_mask_sreg       := r_dqm_mask_sreg
    refresh_sreg        := r_refresh_sreg

    DATA_OUT_sreg       := r_data_out_sreg
    reply_sreg          := r_reply_sreg

    sdram_request_reg   := r_sdram_request_reg
    reset_client_n_reg  := r_reset_client_n_reg
  }

  // ---- Inputs: snap inputs on new request ----
  DATA_IN_snext      := DATA_IN_sreg
  ADDRESS_IN_snext   := ADDRESS_IN_sreg
  READ_EN_snext      := READ_EN_sreg
  WRITE_EN_snext     := WRITE_EN_sreg
  request_snext      := request_sreg
  dqm_mask_snext     := dqm_mask_sreg
  refresh_snext      := io.REFRESH

  when((sdram_request_next ^ request_sreg) === True) {
    DATA_IN_snext      := io.DATA_IN
    ADDRESS_IN_snext   := io.ADDRESS_IN(ADDRESS_WIDTH downto 1)
    READ_EN_snext      := io.READ_EN
    WRITE_EN_snext     := io.WRITE_EN
    request_snext      := sdram_request_next

    dqm_mask_snext(0)  := (io.BYTE_ACCESS | io.WORD_ACCESS) & io.ADDRESS_IN(0)
    dqm_mask_snext(1)  := io.BYTE_ACCESS & ~io.ADDRESS_IN(0)
    dqm_mask_snext(2)  := io.BYTE_ACCESS | (io.WORD_ACCESS & ~io.ADDRESS_IN(0))
    dqm_mask_snext(3)  := ~io.LONGWORD_ACCESS
  }

  // ---- Refresh counters ----
  cycles_since_refresh_next := (cycles_since_refresh_reg.asUInt + 1).asBits.resized
  refresh_pending_next      := refresh_pending_reg
  suggest_refresh           := False
  force_refresh             := False

  when(refresh_pending_reg.asUInt > 0) {
    suggest_refresh := True
  }
  when(refresh_pending_reg === B"xFFF") {
    force_refresh := True
  }

  require_refresh := force_refresh | (suggest_refresh & refresh_sreg)

  when(refreshing_now) {
    cycles_since_refresh_next := B(0, 11 bits)
    when(suggest_refresh) {
      refresh_pending_next := (refresh_pending_reg.asUInt - 1).asBits.resized
    }
  } otherwise {
    when(cycles_since_refresh_reg === B"11111111111") {
      refresh_pending_next := (refresh_pending_reg.asUInt + 1).asBits.resized
      cycles_since_refresh_next := B(0, 11 bits)
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
      when(delay_reg(13)) {
        sdram_state_next := sdram_state_init_precharge
        delay_next := B(0, 14 bits)
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
          if (ROW_WIDTH > 10) addr_next(11 downto 10) := B"00"
        }
        is(B"1010") {
          sdram_state_next := sdram_state_idle
          delay_next := B(0, 14 bits)
        }
      }
    }
    is(sdram_state_idle) {
      reset_client_n_next := True
      delay_next := B(0, 14 bits)

      idle_priority := (request_sreg ^ reply_reg) ## require_refresh ## WRITE_EN_sreg ## READ_EN_sreg
      switch(idle_priority) {
        is(B"0100", B"0101", B"0110", B"0111", B"1100", B"1101", B"1110", B"1111") {
          sdram_state_next := sdram_state_refresh
        }
        is(B"1010", B"1011") {
          sdram_state_next := sdram_state_write
        }
        is(B"1001") {
          sdram_state_next := sdram_state_read
        }
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
          delay_next := B(0, 14 bits)
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
    is(sdram_state_refresh) {
      switch(delay_reg(3 downto 0)) {
        is(B"x0") {
          command_next := sdram_command_refresh
          refreshing_now := True
        }
        is(B"x8") {
          sdram_state_next := sdram_state_idle
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

  // Outputs to rest of system
  io.DATA_OUT := DATA_OUT_sreg
  io.COMPLETE := (~(reply_sreg ^ sdram_request_reg)) & ~io.REQUEST
  sdram_request_next := sdram_request_reg ^ io.REQUEST
  io.reset_client_n := reset_client_n_reg
}
