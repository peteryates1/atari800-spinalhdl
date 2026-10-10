`timescale 1ns/1ps
// ---------------------------------------------------------------------------
// HDMI colour bars, 1280x720p60, PSEUDO-DIFFERENTIAL, fpga-db-colorlight-i5 + i9+.
// Same picture and pin story as hdmi_se_top.v (see there and ../README.md):
// two LVCMOS33 outputs per lane, true + complement, named by CONNECTOR polarity.
//
// Serialising 742 Mb/s per pin: a 10:1 OSERDESE2 is a master/slave pair that
// borrows the partner ball's OLOGIC, and the partner ball carries the
// complement. So every pin gets its own 8:1 DDR OSERDESE2, and a 10->8 gearbox:
//   4 pixels = 40 bits = 5 words of 8.
// Clocks, one MMCM, VCO 742.1875 MHz: pixel /10 = 74.22 MHz, word /8 = 92.77,
// serial /2 = 371.09 (DDR -> 742.19 Mb/s). 4 pixel periods == 5 word periods.
//
// Gearbox CDC (pixel -> word clock): the pixel side packs 4 symbols per lane
// into a 40-bit word and writes it, whole, into one of two buffers, then
// flips `wsel`. The word side syncs `wsel` and, every 5 word clocks, loads the
// buffer last completed. A buffer is rewritten only two groups later, so the
// load always sees a complete word; the two counters share one period, so
// each word is read exactly once. The buffer paths are constrained as a CDC
// (hdmi720_se.xdc), not timed edge-to-edge.
//
// LED0 = MMCM locked, LED1 toggles once a second (60 frames), LED7 heartbeat.
// ---------------------------------------------------------------------------
module hdmi720_se_top (
    input  wire       clk25,
    output wire [7:0] led,
    output wire       hdmi_clk_p, hdmi_clk_n,
    output wire [2:0] hdmi_d_p,   hdmi_d_n
);
    wire clkfb, clkfb_bufg, pix_raw, word_raw, ser_raw;
    wire clk_pix, clk_word, clk_ser, locked;
    MMCME2_BASE #(
        .CLKIN1_PERIOD    (40.000),
        .DIVCLK_DIVIDE    (2),
        .CLKFBOUT_MULT_F  (59.375),       // VCO 742.1875 MHz
        .CLKOUT0_DIVIDE_F (10.000),       // 74.21875 MHz pixel
        .CLKOUT1_DIVIDE   (8),            // 92.77 MHz word
        .CLKOUT2_DIVIDE   (2)             // 371.09 MHz serial (DDR)
    ) mmcm (
        .CLKIN1 (clk25), .RST(1'b0), .PWRDWN(1'b0),
        .CLKFBIN(clkfb_bufg), .CLKFBOUT(clkfb),
        .CLKOUT0(pix_raw), .CLKOUT1(word_raw), .CLKOUT2(ser_raw),
        .CLKOUT0B(), .CLKOUT1B(), .CLKOUT2B(),
        .CLKOUT3(), .CLKOUT3B(), .CLKOUT4(), .CLKOUT5(), .CLKOUT6(), .CLKFBOUTB(),
        .LOCKED (locked)
    );
    BUFG bufg_fb   (.I(clkfb),    .O(clkfb_bufg));
    BUFG bufg_pix  (.I(pix_raw),  .O(clk_pix));
    BUFG bufg_word (.I(word_raw), .O(clk_word));
    BUFG bufg_ser  (.I(ser_raw),  .O(clk_ser));

    // ---- picture: CEA-861 720p60, 1650 x 750, HSync and VSync positive ----
    localparam H_ACT = 1280, H_FP = 110, H_SYNC = 40, H_BP = 220;
    localparam V_ACT = 720,  V_FP = 5,   V_SYNC = 5,  V_BP = 20;
    localparam H_TOT = H_ACT + H_FP + H_SYNC + H_BP;
    localparam V_TOT = V_ACT + V_FP + V_SYNC + V_BP;

    reg [10:0] hc = 0, bx = 0;
    reg [9:0]  vc = 0, by = 0;
    reg [7:0]  barCnt = 0;
    reg [2:0]  bar = 0;
    reg [5:0]  frames = 0;
    reg        sec = 0;
    wire line_end  = (hc == H_TOT - 1);
    wire frame_end = line_end && (vc == V_TOT - 1);

    always @(posedge clk_pix) begin
        hc <= line_end ? 11'd0 : hc + 11'd1;
        if (line_end) vc <= (vc == V_TOT - 1) ? 10'd0 : vc + 10'd1;
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

    // ---- TMDS encode: lane 0 blue + sync, 1 green, 2 red; lane 3 = clock ----
    wire [9:0] sym0, sym1, sym2;
    TMDS_Encoder enc0 (.PixelClk(clk_pix), .SerialClk(clk_ser), .aRst(~locked),
        .pDataOutRaw(sym0), .pDataOut(rgb_r[7:0]),   .pC0(hs_r), .pC1(vs_r), .pVde(de_r));
    TMDS_Encoder enc1 (.PixelClk(clk_pix), .SerialClk(clk_ser), .aRst(~locked),
        .pDataOutRaw(sym1), .pDataOut(rgb_r[15:8]),  .pC0(1'b0), .pC1(1'b0), .pVde(de_r));
    TMDS_Encoder enc2 (.PixelClk(clk_pix), .SerialClk(clk_ser), .aRst(~locked),
        .pDataOutRaw(sym2), .pDataOut(rgb_r[23:16]), .pC0(1'b0), .pC1(1'b0), .pVde(de_r));
    wire [39:0] syms = {10'b0000011111, sym2, sym1, sym0};

    // ---- gearbox, pixel side: pack 4 symbols per lane, write whole ----
    reg [1:0]   pcnt = 0;
    reg [159:0] pack = 0;            // lane L, pixel k at [L*40 + k*10 +: 10]
    reg [159:0] gbuf0 = 0, gbuf1 = 0;
    reg         wsel = 0;
    integer L;
    always @(posedge clk_pix) begin
        pcnt <= pcnt + 2'd1;
        for (L = 0; L < 4; L = L + 1)
            pack[L*40 + pcnt*10 +: 10] <= syms[L*10 +: 10];
        if (pcnt == 2'd0 && locked) begin
            // pack holds the 4 symbols of the previous group (pcnt 0..3 just written)
            if (wsel) gbuf1 <= pack; else gbuf0 <= pack;
            wsel <= ~wsel;
        end
    end

    // ---- gearbox, word side ----
    (* ASYNC_REG = "TRUE" *) reg ws1 = 0, ws2 = 0;
    reg [2:0]   wcnt = 0;
    reg [159:0] wsh = 0;
    reg [7:0]   wrst = 8'hFF;
    always @(posedge clk_word) begin
        ws1 <= wsel; ws2 <= ws1;
        wrst <= locked ? {wrst[6:0], 1'b0} : 8'hFF;
        wcnt <= (wcnt == 3'd4) ? 3'd0 : wcnt + 3'd1;
        if (wcnt == 3'd4)
            wsh <= ws2 ? gbuf0 : gbuf1;            // the buffer NOT being written next
        else
            for (L = 0; L < 4; L = L + 1)
                wsh[L*40 +: 40] <= {8'b0, wsh[L*40 + 8 +: 32]};
    end

    // ---- 8:1 DDR OSERDESE2 per pin: true on P (connector +), complement on N ----
    wire [3:0] pos, neg;
    genvar g;
    generate for (g = 0; g < 4; g = g + 1) begin : lane
        wire [7:0] w = wsh[g*40 +: 8];
        OSERDESE2 #(.DATA_RATE_OQ("DDR"), .DATA_RATE_TQ("SDR"), .DATA_WIDTH(8),
                    .SERDES_MODE("MASTER"), .TRISTATE_WIDTH(1), .INIT_OQ(1'b0)) sp (
            .OQ(pos[g]), .CLK(clk_ser), .CLKDIV(clk_word), .RST(wrst[7]), .OCE(1'b1),
            .D1(w[0]), .D2(w[1]), .D3(w[2]), .D4(w[3]), .D5(w[4]), .D6(w[5]), .D7(w[6]), .D8(w[7]),
            .TCE(1'b0), .T1(1'b0), .T2(1'b0), .T3(1'b0), .T4(1'b0), .TBYTEIN(1'b0),
            .SHIFTIN1(1'b0), .SHIFTIN2(1'b0), .OFB(), .TFB(), .TQ(), .TBYTEOUT(),
            .SHIFTOUT1(), .SHIFTOUT2());
        OSERDESE2 #(.DATA_RATE_OQ("DDR"), .DATA_RATE_TQ("SDR"), .DATA_WIDTH(8),
                    .SERDES_MODE("MASTER"), .TRISTATE_WIDTH(1), .INIT_OQ(1'b1)) sn (
            .OQ(neg[g]), .CLK(clk_ser), .CLKDIV(clk_word), .RST(wrst[7]), .OCE(1'b1),
            .D1(~w[0]), .D2(~w[1]), .D3(~w[2]), .D4(~w[3]), .D5(~w[4]), .D6(~w[5]), .D7(~w[6]), .D8(~w[7]),
            .TCE(1'b0), .T1(1'b0), .T2(1'b0), .T3(1'b0), .T4(1'b0), .TBYTEIN(1'b0),
            .SHIFTIN1(1'b0), .SHIFTIN2(1'b0), .OFB(), .TFB(), .TQ(), .TBYTEOUT(),
            .SHIFTOUT1(), .SHIFTOUT2());
    end endgenerate

    assign hdmi_d_p   = pos[2:0];
    assign hdmi_d_n   = neg[2:0];
    assign hdmi_clk_p = pos[3];
    assign hdmi_clk_n = neg[3];

    reg [24:0] beat = 0;
    always @(posedge clk25) beat <= beat + 25'd1;
    assign led = {beat[24], 5'b0, sec, locked};
endmodule
