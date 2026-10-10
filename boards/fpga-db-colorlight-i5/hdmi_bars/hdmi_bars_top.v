`timescale 1ns/1ps
// ---------------------------------------------------------------------------
// HDMI colour-bar bring-up for the fpga-db-colorlight-i5 base board with an
// i9+ module (XC7A50T-FGG484). 1280x720p60, the Wukong Phase 0 path (Digilent
// rgb2dvi: TMDS encoder + OSERDESE2 10:1 + internal 5x MMCM) on the base
// board's four bank-34 pairs.
//
// 720p, not 1080p: 1080p's 742.5 MHz serial clock misses the BUFG rating on a
// -1 part (boards/i9plus-6v1/README.md) and the modules' grade is unconfirmed.
//
// Polarity: the HDMI connector's + pins land on the Artix N balls for CLK, TX1
// and TX2 (netlist 2026-10-10: DVI_*_P -> AB1, Y3, AA6, AB8; Vivado: AA1/Y3/Y6/AA8
// are the P balls), so those three lanes are serialised inverted (kInvert).
//
// Picture: 1 px white border (edges/timing), 8 colour bars 160 px wide (all
// three lanes), a white 64x64 box moving 1 px/frame (the output is live).
// LED0 = MMCM locked, LED1 toggles once a second (60 frames), LED7 heartbeat
// straight off the 25 MHz input.
// ---------------------------------------------------------------------------
module hdmi_bars_top (
    input  wire       clk25,                    // K4, 25 MHz on the module
    output wire [7:0] led,                      // base board LED0-7, active high
    output wire       tmds_clk_p, tmds_clk_n,
    output wire [2:0] tmds_d_p,   tmds_d_n
);
    // 25 MHz -> 74.21875 MHz pixel (720p nominal 74.25, -0.04 %).
    //   PFD = 25/2 = 12.5 MHz, VCO = 12.5 * 59.375 = 742.1875 MHz, /10.
    wire clkfb, clkfb_bufg, clk_pix_raw, clk_pix, locked;
    MMCME2_BASE #(
        .CLKIN1_PERIOD    (40.000),
        .DIVCLK_DIVIDE    (2),
        .CLKFBOUT_MULT_F  (59.375),
        .CLKOUT0_DIVIDE_F (10.000)
    ) mmcm (
        .CLKIN1 (clk25), .RST(1'b0), .PWRDWN(1'b0),
        .CLKFBIN(clkfb_bufg), .CLKFBOUT(clkfb),
        .CLKOUT0(clk_pix_raw),
        .CLKOUT0B(), .CLKOUT1(), .CLKOUT1B(), .CLKOUT2(), .CLKOUT2B(),
        .CLKOUT3(), .CLKOUT3B(), .CLKOUT4(), .CLKOUT5(), .CLKOUT6(), .CLKFBOUTB(),
        .LOCKED (locked)
    );
    BUFG bufg_fb  (.I(clkfb),       .O(clkfb_bufg));
    BUFG bufg_pix (.I(clk_pix_raw), .O(clk_pix));

    // CEA-861 720p60: 1650 x 750, HSync and VSync positive.
    localparam H_ACT = 1280, H_FP = 110, H_SYNC = 40, H_BP = 220;
    localparam V_ACT = 720,  V_FP = 5,   V_SYNC = 5,  V_BP = 20;
    localparam H_TOT = H_ACT + H_FP + H_SYNC + H_BP;   // 1650
    localparam V_TOT = V_ACT + V_FP + V_SYNC + V_BP;   // 750

    reg [10:0] hc = 0, bx = 0;
    reg [9:0]  vc = 0, by = 0;
    reg [7:0]  barCnt = 0;      // counts 0..159 within a bar
    reg [2:0]  bar = 0;
    reg [5:0]  frames = 0;
    reg        sec = 0;
    wire line_end  = (hc == H_TOT - 1);
    wire frame_end = line_end && (vc == V_TOT - 1);

    always @(posedge clk_pix) begin
        hc <= line_end ? 11'd0 : hc + 11'd1;
        if (line_end) vc <= (vc == V_TOT - 1) ? 10'd0 : vc + 10'd1;
        // Bar index from a counter, not hc/160.
        if (line_end || hc >= H_ACT) begin barCnt <= 0; bar <= 0; end
        else if (barCnt == 8'd159) begin barCnt <= 0; bar <= bar + 3'd1; end
        else barCnt <= barCnt + 8'd1;
        if (frame_end) begin
            bx <= (bx >= H_ACT - 64) ? 11'd0 : bx + 11'd1;
            by <= (by >= V_ACT - 64) ? 10'd0 : by + 10'd1;
            if (frames == 6'd59) begin frames <= 0; sec <= ~sec; end
            else frames <= frames + 6'd1;
        end
    end

    wire de     = (hc < H_ACT) && (vc < V_ACT);
    wire hsync  = (hc >= H_ACT + H_FP) && (hc < H_ACT + H_FP + H_SYNC);
    wire vsync  = (vc >= V_ACT + V_FP) && (vc < V_ACT + V_FP + V_SYNC);
    wire border = (hc == 0) || (hc == H_ACT - 1) || (vc == 0) || (vc == V_ACT - 1);
    wire box    = (hc >= bx) && (hc < bx + 64) && (vc >= by) && (vc < by + 64);

    reg [23:0] bar_rgb;
    always @* case (bar)
        3'd0: bar_rgb = 24'hFFFFFF;   // white
        3'd1: bar_rgb = 24'hFFFF00;   // yellow
        3'd2: bar_rgb = 24'h00FFFF;   // cyan
        3'd3: bar_rgb = 24'h00FF00;   // green
        3'd4: bar_rgb = 24'hFF00FF;   // magenta
        3'd5: bar_rgb = 24'hFF0000;   // red
        3'd6: bar_rgb = 24'h0000FF;   // blue
        default: bar_rgb = 24'h000000;
    endcase

    reg [23:0] rgb_r = 0;
    reg de_r = 0, hs_r = 0, vs_r = 0;
    always @(posedge clk_pix) begin
        rgb_r <= !de ? 24'h0 : (border || box) ? 24'hFFFFFF : bar_rgb;
        de_r <= de; hs_r <= hsync; vs_r <= vsync;
    end

    rgb2dvi_inv #(
        // kGenerateSerialClk and kRstActiveHigh keep their defaults (true):
        // booleans do not pass cleanly from Verilog. Reset is aRst = !locked.
        .kClkPrimitive      ("MMCM"),
        .kClkRange          (2),          // >= 60 MHz pixel: serial VCO x10
        .kInvert            (4'b1110)     // CLK, TX2, TX1 inverted; TX0 straight
    ) dvi (
        .TMDS_Clk_p (tmds_clk_p), .TMDS_Clk_n (tmds_clk_n),
        .TMDS_Data_p(tmds_d_p),   .TMDS_Data_n(tmds_d_n),
        .aRst       (~locked),    .aRst_n     (1'b1),
        // rgb2dvi packs R[23:16], B[15:8], G[7:0].
        .vid_pData  ({rgb_r[23:16], rgb_r[7:0], rgb_r[15:8]}),
        .vid_pVDE   (de_r), .vid_pHSync(hs_r), .vid_pVSync(vs_r),
        .PixelClk   (clk_pix),    .SerialClk  (1'b0)
    );

    reg [24:0] beat = 0;
    always @(posedge clk25) beat <= beat + 25'd1;
    assign led = {beat[24], 5'b0, sec, locked};
endmodule
