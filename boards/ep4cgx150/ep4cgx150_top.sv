// Atari 800 bare-metal top for QMTECH EP4CGX150 + DB_FPGA daughter board.
// Thin wrapper: maps SpinalHDL io_xxx port names to real board signal names
// so that QSF pin assignments can use friendly names.

module ep4cgx150_top (
    input  wire        clk_in,          // PIN_B14  50 MHz oscillator

    // SDRAM (W9825G6JH6, 32 MB, 16-bit)
    output wire [12:0] sdram_addr,
    output wire [1:0]  sdram_ba,
    output wire        sdram_cs_n,
    output wire        sdram_ras_n,
    output wire        sdram_cas_n,
    output wire        sdram_we_n,
    output wire        sdram_clk,       // PIN_E22
    output wire        sdram_dqml,
    output wire        sdram_dqmh,
    inout  wire [15:0] sdram_dq,

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

        .io_sdram_addr  (sdram_addr),
        .io_sdram_ba    (sdram_ba),
        .io_sdram_cs_n  (sdram_cs_n),
        .io_sdram_ras_n (sdram_ras_n),
        .io_sdram_cas_n (sdram_cas_n),
        .io_sdram_we_n  (sdram_we_n),
        .io_sdram_clk   (sdram_clk),
        .io_sdram_dqml  (sdram_dqml),
        .io_sdram_dqmh  (sdram_dqmh),
        .io_sdramDq     (sdram_dq),

        .io_vga_hs      (vga_hs),
        .io_vga_vs      (vga_vs),
        .io_vga_r       (vga_r),
        .io_vga_g       (vga_g),
        .io_vga_b       (vga_b),

        .io_led         (led)
    );

endmodule
