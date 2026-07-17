module ecp5_clkgen720(input clk25, output pixel, output sclk, output eclk, output sys, output locked);
  wire serclk;
  pll_hdmi_ecp5 pll(.clkin(clk25), .clkout0(serclk), .clkout1(pixel), .clkout2(sys), .locked(locked));
  ECLKSYNCB esync(.ECLKI(serclk), .STOP(1'b0), .ECLKO(eclk));
  CLKDIVF #(.DIV("2.0")) cdiv(.CLKI(eclk), .RST(1'b0), .ALIGNWD(1'b0), .CDIVX(sclk));
endmodule
