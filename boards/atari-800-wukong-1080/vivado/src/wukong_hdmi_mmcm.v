// MMCME2_BASE: 50 MHz -> 148.4375 MHz pixel clock for 1080p60 on the Wukong.
//   VCO = 50 / DIVCLK_DIVIDE(4) * CLKFBOUT_MULT_F(59.375) = 742.1875 MHz
//   CLKOUT0 = VCO / CLKOUT0_DIVIDE_F(5.0) = 148.4375 MHz
// (rgb2dvi generates the 5x = 742.1875 MHz TMDS serial clock internally.)
`timescale 1ps/1ps
module wukong_hdmi_mmcm (
    input  wire clk_in,
    output wire clk_pix,
    output wire locked
);
    wire clkfb, clkfb_bufg, clkout0, mmcm_locked;

    MMCME2_BASE #(
        .BANDWIDTH        ("OPTIMIZED"),
        .CLKIN1_PERIOD    (20.000),      // 50 MHz
        .DIVCLK_DIVIDE    (4),
        .CLKFBOUT_MULT_F  (59.375),
        .CLKFBOUT_PHASE   (0.000),
        .CLKOUT0_DIVIDE_F (5.000),       // 148.4375 MHz
        .CLKOUT0_DUTY_CYCLE(0.500),
        .CLKOUT0_PHASE    (0.000),
        .STARTUP_WAIT     ("FALSE")
    ) mmcm_inst (
        .CLKIN1   (clk_in),
        .CLKFBIN  (clkfb_bufg),
        .CLKFBOUT (clkfb),
        .CLKOUT0  (clkout0),
        .LOCKED   (mmcm_locked),
        .PWRDWN   (1'b0),
        .RST      (1'b0)
    );

    BUFG bufg_fb  (.I(clkfb),   .O(clkfb_bufg));
    BUFG bufg_pix (.I(clkout0), .O(clk_pix));

    assign locked = mmcm_locked;
endmodule
