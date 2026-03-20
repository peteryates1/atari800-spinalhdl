// Atari 800 bare-metal top for QMTECH EP4CGX150 + DB_FPGA daughter board.
// BRAM-only Atari core — no SDRAM access. SDRAM reserved for JOP (future).
// Thin wrapper: maps SpinalHDL io_xxx port names to real board signal names
// so that QSF pin assignments can use friendly names.

module ep4cgx150_top (
    input  wire        clk_in,          // PIN_B14  50 MHz oscillator

    // VGA — DB_FPGA daughter board 5-6-5 DAC
    output wire        vga_hs,          // PIN_A6
    output wire        vga_vs,          // PIN_A7
    output wire [4:0]  vga_r,
    output wire [5:0]  vga_g,
    output wire [4:0]  vga_b,

    // LEDs on core board
    output wire [1:0]  led
);

    Atari800Ep4cgx150Top atari (
        .io_clk_in      (clk_in),

        .io_vga_hs      (vga_hs),
        .io_vga_vs      (vga_vs),
        .io_vga_r       (vga_r),
        .io_vga_g       (vga_g),
        .io_vga_b       (vga_b),

        .io_led         (led)
    );

endmodule
