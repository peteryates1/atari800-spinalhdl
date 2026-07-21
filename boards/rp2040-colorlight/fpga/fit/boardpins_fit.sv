// Auto-generated pin fit-check: every used SODIMM ball as an FPGA IO.
module boardpins_fit(input [34:0] pin_in, output [33:0] pin_out);
  wire x = ^pin_in;
  assign pin_out = {34{x}};
endmodule
