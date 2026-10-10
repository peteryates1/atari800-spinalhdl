module fit(input a, output [81:0] pin);
  assign pin = {82{a}};
endmodule
