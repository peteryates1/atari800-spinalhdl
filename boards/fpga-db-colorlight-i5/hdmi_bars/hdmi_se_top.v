`timescale 1ns/1ps
// ---------------------------------------------------------------------------
// HDMI colour bars, 640x480p60, PSEUDO-DIFFERENTIAL, for the fpga-db-colorlight-i5
// base board with an i9+ module (XC7A50T-FGG484).
//
// Why not TMDS_33 (hdmi_bars_top.v): the base board AC-couples every lane
// (100 nF, FPGA_DVI_* -> C1..C10 -> connector, no pull-ups). TMDS_33 is a
// current-steering output that only swings when the sink's 50 R terminations
// pull it to 3.3 V over a DC path; through the caps it never toggles. So each
// lane is driven like the ECP5's LVCMOS33D: two LVCMOS33 outputs, one the
// complement of the other. The true stream goes to the CONNECTOR's + ball, so
// the board's P/N swaps (CLK, TX1, TX2) need no inversion here.
//
// 640x480 so that serialisation is plain fabric: a 10-bit shift register at
// 125 MHz (5x pixel) feeding one ODDR per pin (250 Mb/s). A 10:1 OSERDES pair
// would borrow the partner ball's OLOGIC, which the complement output needs.
//
// LED0 = MMCM locked, LED1 toggles once a second (60 frames), LED7 heartbeat.
// ---------------------------------------------------------------------------
module hdmi_se_top (
    input  wire       clk25,          // K4, 25 MHz on the module
    output wire [7:0] led,
    output wire       hdmi_clk_p, hdmi_clk_n,   // connector + / - (not Artix P/N)
    output wire [2:0] hdmi_d_p,   hdmi_d_n
);
    // 25 MHz -> VCO 1000 MHz -> 25 MHz pixel (/40) and 125 MHz bit clock (/8).
    wire clkfb, clkfb_bufg, pix_raw, bit_raw, clk_pix, clk_bit, locked;
    MMCME2_BASE #(
        .CLKIN1_PERIOD    (40.000),
        .DIVCLK_DIVIDE    (1),
        .CLKFBOUT_MULT_F  (40.000),
        .CLKOUT0_DIVIDE_F (40.000),
        .CLKOUT1_DIVIDE   (8)
    ) mmcm (
        .CLKIN1 (clk25), .RST(1'b0), .PWRDWN(1'b0),
        .CLKFBIN(clkfb_bufg), .CLKFBOUT(clkfb),
        .CLKOUT0(pix_raw), .CLKOUT1(bit_raw),
        .CLKOUT0B(), .CLKOUT1B(), .CLKOUT2(), .CLKOUT2B(),
        .CLKOUT3(), .CLKOUT3B(), .CLKOUT4(), .CLKOUT5(), .CLKOUT6(), .CLKFBOUTB(),
        .LOCKED (locked)
    );
    BUFG bufg_fb  (.I(clkfb),   .O(clkfb_bufg));
    BUFG bufg_pix (.I(pix_raw), .O(clk_pix));
    BUFG bufg_bit (.I(bit_raw), .O(clk_bit));

    // 640x480@60 (VESA): 800 x 525, HSync and VSync NEGATIVE.
    localparam H_ACT = 640, H_FP = 16, H_SYNC = 96, H_BP = 48;
    localparam V_ACT = 480, V_FP = 10, V_SYNC = 2,  V_BP = 33;
    localparam H_TOT = H_ACT + H_FP + H_SYNC + H_BP;   // 800
    localparam V_TOT = V_ACT + V_FP + V_SYNC + V_BP;   // 525

    reg [9:0] hc = 0, vc = 0, bx = 0, by = 0;
    reg [6:0] barCnt = 0;       // 0..79 within a bar
    reg [2:0] bar = 0;
    reg [5:0] frames = 0;
    reg       sec = 0;
    wire line_end  = (hc == H_TOT - 1);
    wire frame_end = line_end && (vc == V_TOT - 1);

    always @(posedge clk_pix) begin
        hc <= line_end ? 10'd0 : hc + 10'd1;
        if (line_end) vc <= (vc == V_TOT - 1) ? 10'd0 : vc + 10'd1;
        if (line_end || hc >= H_ACT) begin barCnt <= 0; bar <= 0; end
        else if (barCnt == 7'd79) begin barCnt <= 0; bar <= bar + 3'd1; end
        else barCnt <= barCnt + 7'd1;
        if (frame_end) begin
            bx <= (bx >= H_ACT - 64) ? 10'd0 : bx + 10'd1;
            by <= (by >= V_ACT - 64) ? 10'd0 : by + 10'd1;
            if (frames == 6'd59) begin frames <= 0; sec <= ~sec; end
            else frames <= frames + 6'd1;
        end
    end

    wire de     = (hc < H_ACT) && (vc < V_ACT);
    wire hsync  = ~((hc >= H_ACT + H_FP) && (hc < H_ACT + H_FP + H_SYNC));
    wire vsync  = ~((vc >= V_ACT + V_FP) && (vc < V_ACT + V_FP + V_SYNC));
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
    reg de_r = 0, hs_r = 1, vs_r = 1;
    always @(posedge clk_pix) begin
        rgb_r <= !de ? 24'h0 : (border || box) ? 24'hFFFFFF : bar_rgb;
        de_r <= de; hs_r <= hsync; vs_r <= vsync;
    end

    // TMDS encode (Digilent TMDS_Encoder): lane 0 blue + sync, 1 green, 2 red.
    wire [9:0] sym [0:2];
    TMDS_Encoder enc0 (.PixelClk(clk_pix), .SerialClk(clk_bit), .aRst(~locked),
        .pDataOutRaw(sym[0]), .pDataOut(rgb_r[7:0]),   .pC0(hs_r), .pC1(vs_r), .pVde(de_r));
    TMDS_Encoder enc1 (.PixelClk(clk_pix), .SerialClk(clk_bit), .aRst(~locked),
        .pDataOutRaw(sym[1]), .pDataOut(rgb_r[15:8]),  .pC0(1'b0), .pC1(1'b0), .pVde(de_r));
    TMDS_Encoder enc2 (.PixelClk(clk_pix), .SerialClk(clk_bit), .aRst(~locked),
        .pDataOutRaw(sym[2]), .pDataOut(rgb_r[23:16]), .pC0(1'b0), .pC1(1'b0), .pVde(de_r));

    // 10:1 in fabric. clk_bit is 5x clk_pix and phase-aligned (same MMCM), so
    // the symbols are sampled synchronously; Vivado times the crossing.
    // Lanes 0..2 data, lane 3 the clock pattern. LSB first.
    reg [2:0]  phase = 0;
    reg [9:0]  shreg [0:3];
    reg [39:0] sym_bit = 0;
    integer i;
    always @(posedge clk_bit) begin
        sym_bit <= {10'b0000011111, sym[2], sym[1], sym[0]};
        phase   <= (phase == 3'd4) ? 3'd0 : phase + 3'd1;
        for (i = 0; i < 4; i = i + 1)
            shreg[i] <= (phase == 3'd4) ? sym_bit[i*10 +: 10] : {2'b00, shreg[i][9:2]};
    end

    wire [3:0] pos, neg;
    genvar g;
    generate for (g = 0; g < 4; g = g + 1) begin : lane
        ODDR #(.DDR_CLK_EDGE("SAME_EDGE"), .INIT(1'b0), .SRTYPE("SYNC")) op (
            .Q(pos[g]), .C(clk_bit), .CE(1'b1), .D1(shreg[g][0]), .D2(shreg[g][1]), .R(1'b0), .S(1'b0));
        ODDR #(.DDR_CLK_EDGE("SAME_EDGE"), .INIT(1'b1), .SRTYPE("SYNC")) on (
            .Q(neg[g]), .C(clk_bit), .CE(1'b1), .D1(~shreg[g][0]), .D2(~shreg[g][1]), .R(1'b0), .S(1'b0));
    end endgenerate

    assign hdmi_d_p   = pos[2:0];
    assign hdmi_d_n   = neg[2:0];
    assign hdmi_clk_p = pos[3];
    assign hdmi_clk_n = neg[3];

    reg [24:0] beat = 0;
    always @(posedge clk25) beat <= beat + 25'd1;
    assign led = {beat[24], 5'b0, sec, locked};
endmodule
