module ecp5_oddrx2x4(input [15:0] nib, input eclk, input sclk, output [3:0] q);
  genvar i;
  generate for (i=0;i<4;i=i+1) begin: lane
    ODDRX2F ser(.D0(nib[i*4+0]),.D1(nib[i*4+1]),.D2(nib[i*4+2]),.D3(nib[i*4+3]),
                .ECLK(eclk),.SCLK(sclk),.RST(1'b0),.Q(q[i]));
  end endgenerate
endmodule
