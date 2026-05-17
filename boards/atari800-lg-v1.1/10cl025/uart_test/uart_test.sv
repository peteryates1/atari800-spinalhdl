// V1.1 base-board UART loopback smoke test.
// Echoes received bytes back to the host via the CH340 USB-serial (115200 8N1).
//
// Activity indicators:
//   led_base[0] — heartbeat (counter MSB), shows fabric+clock alive
//   led_base[1] — rx activity stretch (~100 ms after each received byte)
//   led_base[2] — tx activity stretch (~100 ms after each transmitted byte)
//   led_base[3] — last-received-byte bit 0 (latched)
//   led_base[4] — last-received-byte bit 1 (latched)
//   led_base[5] — last-received-byte bit 2 (latched)
//   led_base[6] — last-received-byte bit 3 (latched)
//   led_base[7] — last-received-byte bit 4 (latched)
//   led_core[0] — rx framing-error sticky flag
//   led_core[1] — tx-busy

module uart_test #(
    parameter integer CLK_HZ   = 50_000_000,
    parameter integer BAUD_BPS = 115_200
) (
    input  wire       clk_in,
    input  wire       uart_rx,   // FPGA input from CH340 TXD
    output wire       uart_tx,   // FPGA output to CH340 RXD
    output wire [7:0] led_base,
    output wire [0:0] led_core           // 10CL025 core board has only 1 LED
);

    localparam integer CYCLES_PER_BIT = (CLK_HZ + (BAUD_BPS/2)) / BAUD_BPS;
    localparam integer BIT_W          = $clog2(CYCLES_PER_BIT + 1);

    // -----------------------------------------------------------------------
    // Synchroniser for uart_rx
    // -----------------------------------------------------------------------
    reg [2:0] rx_sync = 3'b111;
    always @(posedge clk_in) rx_sync <= {rx_sync[1:0], uart_rx};
    wire rx_line = rx_sync[2];

    // -----------------------------------------------------------------------
    // Receiver
    // -----------------------------------------------------------------------
    reg [3:0]        rx_state;          // 0=idle, 1..8=data bits, 9=stop
    reg [BIT_W-1:0]  rx_cnt;
    reg [7:0]        rx_shift;
    reg [7:0]        rx_data;
    reg              rx_strobe;         // 1-cycle pulse when byte complete
    reg              rx_frame_err;

    initial begin
        rx_state     = 4'd0;
        rx_cnt       = '0;
        rx_shift     = 8'h00;
        rx_data      = 8'h00;
        rx_strobe    = 1'b0;
        rx_frame_err = 1'b0;
    end

    always @(posedge clk_in) begin
        rx_strobe <= 1'b0;
        case (rx_state)
            4'd0: begin
                // Idle — wait for start bit (line low)
                if (!rx_line) begin
                    rx_cnt   <= (CYCLES_PER_BIT >> 1);  // sample at mid-bit
                    rx_state <= 4'd1;
                end
            end
            default: begin
                if (rx_cnt == 0) begin
                    rx_cnt <= CYCLES_PER_BIT - 1;
                    if (rx_state == 4'd1) begin
                        // Mid-start-bit re-check
                        if (rx_line) begin
                            rx_state <= 4'd0;  // glitch; abort
                        end else begin
                            rx_state <= 4'd2;
                        end
                    end else if (rx_state <= 4'd9) begin
                        // Data bits LSB-first
                        rx_shift <= {rx_line, rx_shift[7:1]};
                        rx_state <= rx_state + 4'd1;
                    end else begin
                        // Stop bit
                        if (rx_line) begin
                            rx_data   <= rx_shift;
                            rx_strobe <= 1'b1;
                        end else begin
                            rx_frame_err <= 1'b1;
                        end
                        rx_state <= 4'd0;
                    end
                end else begin
                    rx_cnt <= rx_cnt - 1;
                end
            end
        endcase
    end

    // -----------------------------------------------------------------------
    // 16-deep RX→TX FIFO. Needed because at 115200 8N1 the host can send a
    // new byte every ~87 us, but the TX side also needs ~87 us to echo one,
    // so without buffering rx_strobe is dropped while tx_busy.
    // -----------------------------------------------------------------------
    localparam integer FIFO_AW = 4;                   // 16 entries
    reg [7:0]            fifo_mem [0:(1<<FIFO_AW)-1];
    reg [FIFO_AW:0]      fifo_wr = '0;
    reg [FIFO_AW:0]      fifo_rd = '0;
    wire                 fifo_empty = (fifo_wr == fifo_rd);
    wire                 fifo_full  = (fifo_wr[FIFO_AW-1:0] == fifo_rd[FIFO_AW-1:0])
                                      && (fifo_wr[FIFO_AW] != fifo_rd[FIFO_AW]);
    reg                  fifo_overflow = 1'b0;

    reg                  fifo_pop;
    wire [7:0]           fifo_dout = fifo_mem[fifo_rd[FIFO_AW-1:0]];

    always @(posedge clk_in) begin
        if (rx_strobe) begin
            if (!fifo_full) begin
                fifo_mem[fifo_wr[FIFO_AW-1:0]] <= rx_data;
                fifo_wr <= fifo_wr + 1;
            end else begin
                fifo_overflow <= 1'b1;
            end
        end
        if (fifo_pop) fifo_rd <= fifo_rd + 1;
    end

    // -----------------------------------------------------------------------
    // Transmitter — drains the FIFO
    // -----------------------------------------------------------------------
    reg [3:0]        tx_state;          // 0=idle, 1=start, 2..9=data, 10=stop
    reg [BIT_W-1:0]  tx_cnt;
    reg [7:0]        tx_shift;
    reg              tx_line;
    reg              tx_busy;

    initial begin
        tx_state = 4'd0;
        tx_cnt   = '0;
        tx_shift = 8'h00;
        tx_line  = 1'b1;
        tx_busy  = 1'b0;
        fifo_pop = 1'b0;
    end

    assign uart_tx = tx_line;

    always @(posedge clk_in) begin
        fifo_pop <= 1'b0;
        if (tx_state == 4'd0) begin
            tx_line <= 1'b1;
            tx_busy <= 1'b0;
            if (!fifo_empty && !fifo_pop) begin
                tx_shift <= fifo_dout;
                tx_cnt   <= CYCLES_PER_BIT - 1;
                tx_line  <= 1'b0;          // start bit
                tx_busy  <= 1'b1;
                fifo_pop <= 1'b1;
                tx_state <= 4'd1;
            end
        end else if (tx_cnt == 0) begin
            tx_cnt <= CYCLES_PER_BIT - 1;
            case (tx_state)
                4'd1: begin
                    tx_line  <= tx_shift[0];
                    tx_shift <= {1'b1, tx_shift[7:1]};
                    tx_state <= 4'd2;
                end
                4'd2, 4'd3, 4'd4, 4'd5, 4'd6, 4'd7, 4'd8: begin
                    tx_line  <= tx_shift[0];
                    tx_shift <= {1'b1, tx_shift[7:1]};
                    tx_state <= tx_state + 4'd1;
                end
                4'd9: begin
                    tx_line  <= 1'b1;         // stop bit
                    tx_state <= 4'd10;
                end
                default: begin
                    tx_state <= 4'd0;         // back to idle
                end
            endcase
        end else begin
            tx_cnt <= tx_cnt - 1;
        end
    end

    // -----------------------------------------------------------------------
    // Heartbeat + activity stretchers
    // -----------------------------------------------------------------------
    reg [25:0] hb = 26'd0;
    always @(posedge clk_in) hb <= hb + 26'd1;

    // ~100 ms stretch = 0.1 s * 50e6 = 5e6 cycles → 23-bit counter (8.3M).
    localparam integer STRETCH_CYCLES = CLK_HZ / 10;
    localparam integer STRETCH_W      = $clog2(STRETCH_CYCLES + 1);

    reg [STRETCH_W-1:0] rx_blip = '0;
    reg [STRETCH_W-1:0] tx_blip = '0;

    always @(posedge clk_in) begin
        if (rx_strobe) rx_blip <= STRETCH_CYCLES[STRETCH_W-1:0];
        else if (rx_blip != 0) rx_blip <= rx_blip - 1;
    end

    reg tx_busy_prev = 1'b0;
    always @(posedge clk_in) begin
        tx_busy_prev <= tx_busy;
        if (tx_busy & ~tx_busy_prev) tx_blip <= STRETCH_CYCLES[STRETCH_W-1:0];
        else if (tx_blip != 0)        tx_blip <= tx_blip - 1;
    end

    assign led_base[0] = hb[25];
    assign led_base[1] = (rx_blip != 0);
    assign led_base[2] = (tx_blip != 0);
    assign led_base[3] = rx_data[0];
    assign led_base[4] = rx_data[1];
    assign led_base[5] = rx_data[2];
    assign led_base[6] = rx_data[3];
    assign led_base[7] = rx_data[4];
    // 10CL025 has one core LED — OR all the indicators together.
    assign led_core[0] = rx_frame_err | fifo_overflow | tx_busy;

endmodule
