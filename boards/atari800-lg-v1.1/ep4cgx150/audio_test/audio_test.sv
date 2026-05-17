// V1.1 audio bring-up.
//
// Drives the two 1-bit audio outputs (audio_l = PIN_C13 / U9.29,
// audio_r = PIN_C12 / U9.31) with distinct square-wave tones so we can hear
// both channels and confirm they aren't swapped. The base-board has an RC
// filter on each pin → headphones / line-level audio jack.
//
//   audio_l  440 Hz  (musical A4)
//   audio_r  880 Hz  (musical A5, one octave up)
//
// To verify left vs right unambiguously, every ~2 seconds we *swap* which
// tone goes where. Listening for the higher-pitch tone alternating between
// ears (assuming the headphone is on the right way around) confirms both
// channels are wired distinctly.
//
// LEDs:
//   led_base[0] heartbeat (~0.75 Hz)
//   led_base[1] slow blink (~0.5 Hz) — proves the swap timer is running
//   led_base[2] current swap state (which tone is on which channel)
//   led_base[3] audio_l output (will look continuously lit at 440 Hz)
//   led_base[4] audio_r output (will look continuously lit at 880 Hz)

module audio_test (
    input  wire        clk_in,          // 50 MHz

    output wire        audio_l,
    output wire        audio_r,

    output wire [7:0]  led_base,
    output wire [1:0]  led_core
);

    localparam integer CLK_HZ   = 50_000_000;
    localparam integer F_LO     = 440;
    localparam integer F_HI     = 880;

    // Half-period (in cycles) — square wave toggles every HALF_LO cycles.
    localparam integer HALF_LO  = CLK_HZ / (2 * F_LO);   // 56818
    localparam integer HALF_HI  = CLK_HZ / (2 * F_HI);   // 28409

    // -----------------------------------------------------------------------
    // Two square-wave generators
    // -----------------------------------------------------------------------
    reg [16:0] cnt_lo = 17'd0;
    reg        sq_lo  = 1'b0;
    always @(posedge clk_in) begin
        if (cnt_lo == HALF_LO - 1) begin
            cnt_lo <= 17'd0;
            sq_lo  <= ~sq_lo;
        end else
            cnt_lo <= cnt_lo + 17'd1;
    end

    reg [15:0] cnt_hi = 16'd0;
    reg        sq_hi  = 1'b0;
    always @(posedge clk_in) begin
        if (cnt_hi == HALF_HI - 1) begin
            cnt_hi <= 16'd0;
            sq_hi  <= ~sq_hi;
        end else
            cnt_hi <= cnt_hi + 16'd1;
    end

    // -----------------------------------------------------------------------
    // Channel swap every ~2 seconds
    // -----------------------------------------------------------------------
    reg [26:0] swap_cnt = 27'd0;
    reg        swap     = 1'b0;
    localparam integer SWAP_PERIOD = CLK_HZ * 2;   // 100M cycles = 2 s
    always @(posedge clk_in) begin
        if (swap_cnt == SWAP_PERIOD - 1) begin
            swap_cnt <= 27'd0;
            swap     <= ~swap;
        end else
            swap_cnt <= swap_cnt + 27'd1;
    end

    assign audio_l = swap ? sq_hi : sq_lo;
    assign audio_r = swap ? sq_lo : sq_hi;

    // -----------------------------------------------------------------------
    // LEDs
    // -----------------------------------------------------------------------
    reg [25:0] hb = 26'd0;
    always @(posedge clk_in) hb <= hb + 26'd1;

    assign led_base[0]   = hb[25];
    assign led_base[1]   = swap_cnt[26];     // ~0.5 Hz blink
    assign led_base[2]   = swap;
    assign led_base[3]   = audio_l;          // looks always-lit at 440 Hz
    assign led_base[4]   = audio_r;          // looks always-lit at 880 Hz
    assign led_base[5]   = 1'b0;
    assign led_base[6]   = 1'b0;
    assign led_base[7]   = 1'b0;
    assign led_core[0]   = sq_lo;
    assign led_core[1]   = sq_hi;

endmodule
