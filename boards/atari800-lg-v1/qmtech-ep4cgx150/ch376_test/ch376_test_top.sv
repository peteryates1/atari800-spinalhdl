// Minimal UART-to-SPI bridge for CH376T bring-up.
// Binary protocol, 500000 baud, 50 MHz clock.
//
// Commands:
//   'T' <byte>       SPI transfer 1 byte (CS unchanged), reply: <miso_byte>
//   'C' <0|1>        CS: 1=assert(low), 0=deassert(high)
//   'D' <lo> <hi>    Set SPI clock divider. SPI_CLK = 50M / (2*(div+1))
//   'P'              Read MISO pin, reply: 0x00 or 0x01
//   'I'              Read INT# pin, reply: 0x00(active) or 0x01(idle)
//   'W'              Wait for INT# low (up to ~84ms), reply: 'W'(ok) or 'T'(timeout)
//   'R'              HW reset CH376T (~84ms), reply: 'R'

module ch376_test_top (
    input  wire       clk_in,
    output wire       uart_tx,
    input  wire       uart_rx,
    output wire       ch376_sck,
    output wire       ch376_mosi,
    input  wire       ch376_miso,
    output reg        ch376_cs = 1'b1,
    input  wire       ch376_int,
    output reg        ch376_rst = 1'b1,
    output wire       ch376_spi_n,
    output wire [1:0] led
);
    assign ch376_spi_n = 1'b0;

    localparam CLK_HZ = 50_000_000, BAUD = 500_000;
    localparam BIT_T  = CLK_HZ / BAUD;  // 100

    // ---- Boot reset: hold CH376T reset ~84ms so SPI# is sampled ----
    reg [22:0] boot_cnt = 0;
    wire boot_done = boot_cnt[22];
    always @(posedge clk_in)
        if (!boot_done) boot_cnt <= boot_cnt + 1'd1;

    // ---- UART RX ----
    reg [1:0] rxs = 2'b11;
    reg [6:0] rxc = 0;
    reg [3:0] rxb = 0;
    reg [7:0] rxsr = 0;
    reg       rxbusy = 0, rxv = 0;
    reg [7:0] rxd = 0;

    // ---- UART TX ----
    reg [6:0] txc = 0;
    reg [3:0] txb = 0;
    reg [9:0] txsr = 10'h3FF;
    reg       txbusy = 0;
    assign uart_tx = txsr[0];

    // ---- SPI ----
    reg [15:0] sdiv = 16'd49;
    reg [15:0] scnt = 0;
    reg [3:0]  sbit = 0;
    reg [7:0]  stx = 0, srx = 0, srxd = 0;
    reg        sbusy = 0, sdone = 0, sclk = 0;
    assign ch376_sck  = sclk;
    assign ch376_mosi = stx[7];

    // ---- MISO sync ----
    reg [1:0] ms = 2'b11;

    // ---- INT# sync ----
    reg [1:0] ints = 2'b11;

    // ---- Command FSM ----
    localparam IDLE=0, T_BYTE=1, T_WAIT=2, T_TX=3, C_VAL=4, D_LO=5, D_HI=6,
               RST_WAIT=7, W_INT=8;
    reg [3:0]  st = IDLE;
    reg [7:0]  dlo = 0;
    reg [22:0] rst_cnt = 23'h7FFFFF;
    wire       rst_active = !rst_cnt[22];
    reg [22:0] wait_cnt = 23'h7FFFFF;
    wire       wait_active = !wait_cnt[22];

    // ---- tx request from FSM ----
    reg       tx_req = 0;
    reg [7:0] tx_val = 0;

    // ---- spi request from FSM ----
    reg       spi_req = 0;
    reg [7:0] spi_val = 0;

    always @(posedge clk_in) begin
        // ---- sync inputs ----
        rxs  <= {rxs[0], uart_rx};
        ms   <= {ms[0], ch376_miso};
        ints <= {ints[0], ch376_int};
        rxv <= 0;
        sdone <= 0;

        // ---- UART RX (8N1, LSB first) ----
        if (!rxbusy) begin
            if (!rxs[1]) begin
                rxbusy <= 1;
                rxc <= BIT_T/2 - 1;
                rxb <= 0;
            end
        end else if (rxc == 0) begin
            rxc <= BIT_T - 1;
            if (rxb == 0) begin
                // Mid start bit — verify still low
                if (rxs[1]) rxbusy <= 0;
                rxb <= 1;
            end else if (rxb <= 8) begin
                // Data bits d0..d7
                rxsr <= {rxs[1], rxsr[7:1]};
                rxb  <= rxb + 1'd1;
            end else begin
                // Stop bit — latch
                rxd   <= rxsr;
                rxv   <= 1;
                rxbusy <= 0;
            end
        end else
            rxc <= rxc - 1'd1;

        // ---- UART TX ----
        if (!txbusy) begin
            if (tx_req) begin
                txsr   <= {1'b1, tx_val, 1'b0};
                txbusy <= 1;
                txb    <= 0;
                txc    <= BIT_T - 1;
                tx_req <= 0;
            end
        end else if (txc == 0) begin
            txc  <= BIT_T - 1;
            txsr <= {1'b1, txsr[9:1]};
            if (txb == 9)
                txbusy <= 0;
            else
                txb <= txb + 1'd1;
        end else
            txc <= txc - 1'd1;

        // ---- SPI engine ----
        if (!sbusy) begin
            sclk <= 0;
            if (spi_req) begin
                stx     <= spi_val;
                sbusy   <= 1;
                sbit    <= 0;
                scnt    <= 0;
                spi_req <= 0;
            end
        end else if (scnt == sdiv) begin
            scnt <= 0;
            sclk <= ~sclk;
            if (!sclk)
                srx <= {srx[6:0], ms[1]};   // rising: sample
            else begin                        // falling: advance
                if (sbit == 7) begin
                    sbusy <= 0;
                    sclk  <= 0;
                    srxd  <= srx;
                    sdone <= 1;
                end else begin
                    stx  <= {stx[6:0], 1'b1};
                    sbit <= sbit + 1'd1;
                end
            end
        end else
            scnt <= scnt + 1'd1;

        // ---- Counters ----
        if (rst_active)
            rst_cnt <= rst_cnt + 1'd1;
        if (wait_active)
            wait_cnt <= wait_cnt + 1'd1;

        // ---- CH376T reset pin ----
        ch376_rst <= !boot_done || rst_active;

        // ---- Command FSM ----
        case (st)
        IDLE:
            if (rxv) case (rxd)
                "T": st <= T_BYTE;
                "C": st <= C_VAL;
                "D": st <= D_LO;
                "P": begin
                    tx_val <= {7'd0, ms[1]};
                    tx_req <= 1;
                end
                "I": begin
                    tx_val <= {7'd0, ints[1]};  // INT# pin: 0=active, 1=idle
                    tx_req <= 1;
                end
                "W": begin
                    wait_cnt <= 0;      // separate timeout counter (~84ms)
                    st <= W_INT;
                end
                "R": begin
                    rst_cnt <= 0;       // start reset
                    st <= RST_WAIT;
                end
                default: ;
            endcase

        T_BYTE:
            if (rxv) begin
                spi_val <= rxd;
                spi_req <= 1;
                st      <= T_WAIT;
            end

        T_WAIT:
            if (sdone)
                st <= T_TX;

        T_TX:
            if (!txbusy && !tx_req) begin
                tx_val <= srxd;
                tx_req <= 1;
                st     <= IDLE;
            end

        C_VAL:
            if (rxv) begin
                ch376_cs <= ~rxd[0];
                st <= IDLE;
            end

        D_LO:
            if (rxv) begin
                dlo <= rxd;
                st  <= D_HI;
            end

        D_HI:
            if (rxv) begin
                sdiv <= {rxd, dlo};
                tx_val <= "D";
                tx_req <= 1;
                st     <= IDLE;
            end

        W_INT:
            if (!ints[1]) begin         // INT# went low (active)
                tx_val <= "W";
                tx_req <= 1;
                st     <= IDLE;
            end else if (!wait_active) begin  // timeout (~84ms)
                tx_val <= "T";          // 'T' = timeout
                tx_req <= 1;
                st     <= IDLE;
            end

        RST_WAIT:
            if (!rst_active) begin
                tx_val <= "R";
                tx_req <= 1;
                st     <= IDLE;
            end

        default: st <= IDLE;
        endcase
    end

    // ---- LEDs ----
    reg [24:0] hb = 0;
    always @(posedge clk_in) hb <= hb + 1'd1;
    assign led[0] = hb[24];
    assign led[1] = ~ch376_cs;
endmodule
