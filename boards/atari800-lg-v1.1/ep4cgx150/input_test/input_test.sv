// V1.1 console-switch + joystick bring-up.
//
// All inputs are active-low (with internal pull-ups, per v1_1_pins.tcl).
// 2-FF synchronise, invert to active-high, mirror to LEDs, and emit 2 bytes
// every ~50 ms over the CH340 UART (115200 8N1) so a host script can show
// live state.
//
// Packet:
//   byte 0: [rs op se st  j1t j1r j1l j1d]   (MSB first)
//   byte 1: [j1u j2t j2r j2l  j2d j2u . .]
//
// LEDs:
//   led_base[0] heartbeat (hb[25])
//   led_base[1] sw_start
//   led_base[2] sw_select
//   led_base[3] sw_option
//   led_base[4] sw_reset
//   led_base[5] any JS1 direction
//   led_base[6] any JS2 direction
//   led_base[7] any trigger
//   led_core[0] JS1 trigger
//   led_core[1] JS2 trigger

module input_test (
    input  wire        clk_in,

    output wire        uart_tx,
    input  wire        uart_rx,

    input  wire        sw_start,
    input  wire        sw_select,
    input  wire        sw_option,
    input  wire        sw_reset,

    input  wire        js1_up,
    input  wire        js1_down,
    input  wire        js1_left,
    input  wire        js1_right,
    input  wire        js1_trig,

    input  wire        js2_up,
    input  wire        js2_down,
    input  wire        js2_left,
    input  wire        js2_right,
    input  wire        js2_trig,

    output wire [7:0]  led_base,
    output wire [1:0]  led_core
);

    // Tie uart_rx into a NOR gate so Quartus keeps the pin alive (otherwise
    // it's an unused input and the IO standard/pull-up assignment may be
    // dropped). Result is unused.
    wire _rx_keep = uart_rx;

    // -----------------------------------------------------------------------
    // Synchronise raw inputs (active-low). After invert, '1' means pressed.
    // -----------------------------------------------------------------------
    reg [13:0] sync1 = 14'd0;
    reg [13:0] sync2 = 14'd0;

    wire [13:0] raw = {
        js2_trig, js2_right, js2_left, js2_down, js2_up,   // [13:9]
        js1_trig, js1_right, js1_left, js1_down, js1_up,   // [8:4]
        sw_reset, sw_option, sw_select, sw_start           // [3:0]
    };

    always @(posedge clk_in) begin
        sync1 <= ~raw;
        sync2 <= sync1;
    end

    wire st  = sync2[0];
    wire se  = sync2[1];
    wire op  = sync2[2];
    wire rs  = sync2[3];
    wire j1u = sync2[4];
    wire j1d = sync2[5];
    wire j1l = sync2[6];
    wire j1r = sync2[7];
    wire j1t = sync2[8];
    wire j2u = sync2[9];
    wire j2d = sync2[10];
    wire j2l = sync2[11];
    wire j2r = sync2[12];
    wire j2t = sync2[13];

    // -----------------------------------------------------------------------
    // Heartbeat / LEDs
    // -----------------------------------------------------------------------
    reg [25:0] hb = 26'd0;
    always @(posedge clk_in) hb <= hb + 26'd1;

    assign led_base[0] = hb[25];
    assign led_base[1] = st;
    assign led_base[2] = se;
    assign led_base[3] = op;
    assign led_base[4] = rs;
    assign led_base[5] = j1u | j1d | j1l | j1r;
    assign led_base[6] = j2u | j2d | j2l | j2r;
    assign led_base[7] = j1t | j2t;
    assign led_core[0] = j1t;
    assign led_core[1] = j2t;

    // -----------------------------------------------------------------------
    // Simple UART TX core. 115200 8N1 from 50 MHz.
    // Driver: when send_pulse fires, latch byte0 and a "send byte1" flag.
    // The TX FSM sends byte 0, then byte 1, then waits for the next pulse.
    // -----------------------------------------------------------------------
    localparam integer CLK_HZ   = 50_000_000;
    localparam integer BAUD     = 115_200;
    localparam integer BIT_T    = CLK_HZ / BAUD;       // 434 cycles/bit
    localparam integer PULSE_T  = CLK_HZ / 20;         // ~50 ms (2_500_000)

    // Periodic pulse — fires every PULSE_T cycles. Use hb[20] as a simple
    // ~20 Hz timer (50e6 / 2^20 ≈ 47.7 Hz; close enough).
    reg hb20_prev = 1'b0;
    wire send_pulse = (hb[20] & ~hb20_prev);
    always @(posedge clk_in) hb20_prev <= hb[20];

    // TX shift register
    reg [9:0]  txsr   = 10'h3FF;
    reg [3:0]  txbit  = 4'd0;
    reg [9:0]  txc    = 10'd0;
    reg        txbusy = 1'b0;
    assign uart_tx = txsr[0];

    // Higher-level: send 2-byte packet on each pulse.
    reg [1:0] tx_state = 2'd0;
    reg [7:0] byte0_r  = 8'd0;
    reg [7:0] byte1_r  = 8'd0;

    always @(posedge clk_in) begin
        // TX bit engine
        if (txbusy) begin
            if (txc == 10'd0) begin
                txc  <= BIT_T - 1;
                txsr <= {1'b1, txsr[9:1]};
                if (txbit == 4'd9) txbusy <= 1'b0;
                else               txbit  <= txbit + 4'd1;
            end else begin
                txc <= txc - 10'd1;
            end
        end

        case (tx_state)
        2'd0: if (send_pulse) begin
            // Snapshot inputs into packet regs
            byte0_r  <= {rs, op, se, st, j1t, j1r, j1l, j1d};
            byte1_r  <= {j1u, j2t, j2r, j2l, j2d, j2u, 2'd0};
            // Kick off byte 0
            txsr     <= {1'b1, rs, op, se, st, j1t, j1r, j1l, j1d, 1'b0};
            txbusy   <= 1'b1;
            txbit    <= 4'd0;
            txc      <= BIT_T - 1;
            tx_state <= 2'd1;
        end
        2'd1: if (!txbusy) begin
            // Byte 0 done — kick off byte 1
            txsr     <= {1'b1, byte1_r, 1'b0};
            txbusy   <= 1'b1;
            txbit    <= 4'd0;
            txc      <= BIT_T - 1;
            tx_state <= 2'd2;
        end
        2'd2: if (!txbusy) begin
            // Byte 1 done — wait for next pulse
            tx_state <= 2'd0;
        end
        default: tx_state <= 2'd0;
        endcase
    end

endmodule
