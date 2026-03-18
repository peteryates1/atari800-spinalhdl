// Atari 800 + JOP top for QMTECH EP4CGX150 + DB_FPGA daughter board.
// Thin wrapper: maps SpinalHDL io_xxx port names to real board signal names
// so that QSF pin assignments can use friendly names.

module ep4cgx150_jop_top (
    input  wire        clk_in,          // PIN_B14  50 MHz oscillator

    // SDRAM (W9825G6JH6, 32 MB, 16-bit)
    output wire [12:0] sdram_addr,
    output wire [1:0]  sdram_ba,
    output wire        sdram_cs_n,
    output wire        sdram_ras_n,
    output wire        sdram_cas_n,
    output wire        sdram_we_n,
    output wire        sdram_clk,
    output wire        sdram_dqml,
    output wire        sdram_dqmh,
    inout  wire [15:0] sdram_dq,

    // VGA — DB_FPGA daughter board 5-6-5 DAC
    output wire        vga_hs,
    output wire        vga_vs,
    output wire [4:0]  vga_r,
    output wire [5:0]  vga_g,
    output wire [4:0]  vga_b,

    // UART — DB_FPGA CP2102N
    output wire        uart_tx,         // PIN_AD20
    input  wire        uart_rx,         // PIN_AE21

    // CH376T SPI module — PMOD J11
    output wire        ch376_sclk,      // J11 pin 1 (AF25)
    output wire        ch376_mosi,      // J11 pin 2 (AD21)
    input  wire        ch376_miso,      // J11 pin 3 (AF23)
    output wire        ch376_cs_n,      // J11 pin 4 (AF22)
    input  wire        ch376_int,       // J11 pin 7 (AF24)

    // Joystick 1 — PMOD J10 (active low, DB-9)
    input  wire        joy1_up,         // J10 pin 1 (AF21)
    input  wire        joy1_down,       // J10 pin 2 (AF19)
    input  wire        joy1_left,       // J10 pin 3 (AD19)
    input  wire        joy1_right,      // J10 pin 4 (AF18)
    input  wire        joy1_fire,       // J10 pin 7 (AF20)

    // LEDs on core board (active high)
    output wire [1:0]  led
);

    Atari800Ep4cgx150JopTop jop (
        .io_clk_in       (clk_in),

        .io_sdram_addr   (sdram_addr),
        .io_sdram_ba     (sdram_ba),
        .io_sdram_cs_n   (sdram_cs_n),
        .io_sdram_ras_n  (sdram_ras_n),
        .io_sdram_cas_n  (sdram_cas_n),
        .io_sdram_we_n   (sdram_we_n),
        .io_sdram_clk    (sdram_clk),
        .io_sdram_dqml   (sdram_dqml),
        .io_sdram_dqmh   (sdram_dqmh),
        .io_sdramDq      (sdram_dq),

        .io_vga_hs       (vga_hs),
        .io_vga_vs       (vga_vs),
        .io_vga_r        (vga_r),
        .io_vga_g        (vga_g),
        .io_vga_b        (vga_b),

        .io_uartTx       (uart_tx),
        .io_uartRx       (uart_rx),

        .io_ch376Sclk    (ch376_sclk),
        .io_ch376Mosi    (ch376_mosi),
        .io_ch376Miso    (ch376_miso),
        .io_ch376Cs      (ch376_cs_n),
        .io_ch376Int     (ch376_int),

        .io_joy1Up       (joy1_up),
        .io_joy1Down     (joy1_down),
        .io_joy1Left     (joy1_left),
        .io_joy1Right    (joy1_right),
        .io_joy1Fire     (joy1_fire),

        .io_led          (led)
    );

endmodule
