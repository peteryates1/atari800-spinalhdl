// HDMI continuity test, TRANSMIT end: fpga-db-colorlight-i5 + i9+.
// Cable it connector-to-connector to a Wukong running ct_rx_wukong.v.
// Each of the 8 TMDS conductors gets its own square wave, 25 MHz / 2^(i+4):
//   i 0 D0+  1.5625 MHz   i 1 D0-  781 kHz   i 2 D1+  391 kHz   i 3 D1-  195 kHz
//   i 4 D2+  97.7 kHz     i 5 D2-  48.8 kHz  i 6 CLK+ 24.4 kHz  i 7 CLK- 12.2 kHz
// Square waves, not levels: the board AC-couples every lane (100 nF).
// LED i lights while line i is driven (all, always); LED7 also beats.
module ct_tx_i9plus (
    input  wire       clk25,
    output wire [7:0] line,    // connector conductors, order above
    output wire [7:0] led
);
    reg [24:0] c = 0;
    always @(posedge clk25) c <= c + 25'd1;
    genvar i;
    generate for (i = 0; i < 8; i = i + 1) begin : g
        assign line[i] = c[i + 3];
    end endgenerate
    assign led = {c[24], 7'h7f};
endmodule
