// V1.1 VGA bring-up.
//
// 640x480 @ ~60 Hz from 25 MHz pixel clock (PLL: 50 / 2 = 25 MHz, vs the
// standard 25.175 MHz — refresh comes out about 59.5 Hz, well inside any
// monitor's tolerance). 5-bit per channel resistor DAC drives the 15 RGB
// pins on V1.1's U9 connector.
//
// Pattern: 8 vertical SMPTE-ish colour bars across the visible width, with a
// horizontal grey ramp along the bottom 32 lines so we can spot bit-stuck
// patterns on any colour channel quickly.
//
// LEDs:
//   led_base[0] heartbeat
//   led_base[1] pll_locked
//   led_base[2] vsync (pulses ~60 Hz)
//   led_base[3] hsync (rapid → looks lit)
//   led_base[4] visible-region active
//   led_base[5..7] high 3 bits of the colour bar selector

module vga_test (
    input  wire        clk_in,

    output reg         vga_hs,
    output reg         vga_vs,
    output reg  [4:0]  vga_r,
    output reg  [4:0]  vga_g,
    output reg  [4:0]  vga_b,

    output wire [7:0]  led_base,
    output wire [0:0]  led_core           // 10CL025: one core LED
);

    // -----------------------------------------------------------------------
    // 25 MHz pixel clock
    // -----------------------------------------------------------------------
    wire pclk;
    wire pll_locked;
    vga_pll u_pll (
        .areset (1'b0),
        .inclk0 (clk_in),
        .c0     (pclk),
        .locked (pll_locked)
    );

    // -----------------------------------------------------------------------
    // 640x480 @ 60 Hz timing (negative-polarity HS and VS)
    //   H: 640 visible + 16 fp + 96 sync + 48 bp = 800 total
    //   V: 480 visible + 10 fp +  2 sync + 33 bp = 525 total
    // -----------------------------------------------------------------------
    localparam H_VIS    = 10'd640;
    localparam H_FP     = 10'd16;
    localparam H_SYNC   = 10'd96;
    localparam H_BP     = 10'd48;
    localparam H_TOTAL  = H_VIS + H_FP + H_SYNC + H_BP;     // 800

    localparam V_VIS    = 10'd480;
    localparam V_FP     = 10'd10;
    localparam V_SYNC   = 10'd2;
    localparam V_BP     = 10'd33;
    localparam V_TOTAL  = V_VIS + V_FP + V_SYNC + V_BP;     // 525

    reg [9:0] hcnt = 10'd0;
    reg [9:0] vcnt = 10'd0;

    always @(posedge pclk) begin
        if (hcnt == H_TOTAL - 1) begin
            hcnt <= 10'd0;
            if (vcnt == V_TOTAL - 1)
                vcnt <= 10'd0;
            else
                vcnt <= vcnt + 10'd1;
        end else
            hcnt <= hcnt + 10'd1;
    end

    wire visible   = (hcnt < H_VIS) && (vcnt < V_VIS);
    wire hsync_lo  = (hcnt >= H_VIS + H_FP) && (hcnt < H_VIS + H_FP + H_SYNC);
    wire vsync_lo  = (vcnt >= V_VIS + V_FP) && (vcnt < V_VIS + V_FP + V_SYNC);

    // -----------------------------------------------------------------------
    // Pattern generator: 8 vertical colour bars across 640 px → 80 px/bar.
    //   bar 0  white   (R=G=B=31)
    //   bar 1  yellow  (R=31 G=31 B=0)
    //   bar 2  cyan    (R=0  G=31 B=31)
    //   bar 3  green   (R=0  G=31 B=0)
    //   bar 4  magenta (R=31 G=0  B=31)
    //   bar 5  red     (R=31 G=0  B=0)
    //   bar 6  blue    (R=0  G=0  B=31)
    //   bar 7  black   (R=G=B=0)
    //
    // Bottom 32 lines: horizontal greyscale ramp (8 step intensities).
    // -----------------------------------------------------------------------
    // 8 bars of 80 px each. 80 isn't a power of 2 so we use a compare chain
    // instead of hcnt[9:7] (which would give 128 px bars = only 5 fit in 640).
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

    reg [4:0] r_bar, g_bar, b_bar;
    always @(*) begin
        case (bar)
        3'd0: begin r_bar = 5'd31; g_bar = 5'd31; b_bar = 5'd31; end
        3'd1: begin r_bar = 5'd31; g_bar = 5'd31; b_bar = 5'd0;  end
        3'd2: begin r_bar = 5'd0;  g_bar = 5'd31; b_bar = 5'd31; end
        3'd3: begin r_bar = 5'd0;  g_bar = 5'd31; b_bar = 5'd0;  end
        3'd4: begin r_bar = 5'd31; g_bar = 5'd0;  b_bar = 5'd31; end
        3'd5: begin r_bar = 5'd31; g_bar = 5'd0;  b_bar = 5'd0;  end
        3'd6: begin r_bar = 5'd0;  g_bar = 5'd0;  b_bar = 5'd31; end
        default: begin r_bar = 5'd0; g_bar = 5'd0; b_bar = 5'd0; end
        endcase
    end

    wire ramp_zone = (vcnt >= V_VIS - 10'd32) && (vcnt < V_VIS);
    // 8 intensity steps: 0,4,9,13,17,22,26,31 across 5-bit (0..31) range.
    reg [4:0] ramp_level;
    always @(*) begin
        case (bar)
        3'd0:    ramp_level = 5'd0;
        3'd1:    ramp_level = 5'd4;
        3'd2:    ramp_level = 5'd9;
        3'd3:    ramp_level = 5'd13;
        3'd4:    ramp_level = 5'd17;
        3'd5:    ramp_level = 5'd22;
        3'd6:    ramp_level = 5'd26;
        default: ramp_level = 5'd31;
        endcase
    end

    always @(posedge pclk) begin
        vga_hs <= ~hsync_lo;
        vga_vs <= ~vsync_lo;
        if (visible) begin
            if (ramp_zone) begin
                vga_r <= ramp_level;
                vga_g <= ramp_level;
                vga_b <= ramp_level;
            end else begin
                vga_r <= r_bar;
                vga_g <= g_bar;
                vga_b <= b_bar;
            end
        end else begin
            vga_r <= 5'd0;
            vga_g <= 5'd0;
            vga_b <= 5'd0;
        end
    end

    // -----------------------------------------------------------------------
    // Status LEDs (driven from the 50 MHz domain for a simple heartbeat;
    // the rest are sampled from the pixel-clock-domain VGA outputs which
    // are slow-changing enough that no full synchroniser is needed for
    // visual indication).
    // -----------------------------------------------------------------------
    reg [25:0] hb = 26'd0;
    always @(posedge clk_in) hb <= hb + 26'd1;

    assign led_base[0]   = hb[25];
    assign led_base[1]   = pll_locked;
    assign led_base[2]   = ~vga_vs;       // pulses each frame
    assign led_base[3]   = ~vga_hs;
    assign led_base[4]   = visible;
    assign led_base[7:5] = bar;
    // 10CL025: one core LED — show PLL lock only (vsync indicator stays on led_base[2]).
    assign led_core[0]   = pll_locked;

endmodule
