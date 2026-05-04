// VGA test pattern generator for ATARI-800-LG-V1 base board.
// 640x480 @ 60 Hz from 50 MHz clock (pixel clock ~25 MHz via toggle).
// Generates color bars: Red, Green, Blue, White, Cyan, Magenta, Yellow, Black.

module vga_test_top (
    input  wire       clk_in,       // 50 MHz
    output reg        vga_hs,
    output reg        vga_vs,
    output reg  [4:0] vga_r,
    output reg  [4:0] vga_g,
    output reg  [4:0] vga_b,
    output wire [1:0] led
);

    // ---- 25 MHz pixel clock from 50 MHz ----
    reg pclk = 0;
    always @(posedge clk_in) pclk <= ~pclk;

    // ---- VGA 640x480 @ 60 Hz timing ----
    // Pixel clock: 25.175 MHz (25 MHz close enough)
    // H: 640 visible + 16 front + 96 sync + 48 back = 800 total
    // V: 480 visible + 10 front + 2 sync + 33 back = 525 total
    localparam H_VIS = 640, H_FP = 16, H_SYNC = 96, H_BP = 48;
    localparam H_TOTAL = H_VIS + H_FP + H_SYNC + H_BP;  // 800
    localparam V_VIS = 480, V_FP = 10, V_SYNC = 2, V_BP = 33;
    localparam V_TOTAL = V_VIS + V_FP + V_SYNC + V_BP;  // 525

    reg [9:0] hcnt = 0;
    reg [9:0] vcnt = 0;

    always @(posedge clk_in) if (pclk) begin
        if (hcnt == H_TOTAL - 1) begin
            hcnt <= 0;
            if (vcnt == V_TOTAL - 1)
                vcnt <= 0;
            else
                vcnt <= vcnt + 1'd1;
        end else
            hcnt <= hcnt + 1'd1;
    end

    // ---- Sync signals (active low) ----
    wire h_sync = (hcnt >= H_VIS + H_FP) && (hcnt < H_VIS + H_FP + H_SYNC);
    wire v_sync = (vcnt >= V_VIS + V_FP) && (vcnt < V_VIS + V_FP + V_SYNC);
    wire visible = (hcnt < H_VIS) && (vcnt < V_VIS);

    // ---- Color bars (8 vertical bars, 80 pixels each) ----
    wire [2:0] bar = (hcnt < 80)  ? 3'd0 :
                     (hcnt < 160) ? 3'd1 :
                     (hcnt < 240) ? 3'd2 :
                     (hcnt < 320) ? 3'd3 :
                     (hcnt < 400) ? 3'd4 :
                     (hcnt < 480) ? 3'd5 :
                     (hcnt < 560) ? 3'd6 : 3'd7;

    always @(posedge clk_in) if (pclk) begin
        vga_hs <= ~h_sync;
        vga_vs <= ~v_sync;

        if (visible) begin
            case (bar)
                3'd0: begin vga_r <= 5'h1F; vga_g <= 5'h00; vga_b <= 5'h00; end  // Red
                3'd1: begin vga_r <= 5'h00; vga_g <= 5'h1F; vga_b <= 5'h00; end  // Green
                3'd2: begin vga_r <= 5'h00; vga_g <= 5'h00; vga_b <= 5'h1F; end  // Blue
                3'd3: begin vga_r <= 5'h1F; vga_g <= 5'h1F; vga_b <= 5'h1F; end  // White
                3'd4: begin vga_r <= 5'h00; vga_g <= 5'h1F; vga_b <= 5'h1F; end  // Cyan
                3'd5: begin vga_r <= 5'h1F; vga_g <= 5'h00; vga_b <= 5'h1F; end  // Magenta
                3'd6: begin vga_r <= 5'h1F; vga_g <= 5'h1F; vga_b <= 5'h00; end  // Yellow
                3'd7: begin vga_r <= 5'h00; vga_g <= 5'h00; vga_b <= 5'h00; end  // Black
            endcase
        end else begin
            vga_r <= 5'h00;
            vga_g <= 5'h00;
            vga_b <= 5'h00;
        end
    end

    // ---- Heartbeat LED ----
    reg [24:0] hb = 0;
    always @(posedge clk_in) hb <= hb + 1'd1;
    assign led[0] = hb[24];
    assign led[1] = hb[23];
endmodule
