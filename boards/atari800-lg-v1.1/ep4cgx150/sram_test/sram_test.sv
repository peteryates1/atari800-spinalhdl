// V1.1 SRAM bring-up bridge.
//
// IS63LV1024L: 128K x 8 async SRAM, 10 ns access. Pins on V1.1:
//   sram_a[16:0], sram_dq[7:0], sram_ce_n, sram_we_n, sram_oe_n.
//
// Host protocol (UART, 500000 baud, 8N1):
//   'A' <a0> <a1> <a2>           Set 17-bit current address. Reply: 'A'
//   'P'                          Read back current address. Reply: <a0> <a1> <a2>
//   'W' <data>                   Write byte at addr, addr++.   Reply: 'W'
//   'R'                          Read byte at addr, addr++.    Reply: <byte>
//   'F' <c0> <c1> <data>         Fill `(c1<<8)|c0 + 1` bytes from addr. Reply: 'F'
//   'K' <c0> <c1> <expected>     Check count+1 bytes match.    Reply: <miscount>
//   'S'                          Reply: <status> (bit0 busy, bit1 mismatch_flag)
//   'X'                          Clear mismatch flag. Reply: 'X'
//
// All registers driven from one always block to avoid multiple-driver issues.

// SRAM access timing is set by SPEED:
//   0 = conservative: 3-cycle write (60 ns), 4-cycle read (80 ns)
//   1 = fast:         2-cycle write (40 ns), 3-cycle read (60 ns)
//   2 = aggressive:   2-cycle write (40 ns), 2-cycle read (40 ns)
//   3 = redline:      1-cycle write (20 ns), 2-cycle read (40 ns)
module sram_test #(
    parameter integer CLK_HZ   = 50_000_000,
    parameter integer BAUD_BPS = 500_000,
    parameter integer SPEED    = 2
) (
    input  wire        clk_in,

    output wire        uart_tx,
    input  wire        uart_rx,

    output reg  [16:0] sram_a,
    inout  wire [7:0]  sram_dq,
    output reg         sram_ce_n,
    output reg         sram_we_n,
    output reg         sram_oe_n,

    output wire [7:0]  led_base,
    output wire [1:0]  led_core
);

    localparam integer BIT_T = CLK_HZ / BAUD_BPS;

    // -----------------------------------------------------------------------
    // Bidirectional DQ
    // -----------------------------------------------------------------------
    reg [7:0] dq_out;
    reg       dq_oe;
    assign sram_dq = dq_oe ? dq_out : 8'bz;

    reg [7:0] dq_in_sync;
    always @(posedge clk_in) dq_in_sync <= sram_dq;

    // -----------------------------------------------------------------------
    // UART RX
    // -----------------------------------------------------------------------
    reg [1:0] rxs    = 2'b11;
    reg [6:0] rxc    = 7'd0;
    reg [3:0] rxb    = 4'd0;
    reg [7:0] rxsr   = 8'd0;
    reg       rxbusy = 1'b0;
    reg       rxv    = 1'b0;
    reg [7:0] rxd    = 8'd0;

    // -----------------------------------------------------------------------
    // UART TX
    // -----------------------------------------------------------------------
    reg [6:0] txc     = 7'd0;
    reg [3:0] txb     = 4'd0;
    reg [9:0] txsr    = 10'h3FF;
    reg       txbusy  = 1'b0;
    reg       tx_req  = 1'b0;
    reg [7:0] tx_val  = 8'd0;
    assign uart_tx    = txsr[0];

    // -----------------------------------------------------------------------
    // SRAM op FSM
    //   write states: OP_W_HOLD (only used when SPEED<=1) → OP_W_END
    //   read states:  OP_R_WAIT (only used when SPEED==0) → OP_R_SETTLE
    //                 (only used when SPEED<=1) → OP_R_END
    // -----------------------------------------------------------------------
    localparam [2:0] OP_IDLE     = 3'd0,
                     OP_W_HOLD   = 3'd1,
                     OP_W_END    = 3'd2,
                     OP_R_WAIT   = 3'd3,
                     OP_R_SETTLE = 3'd4,
                     OP_R_END    = 3'd5;

    reg [2:0] op_state   = OP_IDLE;
    reg       op_go_w    = 1'b0;
    reg       op_go_r    = 1'b0;
    reg [7:0] op_wr_data = 8'd0;
    reg [7:0] op_rd_data = 8'd0;

    wire op_busy = (op_state != OP_IDLE);

    // -----------------------------------------------------------------------
    // Command FSM
    // -----------------------------------------------------------------------
    localparam [4:0] C_IDLE   = 5'd0,
                     C_A0     = 5'd1,
                     C_A1     = 5'd2,
                     C_A2     = 5'd3,
                     C_P_1    = 5'd4,
                     C_P_2    = 5'd5,
                     C_W_DAT  = 5'd6,
                     C_W_WAIT = 5'd7,
                     C_R_WAIT = 5'd8,
                     C_F_C0   = 5'd9,
                     C_F_C1   = 5'd10,
                     C_F_DAT  = 5'd11,
                     C_F_RUN  = 5'd12,
                     C_K_C0   = 5'd13,
                     C_K_C1   = 5'd14,
                     C_K_EXP  = 5'd15,
                     C_K_RUN  = 5'd16;

    reg [4:0]  cst            = C_IDLE;
    reg [16:0] cur_addr       = 17'd0;
    reg [15:0] fk_count       = 16'd0;
    reg [7:0]  fk_data        = 8'd0;
    reg [7:0]  miscount       = 8'd0;
    reg        last_was_write = 1'b0;
    reg        last_was_read  = 1'b0;
    reg        mismatch_flag  = 1'b0;
    reg [7:0]  last_wr_byte   = 8'h00;

    // -----------------------------------------------------------------------
    // Activity-stretch counters
    // -----------------------------------------------------------------------
    localparam integer STRETCH_CYC = CLK_HZ / 10;
    localparam integer STRETCH_W   = $clog2(STRETCH_CYC + 1);
    reg [STRETCH_W-1:0] rx_blip = 0;
    reg [STRETCH_W-1:0] tx_blip = 0;
    reg                 tx_req_prev = 1'b0;
    reg [25:0]          hb = 26'd0;

    // -----------------------------------------------------------------------
    // Single combined always block — everything synchronous to clk_in
    // -----------------------------------------------------------------------
    initial begin
        sram_a    = 17'h00000;
        sram_ce_n = 1'b1;
        sram_we_n = 1'b1;
        sram_oe_n = 1'b1;
        dq_out    = 8'h00;
        dq_oe     = 1'b0;
    end

    always @(posedge clk_in) begin
        // ----- defaults: rxv pulse, op_go_* hold until consumed -----
        rxs <= {rxs[0], uart_rx};
        rxv <= 1'b0;
        hb  <= hb + 26'd1;

        // ===== UART RX =====
        if (!rxbusy) begin
            if (!rxs[1]) begin
                rxbusy <= 1'b1;
                rxc    <= BIT_T/2 - 1;
                rxb    <= 4'd0;
            end
        end else if (rxc == 7'd0) begin
            rxc <= BIT_T - 1;
            if (rxb == 4'd0) begin
                if (rxs[1]) rxbusy <= 1'b0;
                rxb <= 4'd1;
            end else if (rxb <= 4'd8) begin
                rxsr <= {rxs[1], rxsr[7:1]};
                rxb  <= rxb + 4'd1;
            end else begin
                rxd    <= rxsr;
                rxv    <= 1'b1;
                rxbusy <= 1'b0;
            end
        end else
            rxc <= rxc - 7'd1;

        // ===== UART TX =====
        if (!txbusy) begin
            if (tx_req) begin
                txsr   <= {1'b1, tx_val, 1'b0};
                txbusy <= 1'b1;
                txb    <= 4'd0;
                txc    <= BIT_T - 1;
                tx_req <= 1'b0;
            end
        end else if (txc == 7'd0) begin
            txc  <= BIT_T - 1;
            txsr <= {1'b1, txsr[9:1]};
            if (txb == 4'd9)
                txbusy <= 1'b0;
            else
                txb <= txb + 4'd1;
        end else
            txc <= txc - 7'd1;

        // ===== SRAM op FSM =====
        case (op_state)
        OP_IDLE: begin
            sram_ce_n <= 1'b1;
            sram_we_n <= 1'b1;
            sram_oe_n <= 1'b1;
            dq_oe     <= 1'b0;
            if (op_go_w) begin
                dq_out    <= op_wr_data;
                dq_oe     <= 1'b1;
                sram_ce_n <= 1'b0;
                sram_we_n <= 1'b0;
                op_state  <= (SPEED <= 1) ? OP_W_HOLD : OP_W_END;
                op_go_w   <= 1'b0;
            end else if (op_go_r) begin
                sram_ce_n <= 1'b0;
                sram_oe_n <= 1'b0;
                op_state  <= (SPEED == 0) ? OP_R_WAIT :
                             (SPEED == 1) ? OP_R_SETTLE : OP_R_END;
                op_go_r   <= 1'b0;
            end
        end
        OP_W_HOLD:
            // extra cycle holding WE# low — SPEED 0 only
            op_state <= OP_W_END;
        OP_W_END: begin
            // deassert strobes; dq_oe stays 1 for one more cycle (next IDLE)
            // to satisfy tDH after WE# rising (spec 0 ns, we give one cycle).
            sram_we_n <= 1'b1;
            sram_ce_n <= 1'b1;
            op_state  <= OP_IDLE;
        end
        OP_R_WAIT:
            // extra settle cycle — SPEED 0 only (4-cycle read total)
            op_state <= OP_R_SETTLE;
        OP_R_SETTLE:
            // resync settle cycle — SPEED 0 and 1
            op_state <= OP_R_END;
        OP_R_END: begin
            // Sample sram_dq directly so Quartus packs an IO input FF — that
            // gives us 1 cycle of latency end-to-end, the same as a discrete
            // dq_in_sync FF in the fabric but without an extra fabric hop.
            op_rd_data <= sram_dq;
            sram_oe_n  <= 1'b1;
            sram_ce_n  <= 1'b1;
            op_state   <= OP_IDLE;
        end
        default: op_state <= OP_IDLE;
        endcase

        // ===== Command FSM (request side — sets op_go_*, sets tx_req) =====
        case (cst)
        C_IDLE: if (rxv) begin
            case (rxd)
            "A": cst <= C_A0;
            "P": begin
                tx_val <= cur_addr[7:0];
                tx_req <= 1'b1;
                cst    <= C_P_1;
            end
            "W": cst <= C_W_DAT;
            "R": begin
                sram_a         <= cur_addr;
                op_go_r        <= 1'b1;
                last_was_write <= 1'b0;
                last_was_read  <= 1'b1;
                cst            <= C_R_WAIT;
            end
            "F": cst <= C_F_C0;
            "K": cst <= C_K_C0;
            "S": begin
                tx_val <= {6'd0, mismatch_flag, op_busy};
                tx_req <= 1'b1;
            end
            "X": begin
                mismatch_flag <= 1'b0;
                tx_val        <= "X";
                tx_req        <= 1'b1;
            end
            default: ;
            endcase
        end

        C_A0: if (rxv) begin cur_addr[7:0]   <= rxd;        cst <= C_A1; end
        C_A1: if (rxv) begin cur_addr[15:8]  <= rxd;        cst <= C_A2; end
        C_A2: if (rxv) begin
            cur_addr[16] <= rxd[0];
            tx_val <= "A"; tx_req <= 1'b1;
            cst    <= C_IDLE;
        end

        C_P_1: if (!txbusy && !tx_req) begin
            tx_val <= cur_addr[15:8];
            tx_req <= 1'b1;
            cst    <= C_P_2;
        end
        C_P_2: if (!txbusy && !tx_req) begin
            tx_val <= {7'd0, cur_addr[16]};
            tx_req <= 1'b1;
            cst    <= C_IDLE;
        end

        C_W_DAT: if (rxv) begin
            sram_a         <= cur_addr;
            op_wr_data     <= rxd;
            op_go_w        <= 1'b1;
            last_wr_byte   <= rxd;
            last_was_write <= 1'b1;
            last_was_read  <= 1'b0;
            cst            <= C_W_WAIT;
        end
        C_W_WAIT: if (!op_busy && !op_go_w) begin
            cur_addr <= cur_addr + 17'd1;
            tx_val   <= "W"; tx_req <= 1'b1;
            cst      <= C_IDLE;
        end

        C_R_WAIT: if (!op_busy && !op_go_r) begin
            cur_addr <= cur_addr + 17'd1;
            tx_val   <= op_rd_data; tx_req <= 1'b1;
            cst      <= C_IDLE;
        end

        C_F_C0: if (rxv) begin fk_count[7:0]  <= rxd; cst <= C_F_C1; end
        C_F_C1: if (rxv) begin fk_count[15:8] <= rxd; cst <= C_F_DAT; end
        C_F_DAT: if (rxv) begin
            fk_data        <= rxd;
            sram_a         <= cur_addr;
            op_wr_data     <= rxd;
            op_go_w        <= 1'b1;
            last_wr_byte   <= rxd;
            last_was_write <= 1'b1;
            last_was_read  <= 1'b0;
            cst            <= C_F_RUN;
        end
        C_F_RUN: if (!op_busy && !op_go_w) begin
            cur_addr <= cur_addr + 17'd1;
            if (fk_count == 0) begin
                tx_val <= "F"; tx_req <= 1'b1;
                cst    <= C_IDLE;
            end else begin
                fk_count   <= fk_count - 16'd1;
                sram_a     <= cur_addr + 17'd1;
                op_wr_data <= fk_data;
                op_go_w    <= 1'b1;
            end
        end

        C_K_C0: if (rxv) begin fk_count[7:0]  <= rxd; cst <= C_K_C1; end
        C_K_C1: if (rxv) begin fk_count[15:8] <= rxd; cst <= C_K_EXP; end
        C_K_EXP: if (rxv) begin
            fk_data        <= rxd;
            miscount       <= 8'd0;
            sram_a         <= cur_addr;
            op_go_r        <= 1'b1;
            last_was_write <= 1'b0;
            last_was_read  <= 1'b1;
            cst            <= C_K_RUN;
        end
        C_K_RUN: if (!op_busy && !op_go_r) begin
            if (op_rd_data != fk_data) begin
                if (miscount != 8'hFF) miscount <= miscount + 8'd1;
                mismatch_flag <= 1'b1;
            end
            cur_addr <= cur_addr + 17'd1;
            if (fk_count == 0) begin
                tx_val <= (op_rd_data != fk_data && miscount != 8'hFF)
                          ? miscount + 8'd1 : miscount;
                tx_req <= 1'b1;
                cst    <= C_IDLE;
            end else begin
                fk_count <= fk_count - 16'd1;
                sram_a   <= cur_addr + 17'd1;
                op_go_r  <= 1'b1;
            end
        end

        default: cst <= C_IDLE;
        endcase

        // ===== Activity stretchers =====
        if (rxv) rx_blip <= STRETCH_CYC[STRETCH_W-1:0];
        else if (rx_blip != 0) rx_blip <= rx_blip - 1;

        tx_req_prev <= tx_req;
        if (tx_req & ~tx_req_prev) tx_blip <= STRETCH_CYC[STRETCH_W-1:0];
        else if (tx_blip != 0)     tx_blip <= tx_blip - 1;
    end

    assign led_base[0]   = hb[25];
    assign led_base[1]   = op_busy | (cst != C_IDLE);
    assign led_base[2]   = last_was_write;
    assign led_base[3]   = last_was_read;
    assign led_base[4]   = mismatch_flag;
    assign led_base[7:5] = last_wr_byte[7:5];
    assign led_core[0]   = (rx_blip != 0);
    assign led_core[1]   = (tx_blip != 0);

endmodule
