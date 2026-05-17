// V1.1 dual CH376T bring-up bridge.
// UART-to-SPI bridge for poking at both CH376T chips (USB keyboard + SD card)
// from a host PC. Drives the 12 MHz CH376T clock input from the FPGA PLL.
//
// Protocol: binary, 500000 baud, 8N1.
//   'B' <0|1>      Select bus  (0 = KB, 1 = SD), reply: 'B'
//   'T' <byte>     SPI transfer 1 byte on selected bus, reply: <miso_byte>
//   'C' <0|1>      CS on selected bus  (1 = assert/low, 0 = deassert/high)
//   'D' <lo> <hi>  Set SPI clock divider, reply: 'D'
//   'P'            Read MISO pin of selected bus, reply: 0x00/0x01
//   'I'            Read INT# pin of selected bus, reply: 0x00(active)/0x01(idle)
//   'W'            Wait for INT# low (up to ~84 ms), reply: 'W'(ok) or 'T'(timeout)
//   'R'            HW reset selected CH376T (~84 ms), reply: 'R'
//   'L'            Lock status of 12 MHz PLL, reply: 0x00/0x01
//
// LEDs (active-high, all base-board LEDs):
//   led_base[0] heartbeat
//   led_base[1] pll_locked
//   led_base[2] cs_kb_asserted     (KB CS low)
//   led_base[3] cs_sd_asserted     (SD CS low)
//   led_base[4] !int_kb            (KB INT# low = activity)
//   led_base[5] !int_sd            (SD INT# low = activity)
//   led_base[6] uart_rx blip       (~100 ms stretch)
//   led_base[7] uart_tx blip       (~100 ms stretch)
//   led_core[0] bus_sel            (0 = KB dim, 1 = SD lit)
//   led_core[1] spi_busy
//
// The CH376T SPI# (mode select) pin is hardwired on the V1.1 base board, so
// no FPGA pin drives it.

module ch376_test (
    input  wire clk_in,                   // PIN_B14, 50 MHz

    // UART (CH340)
    output wire uart_tx,
    input  wire uart_rx,

    // CH376T_KB (USB keyboard)
    output wire ch376_kb_sck,
    output wire ch376_kb_mosi,
    input  wire ch376_kb_miso,
    output reg  ch376_kb_cs = 1'b1,
    input  wire ch376_kb_int,
    output reg  ch376_kb_rst = 1'b1,

    // CH376T_SD (SD card)
    output wire ch376_sd_sck,
    output wire ch376_sd_mosi,
    input  wire ch376_sd_miso,
    output reg  ch376_sd_cs = 1'b1,
    input  wire ch376_sd_int,
    output reg  ch376_sd_rst = 1'b1,

    // 12 MHz output to both CH376Ts (replaces crystal)
    output wire ch376_12mhz,

    // Status
    output wire [7:0] led_base,
    output wire [0:0] led_core           // 10CL025: one core LED only
);

    // -----------------------------------------------------------------------
    // 12 MHz PLL drives the CH376T CLKI pin
    // -----------------------------------------------------------------------
    wire pll_locked;
    ch376_pll u_pll (
        .areset (1'b0),
        .inclk0 (clk_in),
        .c0     (ch376_12mhz),
        .locked (pll_locked)
    );

    // -----------------------------------------------------------------------
    // Hold all CH376T resets active until the PLL has locked AND a power-on
    // delay has elapsed (~84 ms) so the chips see a stable 12 MHz clock
    // before sampling SPI mode pins on their internal POR.
    // -----------------------------------------------------------------------
    localparam CLK_HZ = 50_000_000;
    localparam BAUD   = 500_000;
    localparam BIT_T  = CLK_HZ / BAUD;          // 100 cycles/bit

    reg [22:0] boot_cnt = 0;
    wire       boot_done = boot_cnt[22];
    always @(posedge clk_in) begin
        if (!boot_done) boot_cnt <= boot_cnt + 1'd1;
    end

    // -----------------------------------------------------------------------
    // UART RX (8N1, LSB first)
    // -----------------------------------------------------------------------
    reg [1:0] rxs = 2'b11;
    reg [6:0] rxc = 0;
    reg [3:0] rxb = 0;
    reg [7:0] rxsr = 0;
    reg       rxbusy = 0, rxv = 0;
    reg [7:0] rxd = 0;

    // -----------------------------------------------------------------------
    // UART TX
    // -----------------------------------------------------------------------
    reg [6:0] txc = 0;
    reg [3:0] txb = 0;
    reg [9:0] txsr = 10'h3FF;
    reg       txbusy = 0;
    assign uart_tx = txsr[0];

    // -----------------------------------------------------------------------
    // SPI bus shared between KB + SD, selected by bus_sel
    // -----------------------------------------------------------------------
    reg [15:0] sdiv = 16'd49;       // ~500 kHz SPI default
    reg [15:0] scnt = 0;
    reg [3:0]  sbit = 0;
    reg [7:0]  stx = 0, srx = 0, srxd = 0;
    reg        sbusy = 0, sdone = 0, sclk = 0;
    reg        bus_sel = 1'b0;      // 0 = KB, 1 = SD

    wire miso_active = bus_sel ? ch376_sd_miso : ch376_kb_miso;
    wire int_active  = bus_sel ? ch376_sd_int  : ch376_kb_int;

    // Drive both buses; only the selected one toggles, the other is parked.
    assign ch376_kb_sck  = (~bus_sel) ? sclk    : 1'b0;
    assign ch376_kb_mosi = (~bus_sel) ? stx[7]  : 1'b1;
    assign ch376_sd_sck  = ( bus_sel) ? sclk    : 1'b0;
    assign ch376_sd_mosi = ( bus_sel) ? stx[7]  : 1'b1;

    // -----------------------------------------------------------------------
    // MISO + INT# synchronisers (one each, on selected bus only)
    // -----------------------------------------------------------------------
    reg [1:0] ms   = 2'b11;
    reg [1:0] ints = 2'b11;

    // -----------------------------------------------------------------------
    // Command FSM
    // -----------------------------------------------------------------------
    localparam IDLE=0, T_BYTE=1, T_WAIT=2, T_TX=3, C_VAL=4, D_LO=5, D_HI=6,
               RST_WAIT=7, W_INT=8, B_VAL=9;
    reg [3:0]  st = IDLE;
    reg [7:0]  dlo = 0;
    reg [22:0] rst_cnt = 23'h7FFFFF;
    wire       rst_active = !rst_cnt[22];
    reg [22:0] wait_cnt = 23'h7FFFFF;
    wire       wait_active = !wait_cnt[22];

    reg       tx_req = 0;
    reg [7:0] tx_val = 0;

    reg       spi_req = 0;
    reg [7:0] spi_val = 0;

    always @(posedge clk_in) begin
        // sync inputs
        rxs  <= {rxs[0], uart_rx};
        ms   <= {ms[0], miso_active};
        ints <= {ints[0], int_active};
        rxv <= 0;
        sdone <= 0;

        // ---- UART RX ----
        if (!rxbusy) begin
            if (!rxs[1]) begin
                rxbusy <= 1;
                rxc <= BIT_T/2 - 1;
                rxb <= 0;
            end
        end else if (rxc == 0) begin
            rxc <= BIT_T - 1;
            if (rxb == 0) begin
                if (rxs[1]) rxbusy <= 0;
                rxb <= 1;
            end else if (rxb <= 8) begin
                rxsr <= {rxs[1], rxsr[7:1]};
                rxb  <= rxb + 1'd1;
            end else begin
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

        // ---- SPI engine (mode 0: CPOL=0 CPHA=0) ----
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
                srx <= {srx[6:0], ms[1]};        // rising: sample
            else begin                            // falling: advance
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
        if (rst_active)  rst_cnt  <= rst_cnt  + 1'd1;
        if (wait_active) wait_cnt <= wait_cnt + 1'd1;

        // ---- CH376T reset pins ----
        // Hold both in reset until boot_done & PLL locked. Per-bus runtime
        // reset (RST_WAIT) only releases the selected chip.
        if (!boot_done || !pll_locked) begin
            ch376_kb_rst <= 1'b1;
            ch376_sd_rst <= 1'b1;
        end else if (rst_active) begin
            // Drive only the selected chip's reset during runtime 'R'.
            ch376_kb_rst <= (~bus_sel) ? 1'b1 : 1'b0;
            ch376_sd_rst <= ( bus_sel) ? 1'b1 : 1'b0;
        end else begin
            ch376_kb_rst <= 1'b0;
            ch376_sd_rst <= 1'b0;
        end

        // ---- Command FSM ----
        case (st)
        IDLE:
            if (rxv) case (rxd)
                "B": st <= B_VAL;
                "T": st <= T_BYTE;
                "C": st <= C_VAL;
                "D": st <= D_LO;
                "P": begin
                    tx_val <= {7'd0, ms[1]};
                    tx_req <= 1;
                end
                "I": begin
                    tx_val <= {7'd0, ints[1]};
                    tx_req <= 1;
                end
                "L": begin
                    tx_val <= {7'd0, pll_locked};
                    tx_req <= 1;
                end
                "W": begin
                    wait_cnt <= 0;
                    st <= W_INT;
                end
                "R": begin
                    rst_cnt <= 0;
                    st <= RST_WAIT;
                end
                default: ;
            endcase

        B_VAL:
            if (rxv) begin
                bus_sel <= rxd[0];
                tx_val  <= "B";
                tx_req  <= 1;
                st      <= IDLE;
            end

        T_BYTE:
            if (rxv) begin
                spi_val <= rxd;
                spi_req <= 1;
                st      <= T_WAIT;
            end

        T_WAIT:
            if (sdone) st <= T_TX;

        T_TX:
            if (!txbusy && !tx_req) begin
                tx_val <= srxd;
                tx_req <= 1;
                st     <= IDLE;
            end

        C_VAL:
            if (rxv) begin
                if (~bus_sel) ch376_kb_cs <= ~rxd[0];
                else          ch376_sd_cs <= ~rxd[0];
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
            if (!ints[1]) begin
                tx_val <= "W";
                tx_req <= 1;
                st     <= IDLE;
            end else if (!wait_active) begin
                tx_val <= "T";
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

    // -----------------------------------------------------------------------
    // Status LEDs + activity stretchers
    // -----------------------------------------------------------------------
    reg [25:0] hb = 0;
    always @(posedge clk_in) hb <= hb + 26'd1;

    localparam integer STRETCH_CYCLES = CLK_HZ / 10;   // ~100 ms
    localparam integer STRETCH_W      = $clog2(STRETCH_CYCLES + 1);
    reg [STRETCH_W-1:0] rx_blip = '0;
    reg [STRETCH_W-1:0] tx_blip = '0;
    reg                 tx_req_prev = 1'b0;

    always @(posedge clk_in) begin
        if (rxv) rx_blip <= STRETCH_CYCLES[STRETCH_W-1:0];
        else if (rx_blip != 0) rx_blip <= rx_blip - 1;

        tx_req_prev <= tx_req;
        if (tx_req & ~tx_req_prev) tx_blip <= STRETCH_CYCLES[STRETCH_W-1:0];
        else if (tx_blip != 0)     tx_blip <= tx_blip - 1;
    end

    assign led_base[0] = hb[25];
    assign led_base[1] = pll_locked;
    assign led_base[2] = ~ch376_kb_cs;
    assign led_base[3] = ~ch376_sd_cs;
    assign led_base[4] = ~ch376_kb_int;
    assign led_base[5] = ~ch376_sd_int;
    assign led_base[6] = (rx_blip != 0);
    assign led_base[7] = (tx_blip != 0);
    // 10CL025: one core LED — show bus_sel solidly when SD selected,
    // and flicker when SPI is busy (XOR with sbusy).
    assign led_core[0] = bus_sel ^ sbusy;

endmodule
