// 720p feasibility, all 4 i5-ext HDMI lanes (8 pins P/N) via ODDRX2F on shared ECLK
module oddr2_4lane(input clk25, output [7:0] gpdi);
  wire pll_o0, locked, eclk, sclk;
  pll371 pll(.clkin(clk25), .clkout0(pll_o0), .locked(locked));
  ECLKSYNCB esync(.ECLKI(pll_o0), .STOP(1'b0), .ECLKO(eclk));
  CLKDIVF #(.DIV("2.0")) cdiv(.CLKI(eclk), .RST(1'b0), .ALIGNWD(1'b0), .CDIVX(sclk));
  reg [3:0] d = 0;
  always @(posedge sclk) d <= d + 4'b0101;
  genvar i;
  generate for (i=0;i<8;i=i+1) begin: lane
    ODDRX2F ser(.D0(d[0]^i[0]),.D1(d[1]),.D2(d[2]),.D3(d[3]^i[1]),
                .ECLK(eclk),.SCLK(sclk),.RST(1'b0),.Q(gpdi[i]));
  end endgenerate
endmodule
