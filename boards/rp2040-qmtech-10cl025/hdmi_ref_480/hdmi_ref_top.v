// Port of the corecourse c23_hdmi_color reference demo (720p colour bars) to
// the ATARI-800-QMTechCB-RP2040-STAMP-HDMI-LG + QMTECH 10CL025 board.
//
// PURPOSE: the reference demo is a KNOWN-GOOD HDMI design for this exact FPGA
// (10CL025) and HDMI front-end (AZ1045 + 100nF AC-coupling, per ACC2361). It
// outputs 1280x720@60 (74.25 MHz pixel / 371.25 MHz 5x), which HDMI sinks
// accept far more reliably than our 640x480 attempt. If THIS shows bars, the
// board hardware is good and the fault was our SpinalHDL DvidOut / the 640x480
// resolution; if it's blank too, the fault is board/assembly.
//
// Only changes vs the demo: device = board's 10CL025YU256I7G, pins remapped to
// this board's diff-capable HDMI pairs, rst_n from an internal power-on reset.

module hdmi_ref_top (
    input  wire       clk_in,        // PIN_E2  50 MHz  → pll_hdmi (in demo: 50 MHz)
    output wire       tmds_clk_p,    // R11
    output wire       tmds_clk_n,    // T11
    output wire [2:0] tmds_data_p,   // [0]=R12 blue  [1]=R13 green  [2]=T14 red
    output wire [2:0] tmds_data_n,   // [0]=T12       [1]=T13        [2]=T15
    output wire [0:0] led_core       // PIN_N9  heartbeat
);

    // Power-on reset (active low): held low ~1.3 ms after config, then released.
    reg [15:0] por_cnt = 16'd0;
    reg        rst_n   = 1'b0;
    always @(posedge clk_in) begin
        if (~&por_cnt) por_cnt <= por_cnt + 16'd1;
        rst_n <= &por_cnt;
    end

    hdmi_color u_hdmi (
        .clk         (clk_in),
        .rst_n       (rst_n),
        .tmds_clk_p  (tmds_clk_p),
        .tmds_clk_n  (tmds_clk_n),
        .tmds_data_p (tmds_data_p),
        .tmds_data_n (tmds_data_n)
    );

    // Raw-clock heartbeat so the on-module LED always shows the design is alive.
    reg [25:0] hb = 26'd0;
    always @(posedge clk_in) hb <= hb + 26'd1;
    assign led_core[0] = hb[25];

endmodule
