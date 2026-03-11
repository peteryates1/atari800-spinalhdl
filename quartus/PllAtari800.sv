// PLL stub for synthesis fit check
// In real design, this is an Altera ALTPLL megafunction.
// For fit checking, just pass through clk and assert locked.
module PllAtari800 (
    input  wire inclk0,
    output wire c0,
    output wire c1,
    output wire c2,
    output wire locked
);
    assign c0 = inclk0;      // 56.67 MHz system
    assign c1 = inclk0;      // 56.67 MHz SDRAM (phase-shifted)
    assign c2 = inclk0;      // 25 MHz VGA text
    assign locked = 1'b1;
endmodule
