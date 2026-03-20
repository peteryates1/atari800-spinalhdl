// ECP5 fit check: Atari 800 BRAM-only (no SDRAM, no JOP)
// Colorlight i5 v7.0 — LFE5U-25F

module fit_check_top (
    input  wire        clk50,    // 25 MHz on Colorlight (name kept for LPF compat)

    // VGA
    output wire [3:0]  vga_r, vga_g, vga_b,
    output wire        vga_hsync, vga_vsync,

    // LEDs
    output wire [3:0]  led
);

    Atari800Ecp5BramTop atari (
        .io_clk_in      (clk50),

        .io_vga_r       (vga_r),
        .io_vga_g       (vga_g),
        .io_vga_b       (vga_b),
        .io_vga_hs      (vga_hsync),
        .io_vga_vs      (vga_vsync),

        .io_led         (led)
    );

endmodule
