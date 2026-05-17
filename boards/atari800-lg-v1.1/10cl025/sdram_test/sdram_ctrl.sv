// Minimal single-word SDRAM controller for the W9825G6JH-6 on the QMTECH
// 10CL025 core board. 32 MB / 16-bit / 4 banks / 13 row × 9 col.
//
// Runs at 50 MHz (sdram_clk = clk). CL = 2, burst length 1, no refresh
// during R/W (deferred to idle), auto-precharge on every read and write.
//
// Word address layout (24 bits):
//   a[23:22] = bank (0..3)
//   a[21:9]  = row  (0..8191)
//   a[8:0]   = col  (0..511)
//
// Interface to the surrounding logic:
//   - req_we / req_rd : single-cycle pulse to start a write or read
//   - req_addr        : 24-bit word address (valid while busy=0)
//   - req_wdata       : 16-bit data (write only)
//   - req_dqm         : per-byte mask (0=write that byte, 1=mask off)
//   - rd_valid pulses 1 cycle when rdata is fresh
//   - busy=1 while a request is in flight or refresh is pending

module sdram_ctrl #(
    parameter integer CLK_HZ = 50_000_000
) (
    input  wire        clk,
    input  wire        rst_n,

    // Request port
    input  wire        req_we,
    input  wire        req_rd,
    input  wire [23:0] req_addr,
    input  wire [15:0] req_wdata,
    input  wire [1:0]  req_dqm,
    output reg         busy,
    output reg         init_done,

    output reg  [15:0] rdata,
    output reg         rd_valid,

    // SDRAM pins
    output reg         sdram_clk,
    output reg         sdram_cke,
    output reg         sdram_csn,
    output reg         sdram_rasn,
    output reg         sdram_casn,
    output reg         sdram_wen,
    output reg  [1:0]  sdram_ba,
    output reg  [12:0] sdram_addr,
    output reg  [1:0]  sdram_dqm,
    inout  wire [15:0] sdram_dq
);

    // -----------------------------------------------------------------------
    // Constants and helper functions
    // -----------------------------------------------------------------------
    // 200 µs power-on wait
    localparam integer POR_CYCLES = (200 * (CLK_HZ / 1_000_000));   // 10_000
    // Refresh interval: 64 ms / 8192 rows = 7.81 µs → use 7.5 µs for margin
    localparam integer REFRESH_INTERVAL = (CLK_HZ / 200_000) * 3 / 2; // ~7.5us

    // Command codes: {CS#, RAS#, CAS#, WE#}
    localparam [3:0] CMD_NOP        = 4'b0111;
    localparam [3:0] CMD_ACTIVE     = 4'b0011;
    localparam [3:0] CMD_READ       = 4'b0101;
    localparam [3:0] CMD_WRITE      = 4'b0100;
    localparam [3:0] CMD_PRECHARGE  = 4'b0010;
    localparam [3:0] CMD_AREFRESH   = 4'b0001;
    localparam [3:0] CMD_MRS        = 4'b0000;
    localparam [3:0] CMD_INHIBIT    = 4'b1111;

    // Mode register: BL=1, sequential, CL=2, standard, no burst-write
    //   A[2:0]=000 (BL=1), A[3]=0 (seq), A[6:4]=010 (CL=2), rest=0
    localparam [12:0] MODE_VAL = 13'b0_000_010_0_0_000;

    // -----------------------------------------------------------------------
    // Bidirectional DQ
    // -----------------------------------------------------------------------
    reg [15:0] dq_out;
    reg        dq_oe;
    assign sdram_dq = dq_oe ? dq_out : 16'bz;

    reg [15:0] dq_in_sync;
    always @(posedge clk) dq_in_sync <= sdram_dq;

    // -----------------------------------------------------------------------
    // State machine
    // -----------------------------------------------------------------------
    localparam [4:0] S_RESET     = 5'd0,
                     S_POR       = 5'd1,
                     S_INIT_PALL = 5'd2,
                     S_INIT_RFA  = 5'd3,
                     S_INIT_RFB  = 5'd4,
                     S_INIT_MRS  = 5'd5,
                     S_INIT_DONE = 5'd6,
                     S_IDLE      = 5'd7,
                     S_AREF      = 5'd8,
                     S_AREF_WAIT = 5'd9,
                     S_ACTIVE    = 5'd10,
                     S_ACT_WAIT  = 5'd11,
                     S_RD_CMD    = 5'd12,
                     S_RD_CL1    = 5'd13,
                     S_RD_CL2    = 5'd14,
                     S_RD_LAT    = 5'd19,    // wait one cycle for SDRAM CL output
                     S_RD_LAT2   = 5'd20,    // and another for round-trip propagation + IO-FF sample
                     S_RD_DONE   = 5'd15,
                     S_WR_CMD    = 5'd16,
                     S_WR_DONE   = 5'd17,
                     S_TRP       = 5'd18;

    reg [4:0]  st;
    reg [16:0] tmr;             // up to 128k cycles for POR delay
    reg [3:0]  rf_count;
    reg [15:0] refresh_cnt;
    reg        do_we, do_rd;
    reg [23:0] cur_addr;
    reg [15:0] cur_wdata;
    reg [1:0]  cur_dqm;

    // Issue a command set by setting all command pins together. Helper macros
    // would be cleaner; here we inline.

    initial begin
        st          = S_RESET;
        sdram_clk   = 1'b0;
        sdram_cke   = 1'b0;
        sdram_csn   = 1'b1;
        sdram_rasn  = 1'b1;
        sdram_casn  = 1'b1;
        sdram_wen   = 1'b1;
        sdram_addr  = 13'd0;
        sdram_ba    = 2'd0;
        sdram_dqm   = 2'b11;
        dq_out      = 16'd0;
        dq_oe       = 1'b0;
        busy        = 1'b1;
        init_done   = 1'b0;
        rdata       = 16'd0;
        rd_valid    = 1'b0;
        tmr         = 17'd0;
        rf_count    = 4'd0;
        refresh_cnt = 16'd0;
        do_we       = 1'b0;
        do_rd       = 1'b0;
        cur_addr    = 24'd0;
        cur_wdata   = 16'd0;
        cur_dqm     = 2'b00;
    end

    // SDRAM clock = system clock (output the clock pin)
    always @(*) sdram_clk = clk;

    task issue_cmd(input [3:0] cmd);
        begin
            {sdram_csn, sdram_rasn, sdram_casn, sdram_wen} <= cmd;
        end
    endtask

    always @(posedge clk) begin
        rd_valid <= 1'b0;

        // Refresh timer
        if (st != S_RESET && st != S_POR && st != S_INIT_PALL
                          && st != S_INIT_RFA && st != S_INIT_RFB
                          && st != S_INIT_MRS && refresh_cnt > 0)
            refresh_cnt <= refresh_cnt - 16'd1;

        case (st)
        S_RESET: begin
            sdram_cke <= 1'b1;
            issue_cmd(CMD_NOP);
            sdram_dqm <= 2'b11;
            tmr <= POR_CYCLES[16:0];
            st  <= S_POR;
        end

        S_POR: begin
            issue_cmd(CMD_NOP);
            if (tmr == 0) begin
                // Precharge all
                issue_cmd(CMD_PRECHARGE);
                sdram_addr <= 13'b0010000000000;  // A10 = 1 → ALL
                st  <= S_INIT_PALL;
                tmr <= 17'd2;                     // tRP wait
            end else
                tmr <= tmr - 17'd1;
        end

        S_INIT_PALL: begin
            issue_cmd(CMD_NOP);
            if (tmr == 0) begin
                rf_count <= 4'd8;
                st <= S_INIT_RFA;
            end else
                tmr <= tmr - 17'd1;
        end

        S_INIT_RFA: begin
            issue_cmd(CMD_AREFRESH);
            tmr <= 17'd6;            // tRFC = 60ns → 3 cycles + margin
            st  <= S_INIT_RFB;
        end

        S_INIT_RFB: begin
            issue_cmd(CMD_NOP);
            if (tmr == 0) begin
                if (rf_count == 4'd1) st <= S_INIT_MRS;
                else begin
                    rf_count <= rf_count - 4'd1;
                    st       <= S_INIT_RFA;
                end
            end else
                tmr <= tmr - 17'd1;
        end

        S_INIT_MRS: begin
            issue_cmd(CMD_MRS);
            sdram_ba   <= 2'b00;
            sdram_addr <= MODE_VAL;
            tmr <= 17'd2;            // tMRD
            st  <= S_INIT_DONE;
        end

        S_INIT_DONE: begin
            issue_cmd(CMD_NOP);
            if (tmr == 0) begin
                init_done   <= 1'b1;
                busy        <= 1'b0;
                refresh_cnt <= REFRESH_INTERVAL[15:0];
                st          <= S_IDLE;
            end else
                tmr <= tmr - 17'd1;
        end

        S_IDLE: begin
            issue_cmd(CMD_NOP);
            sdram_dqm <= 2'b00;
            dq_oe     <= 1'b0;

            if (refresh_cnt == 0) begin
                busy <= 1'b1;
                st   <= S_AREF;
            end else if (req_we) begin
                cur_addr  <= req_addr;
                cur_wdata <= req_wdata;
                cur_dqm   <= req_dqm;
                do_we     <= 1'b1;
                do_rd     <= 1'b0;
                busy      <= 1'b1;
                st        <= S_ACTIVE;
            end else if (req_rd) begin
                cur_addr <= req_addr;
                do_we    <= 1'b0;
                do_rd    <= 1'b1;
                busy     <= 1'b1;
                st       <= S_ACTIVE;
            end
        end

        S_AREF: begin
            // Need all banks precharged first — but we always use auto-
            // precharge, so banks are idle. Just issue AREFRESH.
            issue_cmd(CMD_AREFRESH);
            tmr <= 17'd6;            // tRFC
            st  <= S_AREF_WAIT;
        end

        S_AREF_WAIT: begin
            issue_cmd(CMD_NOP);
            if (tmr == 0) begin
                refresh_cnt <= REFRESH_INTERVAL[15:0];
                busy        <= 1'b0;
                st          <= S_IDLE;
            end else
                tmr <= tmr - 17'd1;
        end

        S_ACTIVE: begin
            issue_cmd(CMD_ACTIVE);
            sdram_ba   <= cur_addr[23:22];
            sdram_addr <= cur_addr[21:9];
            tmr <= 17'd1;            // tRCD = 1 cycle margin
            st  <= S_ACT_WAIT;
        end

        S_ACT_WAIT: begin
            issue_cmd(CMD_NOP);
            if (tmr == 0) begin
                st <= do_we ? S_WR_CMD : S_RD_CMD;
            end else
                tmr <= tmr - 17'd1;
        end

        // --- Read with auto-precharge ---
        S_RD_CMD: begin
            issue_cmd(CMD_READ);
            sdram_ba   <= cur_addr[23:22];
            // col + A10=1 (auto-precharge) → addr[10] forced high
            sdram_addr <= {3'b001, 1'b0, cur_addr[8:0]};
            sdram_dqm  <= 2'b00;
            dq_oe      <= 1'b0;
            st  <= S_RD_CL1;
        end
        S_RD_CL1: begin issue_cmd(CMD_NOP); st <= S_RD_CL2; end
        S_RD_CL2:  begin issue_cmd(CMD_NOP); st <= S_RD_LAT;  end
        S_RD_LAT:  begin issue_cmd(CMD_NOP); st <= S_RD_LAT2; end
        S_RD_LAT2: begin issue_cmd(CMD_NOP); st <= S_RD_DONE; end
        S_RD_DONE: begin
            issue_cmd(CMD_NOP);
            // Sample sram_dq directly — Quartus packs the IO input FF, which
            // saves a cycle of pipeline vs going through the fabric
            // dq_in_sync register and matches the SDRAM CL=2 read timing.
            rdata    <= dq_in_sync;
            rd_valid <= 1'b1;
            tmr      <= 17'd2;       // tRP wait for autoprecharge
            st       <= S_TRP;
        end

        // --- Write with auto-precharge ---
        S_WR_CMD: begin
            issue_cmd(CMD_WRITE);
            sdram_ba   <= cur_addr[23:22];
            sdram_addr <= {3'b001, 1'b0, cur_addr[8:0]};
            sdram_dqm  <= cur_dqm;
            dq_out     <= cur_wdata;
            dq_oe      <= 1'b1;
            tmr        <= 17'd1;     // tWR
            st         <= S_WR_DONE;
        end
        S_WR_DONE: begin
            issue_cmd(CMD_NOP);
            dq_oe <= 1'b0;
            if (tmr == 0) begin
                tmr <= 17'd2;        // tRP wait for autoprecharge
                st  <= S_TRP;
            end else
                tmr <= tmr - 17'd1;
        end

        S_TRP: begin
            issue_cmd(CMD_NOP);
            if (tmr == 0) begin
                do_we <= 1'b0;
                do_rd <= 1'b0;
                busy  <= 1'b0;
                st    <= S_IDLE;
            end else
                tmr <= tmr - 17'd1;
        end

        default: st <= S_RESET;
        endcase
    end

endmodule
