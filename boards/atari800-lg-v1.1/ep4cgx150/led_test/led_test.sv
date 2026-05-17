// V1.1 base-board bring-up smoke test.
// Drives a rotating pattern on the 8 base-board LEDs (U9.53-60) and the
// 2 QMTECH EP4CGX150 core-board LEDs. Pure RTL, no PLL, no IP — just a
// 26-bit counter off the 50 MHz onboard oscillator.
//
// Base-board LEDs walk a single lit LED left-to-right at ~3 Hz.
// Core-board LEDs alternate at ~1.5 Hz (the two MSBs of the same divider).

module led_test (
    input  wire       clk_in,       // PIN_B14  50 MHz
    output wire [7:0] led_base,     // active-high, base board D1..D8
    output wire [1:0] led_core      // active-high, core board
);

    reg [25:0] cnt = 26'd0;
    always @(posedge clk_in) cnt <= cnt + 26'd1;

    // ~3 Hz step: top 3 bits of cnt select which of 8 LEDs is lit.
    wire [2:0] sel = cnt[25:23];
    assign led_base = (8'h01) << sel;

    // ~1.5 Hz toggle, 180 deg apart, so a human can tell both LEDs are alive.
    assign led_core[0] = cnt[24];
    assign led_core[1] = ~cnt[24];

endmodule
