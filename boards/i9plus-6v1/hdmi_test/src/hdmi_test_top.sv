`timescale 1ns/1ps
// ---------------------------------------------------------------------------
// 1080p60 HDMI serializer timing probe for the Colorlight i9+ (XC7A50T-FGG484).
//
// Instantiates the SAME Digilent rgb2dvi (OSERDESE2 10:1 + internal 5x MMCM) we
// run on the Wukong, but on the i9+'s bank-34 HDMI balls, and clocked for
// 1080p60 (148.5 MHz pixel -> 742.5 MHz serial). Push it through place+route at a
// given speed grade and read the WNS on the 742.5 MHz serial clock: that is the
// real answer to "does this part/pin path close 1080p60?" (vs. the ~680 MHz claim).
// The pixel-domain pattern is a throwaway counter — only the clocking + OSERDES
// paths matter for the timing question.
// ---------------------------------------------------------------------------
module hdmi_test_top (
    input  wire       clk25,                    // K4, 25 MHz on-module oscillator
    output wire       tmds_clk_p, tmds_clk_n,   // bank 34
    output wire [2:0] tmds_d_p,   tmds_d_n
);
    // 25 MHz -> 148.5 MHz pixel.  VCO = 25 * 29.7 = 742.5 MHz; /5 = 148.5 MHz.
    wire clkfb, clk_pix_raw, clk_pix, locked;
    MMCME2_BASE #(
        .CLKIN1_PERIOD    (40.000),   // 25 MHz
        .CLKFBOUT_MULT_F  (29.700),   // VCO = 742.5 MHz
        .DIVCLK_DIVIDE    (1),
        .CLKOUT0_DIVIDE_F (5.000)     // 742.5 / 5 = 148.5 MHz pixel
    ) mmcm (
        .CLKIN1   (clk25), .RST(1'b0), .PWRDWN(1'b0),
        .CLKFBIN  (clkfb), .CLKFBOUT(clkfb),
        .CLKOUT0  (clk_pix_raw),
        .CLKOUT1  (), .CLKOUT2(), .CLKOUT3(), .CLKOUT4(), .CLKOUT5(), .CLKOUT6(),
        .LOCKED   (locked)
    );
    BUFG bufg_pix (.I(clk_pix_raw), .O(clk_pix));

    // Throwaway pixel-domain pattern so the TMDS encoders + OSERDES are exercised.
    reg [23:0] cnt = 24'd0;
    always @(posedge clk_pix) cnt <= cnt + 24'd1;

    rgb2dvi_wrapper u_dvi (
        .PixelClk    (clk_pix),
        .aRst_n      (locked),
        .vid_pData   (cnt),
        .vid_pVDE    (~cnt[11]),
        .vid_pHSync  (cnt[10]),
        .vid_pVSync  (cnt[20]),
        .TMDS_Clk_p  (tmds_clk_p),  .TMDS_Clk_n  (tmds_clk_n),
        .TMDS_Data_p (tmds_d_p),    .TMDS_Data_n (tmds_d_n)
    );
endmodule
