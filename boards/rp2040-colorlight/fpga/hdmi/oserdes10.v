module oserdes10(input clk, input clkdiv, input rst, input [9:0] d, output outp, output outn);
  wire s1,s2,oq;
  OSERDESE2 #(.DATA_RATE_OQ("DDR"),.DATA_RATE_TQ("SDR"),.DATA_WIDTH(10),.SERDES_MODE("MASTER"),.TRISTATE_WIDTH(1)) mstr(
    .OQ(oq),.OFB(),.SHIFTOUT1(),.SHIFTOUT2(),.TBYTEOUT(),.TFB(),.TQ(),
    .CLK(clk),.CLKDIV(clkdiv),.OCE(1'b1),.RST(rst),
    .D1(d[0]),.D2(d[1]),.D3(d[2]),.D4(d[3]),.D5(d[4]),.D6(d[5]),.D7(d[6]),.D8(d[7]),
    .SHIFTIN1(s1),.SHIFTIN2(s2),.T1(1'b0),.T2(1'b0),.T3(1'b0),.T4(1'b0),.TBYTEIN(1'b0),.TCE(1'b0));
  OSERDESE2 #(.DATA_RATE_OQ("DDR"),.DATA_RATE_TQ("SDR"),.DATA_WIDTH(10),.SERDES_MODE("SLAVE"),.TRISTATE_WIDTH(1)) slv(
    .OQ(),.OFB(),.SHIFTOUT1(s1),.SHIFTOUT2(s2),.TBYTEOUT(),.TFB(),.TQ(),
    .CLK(clk),.CLKDIV(clkdiv),.OCE(1'b1),.RST(rst),
    .D1(1'b0),.D2(1'b0),.D3(d[8]),.D4(d[9]),.D5(1'b0),.D6(1'b0),.D7(1'b0),.D8(1'b0),
    .SHIFTIN1(1'b0),.SHIFTIN2(1'b0),.T1(1'b0),.T2(1'b0),.T3(1'b0),.T4(1'b0),.TBYTEIN(1'b0),.TCE(1'b0));
  OBUFDS ob(.I(oq),.O(outp),.OB(outn));
endmodule
