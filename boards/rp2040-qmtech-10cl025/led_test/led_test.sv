// 10CL025 + ATARI-800-QMTechCB-RP2040-STAMP-HDMI-LG — board bring-up test.
//
// Modelled on boards/atari800-lg-v1.1/ep4cgx150/vga_test. This board exposes
// only ONE on-module LED (led_core[0], PIN_N9), so we report board health by
// BLINK RATE rather than by separate status LEDs. The real HDMI atari_pll is
// reused so this exercises the exact clock path the Atari/HDMI design needs:
//
//   fast blink (~6 Hz)     PLL locked   → clk_in AND PLL good (HDMI clocks live)
//   slow blink (~0.75 Hz)  PLL unlocked → clk_in alive at E2, but PLL not locking
//   steady / dark          no clk_in at E2 (or LED pin / polarity issue)
//
// A blinking (edge-toggling) output is used deliberately so the LED's
// active-high vs active-low wiring does not affect the "is it blinking" read.

module led_test (
    input  wire       clk_in,      // PIN_E2  50 MHz QMTech core-board oscillator
    output wire [0:0] led_core     // PIN_N9  on-module user LED
);

    // Raw 50 MHz heartbeat — free-runs regardless of PLL state.
    reg [25:0] hb_raw = 26'd0;
    always @(posedge clk_in) hb_raw <= hb_raw + 26'd1;

    // HDMI PLL (identical module + config to Atari800Rp2040HdmiLgTop):
    //   c1 = 25 MHz pixel clock. VCO = 50 MHz x 15 = 750 MHz.
    wire pclk;
    wire pll_locked;
    atari_pll u_pll (
        .areset (1'b0),
        .inclk0 (clk_in),
        .c0     (),
        .c1     (pclk),
        .c2     (),
        .c3     (),
        .locked (pll_locked)
    );

    // Pixel-clock heartbeat — only meaningful while the PLL is locked.
    reg [21:0] hb_pll = 22'd0;
    always @(posedge pclk) hb_pll <= hb_pll + 22'd1;

    // Locked  → fast blink from the 25 MHz pixel clock (~6 Hz).
    // Unlocked→ slow blink from the raw 50 MHz clock (~0.75 Hz).
    assign led_core[0] = pll_locked ? hb_pll[21] : hb_raw[25];

endmodule
