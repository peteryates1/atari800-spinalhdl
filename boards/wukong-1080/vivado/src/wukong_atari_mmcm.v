// MMCME2_BASE for the Atari core + W9825 SDRAM controller on the Wukong.
//   VCO = 50 / DIVCLK_DIVIDE(1) * CLKFBOUT_MULT_F(18.0) = 900 MHz
//   clk_sys      = VCO / 16 = 56.25 MHz  (Atari system clock; 6502 = sys/32)
//   clk_sdram    = VCO / 8  = 112.5 MHz  (SdramStatemachine controller, exact 2x sys)
//   clk_sdram_ps = VCO / 8  = 112.5 MHz, phase-shifted for the SDRAM chip clock pin.
// Both clk_sys and clk_sdram come from ONE MMCM so every sys<->ctrl crossing in
// SdramStatemachine is a timed path (the LG design's key SDRAM-reliability rule).
// (~56.25 vs the LG's 56.67 MHz = 0.7% slower Atari — irrelevant; the framebuffer
// decouples the Atari's ~50 Hz from the 1080p60 output.)
//
// NB: CLKOUT2_PHASE (SDRAM chip clock vs controller clock) is a board-specific
// tune (LG 10CL025 used ~-2400 ps @115 MHz ~= -97 deg). -90 deg is a starting
// point; may need adjusting on the Wukong if the framebuffer shows corruption.
`timescale 1ps/1ps
module wukong_atari_mmcm (
    input  wire clk_in,        // 50 MHz
    output wire clk_sys,       // 56.25 MHz
    output wire clk_sdram,     // 112.5 MHz (controller)
    output wire clk_sdram_ps,  // 112.5 MHz, phase-shifted (chip pin)
    output wire locked
);
    wire clkfb, clkfb_b, c0, c1, c2, mmcm_locked;

    MMCME2_BASE #(
        .BANDWIDTH        ("OPTIMIZED"),
        .CLKIN1_PERIOD    (20.000),      // 50 MHz
        .DIVCLK_DIVIDE    (1),
        .CLKFBOUT_MULT_F  (18.000),      // VCO = 900 MHz
        .CLKOUT0_DIVIDE_F (16.000),      // 56.25 MHz
        .CLKOUT1_DIVIDE   (8),           // 112.5 MHz
        .CLKOUT2_DIVIDE   (8),           // 112.5 MHz (phase-shifted)
        .CLKOUT2_PHASE    (-90.000),
        .STARTUP_WAIT     ("FALSE")
    ) mmcm_inst (
        .CLKIN1   (clk_in),
        .CLKFBIN  (clkfb_b),
        .CLKFBOUT (clkfb),
        .CLKOUT0  (c0),
        .CLKOUT1  (c1),
        .CLKOUT2  (c2),
        .LOCKED   (mmcm_locked),
        .PWRDWN   (1'b0),
        .RST      (1'b0)
    );

    BUFG b_fb (.I(clkfb), .O(clkfb_b));
    BUFG b_0  (.I(c0),    .O(clk_sys));
    BUFG b_1  (.I(c1),    .O(clk_sdram));
    BUFG b_2  (.I(c2),    .O(clk_sdram_ps));

    assign locked = mmcm_locked;
endmodule
