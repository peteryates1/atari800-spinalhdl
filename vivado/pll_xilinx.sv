// Xilinx Artix-7 PLL: 25 MHz -> 56.67 MHz (Atari system clock)
// Replaces PllAtari800 (Altera) for Colorlight i9+ (XC7A50T)
// MMCME2_BASE: CLKIN=25MHz, VCO=25*45=1125MHz, CLKOUT0=1125/20=56.25MHz (close to 56.67)
module PllAtari800 (
    input  wire inclk0,
    output wire c0,
    output wire c1,
    output wire c2,
    output wire locked
);

    wire clkfb, clkout0, clkout1, clkout2;

    MMCME2_BASE #(
        .CLKIN1_PERIOD   (40.0),     // 25 MHz = 40ns
        .CLKFBOUT_MULT_F (45.0),     // VCO = 25 * 45 = 1125 MHz
        .CLKOUT0_DIVIDE_F(20.0),     // 1125 / 20 = 56.25 MHz (system)
        .CLKOUT1_DIVIDE  (20),       // 56.25 MHz (SDRAM, phase shifted)
        .CLKOUT1_PHASE   (135.0),    // ~135 degree phase shift for SDRAM
        .CLKOUT2_DIVIDE  (45),       // 1125 / 45 = 25 MHz (VGA text)
        .DIVCLK_DIVIDE   (1)
    ) mmcm_inst (
        .CLKIN1    (inclk0),
        .RST       (1'b0),
        .PWRDWN    (1'b0),
        .CLKFBIN   (clkfb),
        .CLKFBOUT  (clkfb),
        .CLKOUT0   (clkout0),
        .CLKOUT1   (clkout1),
        .CLKOUT2   (clkout2),
        .LOCKED    (locked)
    );

    BUFG bufg_c0 (.I(clkout0), .O(c0));
    BUFG bufg_c1 (.I(clkout1), .O(c1));
    BUFG bufg_c2 (.I(clkout2), .O(c2));

endmodule
