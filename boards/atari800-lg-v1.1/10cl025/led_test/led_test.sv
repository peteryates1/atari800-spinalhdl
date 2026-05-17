// V1.1 + QMTECH 10CL025 (Cyclone 10 LP) bring-up smoke test.
// Drives a rotating single-lit-LED pattern on the 8 base-board LEDs at ~3 Hz,
// and toggles the single core-board LED at ~1.5 Hz so we can confirm both
// the base PCB and the core board are alive.

module led_test (
    input  wire       clk_in,       // PIN_E2  50 MHz on 10CL025 QMTECH core
    output wire [7:0] led_base,     // active-high, base board D1..D8
    output wire [0:0] led_core      // active-high, core board single LED (10CL025 has only 1)
);

    reg [25:0] cnt = 26'd0;
    always @(posedge clk_in) cnt <= cnt + 26'd1;

    wire [2:0] sel = cnt[25:23];
    assign led_base = 8'h01 << sel;

    assign led_core = cnt[24];

endmodule
