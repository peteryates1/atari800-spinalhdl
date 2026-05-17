// V1.1 10CL025 SDRAM smoke test.
//
// Wraps the minimal sdram_ctrl with a UART-bridge for host-driven walking
// patterns. 16-bit word access. 24-bit word address (16M words = 32 MB).
//
// Host protocol (UART, 500_000 baud, 8N1):
//   'A' a0 a1 a2          set 24-bit word address (LE).  Reply: 'A'
//   'P'                   read back 24-bit address.       Reply: a0 a1 a2
//   'W' d0 d1             write 16-bit word (LE) at addr, addr++.  Reply: 'W'
//   'R'                   read 16-bit word, addr++.       Reply: d0 d1
//   'F' c0 c1 c2 d0 d1    fill (c+1) words from addr with d0:d1.  Reply: 'F'
//   'K' c0 c1 c2 d0 d1    check (c+1) words match d0:d1.  Reply: <miscount>
//   'S'                   status:                          Reply: <byte>
//                           bit0 busy
//                           bit1 mismatch sticky
//                           bit2 init_done
//   'X'                   clear mismatch flag.             Reply: 'X'

module sdram_test (
    input  wire        clk_in,

    output wire        uart_tx,
    input  wire        uart_rx,

    // SDRAM
    output wire        sdram_clk,
    output wire        sdram_cke,
    output wire        sdram_csn,
    output wire        sdram_rasn,
    output wire        sdram_casn,
    output wire        sdram_wen,
    output wire [1:0]  sdram_ba,
    output wire [12:0] sdram_addr,
    output wire [1:0]  sdram_dqm,
    inout  wire [15:0] sdram_dq,

    output wire [7:0]  led_base,
    output wire [0:0]  led_core
);

    wire _rx_keep = uart_rx;

    // -----------------------------------------------------------------------
    // SDRAM controller
    // -----------------------------------------------------------------------
    reg         req_we, req_rd;
    reg  [23:0] req_addr;
    reg  [15:0] req_wdata;
    wire        ctrl_busy;
    wire        init_done;
    wire [15:0] rd_data;
    wire        rd_valid;

    sdram_ctrl u_sdram (
        .clk        (clk_in),
        .rst_n      (1'b1),
        .req_we     (req_we),
        .req_rd     (req_rd),
        .req_addr   (req_addr),
        .req_wdata  (req_wdata),
        .req_dqm    (2'b00),
        .busy       (ctrl_busy),
        .init_done  (init_done),
        .rdata      (rd_data),
        .rd_valid   (rd_valid),
        .sdram_clk  (sdram_clk),
        .sdram_cke  (sdram_cke),
        .sdram_csn  (sdram_csn),
        .sdram_rasn (sdram_rasn),
        .sdram_casn (sdram_casn),
        .sdram_wen  (sdram_wen),
        .sdram_ba   (sdram_ba),
        .sdram_addr (sdram_addr),
        .sdram_dqm  (sdram_dqm),
        .sdram_dq   (sdram_dq)
    );

    // -----------------------------------------------------------------------
    // UART RX / TX
    // -----------------------------------------------------------------------
    localparam integer CLK_HZ = 50_000_000;
    localparam integer BAUD   = 500_000;
    localparam integer BIT_T  = CLK_HZ / BAUD;

    reg [1:0] rxs  = 2'b11;
    reg [6:0] rxc  = 0;
    reg [3:0] rxb  = 0;
    reg [7:0] rxsr = 0;
    reg       rxbusy = 0, rxv = 0;
    reg [7:0] rxd = 0;

    reg [6:0] txc    = 0;
    reg [3:0] txb    = 0;
    reg [9:0] txsr   = 10'h3FF;
    reg       txbusy = 0;
    reg       tx_req = 0;
    reg [7:0] tx_val = 0;
    assign uart_tx = txsr[0];

    // -----------------------------------------------------------------------
    // Command FSM
    // -----------------------------------------------------------------------
    localparam [4:0] C_IDLE   = 5'd0,
                     C_A0     = 5'd1,
                     C_A1     = 5'd2,
                     C_A2     = 5'd3,
                     C_P_1    = 5'd4,
                     C_P_2    = 5'd5,
                     C_W_D0   = 5'd6,
                     C_W_D1   = 5'd7,
                     C_W_WAIT = 5'd8,
                     C_R_WAIT = 5'd9,
                     C_R_TX   = 5'd10,
                     C_F_C0   = 5'd11,
                     C_F_C1   = 5'd12,
                     C_F_C2   = 5'd13,
                     C_F_D0   = 5'd14,
                     C_F_D1   = 5'd15,
                     C_F_RUN  = 5'd16,
                     C_K_C0   = 5'd17,
                     C_K_C1   = 5'd18,
                     C_K_C2   = 5'd19,
                     C_K_D0   = 5'd20,
                     C_K_D1   = 5'd21,
                     C_K_RUN  = 5'd22;

    reg [4:0]  cst;
    reg [23:0] cur_addr;
    reg [23:0] fk_count;
    reg [15:0] fk_data;
    reg [7:0]  miscount;
    reg [7:0]  rd_lo;
    reg        mismatch_flag;

    reg [25:0] hb = 26'd0;

    initial begin
        cst           = C_IDLE;
        cur_addr      = 24'd0;
        fk_count      = 24'd0;
        fk_data       = 16'd0;
        miscount      = 8'd0;
        rd_lo         = 8'd0;
        mismatch_flag = 1'b0;
        req_we        = 1'b0;
        req_rd        = 1'b0;
        req_addr      = 24'd0;
        req_wdata     = 16'd0;
    end

    always @(posedge clk_in) begin
        // ----- defaults -----
        rxs <= {rxs[0], uart_rx};
        rxv <= 1'b0;
        hb  <= hb + 26'd1;

        // ----- UART RX -----
        if (!rxbusy) begin
            if (!rxs[1]) begin
                rxbusy <= 1; rxc <= BIT_T/2 - 1; rxb <= 0;
            end
        end else if (rxc == 0) begin
            rxc <= BIT_T - 1;
            if (rxb == 0) begin
                if (rxs[1]) rxbusy <= 0;
                rxb <= 1;
            end else if (rxb <= 8) begin
                rxsr <= {rxs[1], rxsr[7:1]};
                rxb  <= rxb + 4'd1;
            end else begin
                rxd <= rxsr; rxv <= 1; rxbusy <= 0;
            end
        end else rxc <= rxc - 7'd1;

        // ----- UART TX -----
        if (!txbusy) begin
            if (tx_req) begin
                txsr <= {1'b1, tx_val, 1'b0}; txbusy <= 1;
                txb  <= 0; txc <= BIT_T - 1; tx_req <= 0;
            end
        end else if (txc == 0) begin
            txc  <= BIT_T - 1;
            txsr <= {1'b1, txsr[9:1]};
            if (txb == 9) txbusy <= 0; else txb <= txb + 4'd1;
        end else txc <= txc - 7'd1;

        // ----- Clear single-cycle pulses once controller accepts them -----
        if (req_we && ctrl_busy) req_we <= 1'b0;
        if (req_rd && ctrl_busy) req_rd <= 1'b0;

        // ----- Command FSM -----
        case (cst)
        C_IDLE: if (rxv) begin
            case (rxd)
            "A": cst <= C_A0;
            "P": begin tx_val <= cur_addr[7:0];  tx_req <= 1; cst <= C_P_1; end
            "W": cst <= C_W_D0;
            "R": begin
                req_addr <= cur_addr;
                req_rd   <= 1'b1;
                cst      <= C_R_WAIT;
            end
            "F": cst <= C_F_C0;
            "K": cst <= C_K_C0;
            "S": begin
                tx_val <= {5'd0, init_done, mismatch_flag, ctrl_busy};
                tx_req <= 1'b1;
            end
            "X": begin
                mismatch_flag <= 1'b0;
                tx_val <= "X"; tx_req <= 1'b1;
            end
            default: ;
            endcase
        end

        // A: 24-bit address
        C_A0: if (rxv) begin cur_addr[7:0]   <= rxd; cst <= C_A1; end
        C_A1: if (rxv) begin cur_addr[15:8]  <= rxd; cst <= C_A2; end
        C_A2: if (rxv) begin
            cur_addr[23:16] <= rxd;
            tx_val <= "A"; tx_req <= 1; cst <= C_IDLE;
        end

        // P: read addr
        C_P_1: if (!txbusy && !tx_req) begin
            tx_val <= cur_addr[15:8]; tx_req <= 1; cst <= C_P_2;
        end
        C_P_2: if (!txbusy && !tx_req) begin
            tx_val <= cur_addr[23:16]; tx_req <= 1; cst <= C_IDLE;
        end

        // W: write word
        C_W_D0: if (rxv) begin req_wdata[7:0]  <= rxd; cst <= C_W_D1; end
        C_W_D1: if (rxv) begin
            req_wdata[15:8] <= rxd;
            req_addr <= cur_addr;
            req_we   <= 1'b1;
            cst      <= C_W_WAIT;
        end
        C_W_WAIT: if (!ctrl_busy && !req_we) begin
            cur_addr <= cur_addr + 24'd1;
            tx_val   <= "W"; tx_req <= 1;
            cst      <= C_IDLE;
        end

        // R: read word
        C_R_WAIT: if (rd_valid) begin
            cur_addr <= cur_addr + 24'd1;
            tx_val <= rd_data[7:0]; tx_req <= 1;
            rd_lo  <= rd_data[15:8];
            cst    <= C_R_TX;
        end
        C_R_TX: if (!txbusy && !tx_req) begin
            tx_val <= rd_lo; tx_req <= 1; cst <= C_IDLE;
        end

        // F: fill
        C_F_C0: if (rxv) begin fk_count[7:0]   <= rxd; cst <= C_F_C1; end
        C_F_C1: if (rxv) begin fk_count[15:8]  <= rxd; cst <= C_F_C2; end
        C_F_C2: if (rxv) begin fk_count[23:16] <= rxd; cst <= C_F_D0; end
        C_F_D0: if (rxv) begin fk_data[7:0]    <= rxd; cst <= C_F_D1; end
        C_F_D1: if (rxv) begin
            fk_data[15:8] <= rxd;
            req_addr  <= cur_addr;
            req_wdata <= {rxd, fk_data[7:0]};
            req_we    <= 1'b1;
            cst       <= C_F_RUN;
        end
        C_F_RUN: if (!ctrl_busy && !req_we) begin
            cur_addr <= cur_addr + 24'd1;
            if (fk_count == 0) begin
                tx_val <= "F"; tx_req <= 1; cst <= C_IDLE;
            end else begin
                fk_count <= fk_count - 24'd1;
                req_addr <= cur_addr + 24'd1;
                req_wdata <= fk_data;
                req_we    <= 1'b1;
            end
        end

        // K: check
        C_K_C0: if (rxv) begin fk_count[7:0]   <= rxd; cst <= C_K_C1; end
        C_K_C1: if (rxv) begin fk_count[15:8]  <= rxd; cst <= C_K_C2; end
        C_K_C2: if (rxv) begin fk_count[23:16] <= rxd; cst <= C_K_D0; end
        C_K_D0: if (rxv) begin fk_data[7:0]    <= rxd; cst <= C_K_D1; end
        C_K_D1: if (rxv) begin
            fk_data[15:8] <= rxd;
            miscount   <= 8'd0;
            req_addr   <= cur_addr;
            req_rd     <= 1'b1;
            cst        <= C_K_RUN;
        end
        C_K_RUN: if (rd_valid) begin
            if (rd_data != fk_data) begin
                if (miscount != 8'hFF) miscount <= miscount + 8'd1;
                mismatch_flag <= 1'b1;
            end
            cur_addr <= cur_addr + 24'd1;
            if (fk_count == 0) begin
                tx_val <= (rd_data != fk_data && miscount != 8'hFF)
                          ? miscount + 8'd1 : miscount;
                tx_req <= 1; cst <= C_IDLE;
            end else begin
                fk_count <= fk_count - 24'd1;
                req_addr <= cur_addr + 24'd1;
                req_rd   <= 1'b1;
            end
        end

        default: cst <= C_IDLE;
        endcase
    end

    // -----------------------------------------------------------------------
    // LEDs
    // -----------------------------------------------------------------------
    assign led_base[0] = hb[25];
    assign led_base[1] = init_done;
    assign led_base[2] = ctrl_busy;
    assign led_base[3] = mismatch_flag;
    assign led_base[4] = req_we | req_rd;
    assign led_base[5] = rd_valid;
    assign led_base[6] = 1'b0;
    assign led_base[7] = (cst != C_IDLE);
    assign led_core[0] = init_done & ~mismatch_flag;

endmodule
