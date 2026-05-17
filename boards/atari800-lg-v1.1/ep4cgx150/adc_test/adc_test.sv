// V1.1 paddle-ADC (MCP3208) bring-up.
//
// Continuously polls the four paddle channels (CH0..CH3 of the MCP3208) over
// SPI mode 0 at ~1 MHz, then streams a 10-byte packet over the CH340 UART
// every ~50 ms:
//
//   0xFF 0xFF                              (sync prefix — never a valid result)
//   {4'h0, ch0[11:8]}, ch0[7:0]            (12-bit value, big-endian)
//   {4'h0, ch1[11:8]}, ch1[7:0]
//   {4'h0, ch2[11:8]}, ch2[7:0]
//   {4'h0, ch3[11:8]}, ch3[7:0]
//
// MCP3208 SPI sequence per channel:
//   TX byte 0: 0x06 | (D2 << 0)    (5 leading zeros + start + SGL=1 + D2)
//   TX byte 1: {D1,D0,6'b000000}   (channel select tail)
//   TX byte 2: 0x00                (clock out lower 8 bits of result)
//   RX byte 1 ignored
//   RX byte 2 lower nibble = result bits [11:8]
//   RX byte 3 = result bits [7:0]
//
// LEDs:
//   led_base[0] heartbeat
//   led_base[1] poll-active
//   led_base[2..5] high 4 bits of ch0..ch3 result (visual coarse level)
//   led_core[0]  sclk_busy
//   led_core[1]  cs

module adc_test (
    input  wire        clk_in,

    output wire        uart_tx,
    input  wire        uart_rx,

    output reg         adc_sck,
    output wire        adc_mosi,
    input  wire        adc_miso,
    output reg         adc_cs,

    output wire [7:0]  led_base,
    output wire [1:0]  led_core
);

    wire _rx_keep = uart_rx;

    localparam integer CLK_HZ   = 50_000_000;
    localparam integer BAUD     = 115_200;
    localparam integer BIT_T    = CLK_HZ / BAUD;
    localparam integer SPI_DIV  = 24;      // sclk toggles every 25 cycles → 1 MHz SPI

    // -----------------------------------------------------------------------
    // ADC MISO synchroniser
    // -----------------------------------------------------------------------
    reg [1:0] miso_sync = 2'b00;
    always @(posedge clk_in) miso_sync <= {miso_sync[0], adc_miso};
    wire miso_in = miso_sync[1];

    // -----------------------------------------------------------------------
    // SPI byte engine (mode 0, MSB first)
    // -----------------------------------------------------------------------
    reg [4:0]  scnt   = 5'd0;
    reg [3:0]  sbit   = 4'd0;
    reg [7:0]  stx    = 8'h00;
    reg [7:0]  srx    = 8'h00;
    reg        sbusy  = 1'b0;
    reg        sdone  = 1'b0;
    reg        spi_req = 1'b0;
    reg [7:0]  spi_val = 8'h00;
    assign     adc_mosi = stx[7];

    // -----------------------------------------------------------------------
    // Channel poll FSM
    // -----------------------------------------------------------------------
    localparam [3:0] P_IDLE      = 4'd0,
                     P_CS_LOW   = 4'd1,
                     P_B1_SEND  = 4'd2,
                     P_B1_WAIT  = 4'd3,
                     P_B2_SEND  = 4'd4,
                     P_B2_WAIT  = 4'd5,
                     P_B3_SEND  = 4'd6,
                     P_B3_WAIT  = 4'd7,
                     P_CS_HIGH  = 4'd8,
                     P_NEXT     = 4'd9,
                     P_PAUSE    = 4'd10;

    reg [3:0]  pst      = P_IDLE;
    reg [1:0]  poll_ch  = 2'd0;
    reg [11:0] r0 = 12'h000, r1 = 12'h000, r2 = 12'h000, r3 = 12'h000;
    reg [11:0] r_curr;     // accumulator for current channel
    reg [5:0]  pause_cnt = 6'd0;

    // -----------------------------------------------------------------------
    // UART TX
    // -----------------------------------------------------------------------
    reg [9:0]  txc    = 10'd0;
    reg [3:0]  txb    = 4'd0;
    reg [9:0]  txsr   = 10'h3FF;
    reg        txbusy = 1'b0;
    reg        tx_req = 1'b0;
    reg [7:0]  tx_val = 8'h00;
    assign uart_tx = txsr[0];

    // -----------------------------------------------------------------------
    // Periodic packet send timer (~50 ms)
    // -----------------------------------------------------------------------
    reg [21:0] timer    = 22'd0;
    reg        tick_50  = 1'b0;
    localparam integer TIMER_TOP = CLK_HZ / 20;     // ~50 ms

    // Packet send FSM (10 bytes)
    reg [3:0]  send_state = 4'd0;
    reg [3:0]  byte_idx   = 4'd0;
    reg [7:0]  pkt [0:9];

    // -----------------------------------------------------------------------
    // Heartbeat
    // -----------------------------------------------------------------------
    reg [25:0] hb = 26'd0;

    // -----------------------------------------------------------------------
    // Combined always block — all drivers live here.
    // -----------------------------------------------------------------------
    initial begin
        adc_sck = 1'b0;
        adc_cs  = 1'b1;
    end

    always @(posedge clk_in) begin
        sdone <= 1'b0;
        tick_50 <= 1'b0;
        hb <= hb + 26'd1;

        // ===== timer =====
        if (timer == TIMER_TOP - 1) begin
            timer   <= 22'd0;
            tick_50 <= 1'b1;
        end else
            timer <= timer + 22'd1;

        // ===== SPI byte engine =====
        if (!sbusy) begin
            adc_sck <= 1'b0;
            if (spi_req) begin
                stx     <= spi_val;
                sbusy   <= 1'b1;
                sbit    <= 4'd0;
                scnt    <= 5'd0;
                spi_req <= 1'b0;
            end
        end else if (scnt == SPI_DIV) begin
            scnt <= 5'd0;
            adc_sck <= ~adc_sck;
            if (!adc_sck) begin
                // rising edge — slave samples MOSI; we sample MISO
                srx <= {srx[6:0], miso_in};
            end else begin
                // falling edge — shift MOSI to next bit
                if (sbit == 4'd7) begin
                    sbusy   <= 1'b0;
                    adc_sck <= 1'b0;
                    sdone   <= 1'b1;
                end else begin
                    stx  <= {stx[6:0], 1'b0};
                    sbit <= sbit + 4'd1;
                end
            end
        end else
            scnt <= scnt + 5'd1;

        // ===== Poll FSM =====
        case (pst)
        P_IDLE: begin
            poll_ch <= 2'd0;
            pst     <= P_CS_LOW;
        end
        P_CS_LOW: begin
            adc_cs <= 1'b0;
            pst    <= P_B1_SEND;
        end
        P_B1_SEND: begin
            // byte 1: 0x06 (start + SGL/DIFF + D2=0 for channels 0..3)
            spi_val <= 8'h06;
            spi_req <= 1'b1;
            pst     <= P_B1_WAIT;
        end
        P_B1_WAIT: if (sdone) pst <= P_B2_SEND;
        P_B2_SEND: begin
            // byte 2: {D1, D0, 6'b000000}
            spi_val <= {poll_ch, 6'b000000};
            spi_req <= 1'b1;
            pst     <= P_B2_WAIT;
        end
        P_B2_WAIT: if (sdone) begin
            r_curr[11:8] <= srx[3:0];
            pst          <= P_B3_SEND;
        end
        P_B3_SEND: begin
            spi_val <= 8'h00;
            spi_req <= 1'b1;
            pst     <= P_B3_WAIT;
        end
        P_B3_WAIT: if (sdone) begin
            r_curr[7:0] <= srx;
            case (poll_ch)
            2'd0: r0 <= {r_curr[11:8], srx};
            2'd1: r1 <= {r_curr[11:8], srx};
            2'd2: r2 <= {r_curr[11:8], srx};
            2'd3: r3 <= {r_curr[11:8], srx};
            endcase
            pst <= P_CS_HIGH;
        end
        P_CS_HIGH: begin
            adc_cs <= 1'b1;
            pst    <= P_NEXT;
        end
        P_NEXT: begin
            // Brief CS-high gap before the next channel
            pause_cnt <= 6'd0;
            pst       <= P_PAUSE;
        end
        P_PAUSE: begin
            if (pause_cnt == 6'd50) begin   // ~1 µs gap
                if (poll_ch == 2'd3) begin
                    pst <= P_IDLE;          // restart loop
                end else begin
                    poll_ch <= poll_ch + 2'd1;
                    pst     <= P_CS_LOW;
                end
            end else
                pause_cnt <= pause_cnt + 6'd1;
        end
        default: pst <= P_IDLE;
        endcase

        // ===== Packet sender =====
        case (send_state)
        4'd0: if (tick_50) begin
            pkt[0] <= 8'hFF;
            pkt[1] <= 8'hFF;
            pkt[2] <= {4'h0, r0[11:8]};
            pkt[3] <=  r0[7:0];
            pkt[4] <= {4'h0, r1[11:8]};
            pkt[5] <=  r1[7:0];
            pkt[6] <= {4'h0, r2[11:8]};
            pkt[7] <=  r2[7:0];
            pkt[8] <= {4'h0, r3[11:8]};
            pkt[9] <=  r3[7:0];
            byte_idx <= 4'd0;
            send_state <= 4'd1;
        end
        4'd1: if (!txbusy && !tx_req) begin
            tx_val <= pkt[byte_idx];
            tx_req <= 1'b1;
            send_state <= 4'd2;
        end
        4'd2: if (!txbusy && !tx_req) begin
            if (byte_idx == 4'd9) begin
                send_state <= 4'd0;
            end else begin
                byte_idx   <= byte_idx + 4'd1;
                send_state <= 4'd1;
            end
        end
        default: send_state <= 4'd0;
        endcase

        // ===== UART TX shift =====
        if (!txbusy) begin
            if (tx_req) begin
                txsr   <= {1'b1, tx_val, 1'b0};
                txbusy <= 1'b1;
                txb    <= 4'd0;
                txc    <= BIT_T - 1;
                tx_req <= 1'b0;
            end
        end else if (txc == 10'd0) begin
            txc  <= BIT_T - 1;
            txsr <= {1'b1, txsr[9:1]};
            if (txb == 4'd9) txbusy <= 1'b0;
            else             txb    <= txb + 4'd1;
        end else
            txc <= txc - 10'd1;
    end

    // -----------------------------------------------------------------------
    // LEDs
    // -----------------------------------------------------------------------
    assign led_base[0] = hb[25];
    assign led_base[1] = (pst != P_IDLE);
    assign led_base[2] = r0[11];
    assign led_base[3] = r1[11];
    assign led_base[4] = r2[11];
    assign led_base[5] = r3[11];
    assign led_base[6] = sbusy;
    assign led_base[7] = ~adc_cs;
    assign led_core[0] = sbusy;
    assign led_core[1] = ~adc_cs;

endmodule
