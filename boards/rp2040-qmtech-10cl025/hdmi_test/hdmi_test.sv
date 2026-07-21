// 10CL025 + ATARI-800-QMTechCB-RP2040-STAMP-HDMI-LG — minimal HDMI bring-up.
//
// The analogue of boards/atari800-lg-v1.1/ep4cgx150/vga_test, but for THIS
// board's HDMI output. Bypasses the entire Atari core / scandoubler / SDRAM:
// generates 640x480@60 timing directly and feeds 8 vertical colour bars into
// the SAME DvidOut TMDS path the full design uses (dvid.sv, extracted from the
// generated Atari800Rp2040HdmiLgTop.sv).
//
//   * If this shows bars  → HDMI output path (pins/levels/DDR/DvidOut) is good,
//                           and the full design's blank screen is upstream
//                           (Atari core / video data / SDRAM).
//   * If this is blank too → fault is in the HDMI path itself.
//
// Clocks come from atari_pll: c1 = 25 MHz pixel, c2 = 125 MHz TMDS (10:1 DDR).
// led_core[0] = raw-clock heartbeat so we always have a "design alive" sign.

module hdmi_test (
    input  wire       clk_in,       // PIN_E2  50 MHz
    output wire       hdmi_clk_p,
    output wire       hdmi_clk_n,
    output wire       hdmi_d0_p,
    output wire       hdmi_d0_n,
    output wire       hdmi_d1_p,
    output wire       hdmi_d1_n,
    output wire       hdmi_d2_p,
    output wire       hdmi_d2_n,
    output wire [0:0] led_core       // PIN_N9
);

    // ----- Clocks -----
    wire pclk;          // 25 MHz pixel
    wire tclk;          // 125 MHz TMDS
    wire pll_locked;
    atari_pll u_pll (
        .areset (1'b0),
        .inclk0 (clk_in),
        .c0     (),
        .c1     (pclk),
        .c2     (tclk),
        .c3     (),
        .locked (pll_locked)
    );

    // ----- 640x480 @ 60 Hz timing (negative sync polarity), 25 MHz -----
    localparam H_VIS = 10'd640, H_FP = 10'd16, H_SYNC = 10'd96, H_BP = 10'd48;
    localparam V_VIS = 10'd480, V_FP = 10'd10, V_SYNC = 10'd2,  V_BP = 10'd33;
    localparam H_TOTAL = H_VIS + H_FP + H_SYNC + H_BP;   // 800
    localparam V_TOTAL = V_VIS + V_FP + V_SYNC + V_BP;   // 525

    reg [9:0] hcnt = 10'd0;
    reg [9:0] vcnt = 10'd0;
    always @(posedge pclk) begin
        if (hcnt == H_TOTAL - 1) begin
            hcnt <= 10'd0;
            vcnt <= (vcnt == V_TOTAL - 1) ? 10'd0 : vcnt + 10'd1;
        end else
            hcnt <= hcnt + 10'd1;
    end

    wire visible     = (hcnt < H_VIS) && (vcnt < V_VIS);
    // Active-high sync pulses fed to the TMDS control tokens.
    wire hsync_pulse = (hcnt >= H_VIS + H_FP) && (hcnt < H_VIS + H_FP + H_SYNC);
    wire vsync_pulse = (vcnt >= V_VIS + V_FP) && (vcnt < V_VIS + V_FP + V_SYNC);

    // ----- 8 vertical colour bars (80 px each) in 8-bit RGB -----
    reg [2:0] bar;
    always @(*) begin
        if      (hcnt < 10'd80)  bar = 3'd0;
        else if (hcnt < 10'd160) bar = 3'd1;
        else if (hcnt < 10'd240) bar = 3'd2;
        else if (hcnt < 10'd320) bar = 3'd3;
        else if (hcnt < 10'd400) bar = 3'd4;
        else if (hcnt < 10'd480) bar = 3'd5;
        else if (hcnt < 10'd560) bar = 3'd6;
        else                     bar = 3'd7;
    end

    reg [7:0] r, g, b;
    always @(*) begin
        case (bar)                          //          R    G    B
        3'd0: begin r=8'hFF; g=8'hFF; b=8'hFF; end   // white
        3'd1: begin r=8'hFF; g=8'hFF; b=8'h00; end   // yellow
        3'd2: begin r=8'h00; g=8'hFF; b=8'hFF; end   // cyan
        3'd3: begin r=8'h00; g=8'hFF; b=8'h00; end   // green
        3'd4: begin r=8'hFF; g=8'h00; b=8'hFF; end   // magenta
        3'd5: begin r=8'hFF; g=8'h00; b=8'h00; end   // red
        3'd6: begin r=8'h00; g=8'h00; b=8'hFF; end   // blue
        default: begin r=8'h00; g=8'h00; b=8'h00; end// black
        endcase
    end

    // Register into the pixel domain, aligned with DE, before TMDS encode.
    reg [7:0] r_q, g_q, b_q;
    reg       de_q, hs_q, vs_q;
    always @(posedge pclk) begin
        de_q <= visible;
        hs_q <= hsync_pulse;
        vs_q <= vsync_pulse;
        r_q  <= visible ? r : 8'h00;
        g_q  <= visible ? g : 8'h00;
        b_q  <= visible ? b : 8'h00;
    end

    // ----- TMDS output (same module as the full design) -----
    DvidOut u_dvi (
        .io_clkPixel (pclk),
        .io_clkTmds  (tclk),
        .io_red      (r_q),
        .io_green    (g_q),
        .io_blue     (b_q),
        .io_hsync    (hs_q),
        .io_vsync    (vs_q),
        .io_de       (de_q),
        .io_tmdsD0P  (hdmi_d0_p),
        .io_tmdsD0N  (hdmi_d0_n),
        .io_tmdsD1P  (hdmi_d1_p),
        .io_tmdsD1N  (hdmi_d1_n),
        .io_tmdsD2P  (hdmi_d2_p),
        .io_tmdsD2N  (hdmi_d2_n),
        .io_tmdsClkP (hdmi_clk_p),
        .io_tmdsClkN (hdmi_clk_n)
    );

    // ----- Heartbeat (raw clock) so the LED always shows the design is alive -----
    reg [25:0] hb = 26'd0;
    always @(posedge clk_in) hb <= hb + 26'd1;
    assign led_core[0] = hb[25];

endmodule
