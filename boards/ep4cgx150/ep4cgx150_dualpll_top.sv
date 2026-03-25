// Atari 800 + JOP dual-PLL top for QMTECH EP4CGX150 + DB_FPGA daughter board.
// Thin wrapper: maps SpinalHDL io_xxx port names to board signal names.
// JOP at 80 MHz (dram_pll), Atari at 56.67 MHz (atari_pll).

module ep4cgx150_dualpll_top (
    input  wire        clk_in,          // PIN_B14  50 MHz oscillator

    // VGA — DB_FPGA daughter board 5-6-5 DAC
    output wire        vga_hs,
    output wire        vga_vs,
    output wire [4:0]  vga_r,
    output wire [5:0]  vga_g,
    output wire [4:0]  vga_b,

    // UART — DB_FPGA CP2102N
    output wire        uart_tx,         // PIN_AD20
    input  wire        uart_rx,         // PIN_AE21

    // SDRAM — W9825G6JH6 (32 MB, 16-bit)
    output wire [12:0] sdram_ADDR,
    output wire [1:0]  sdram_BA,
    inout  wire [15:0] sdram_DQ,
    output wire [1:0]  sdram_DQM,
    output wire        sdram_CKE,
    output wire        sdram_CSn,
    output wire        sdram_RASn,
    output wire        sdram_CASn,
    output wire        sdram_WEn,
    output wire        sdram_clk,

    // CH376S SPI module — PMOD J11
    output wire        ch376_sck,       // J11 pin 10 (AF22)
    output wire        ch376_mosi,      // J11 pin 3  (AE23)
    input  wire        ch376_miso,      // J11 pin 8  (AD21)
    output wire        ch376_cs,        // J11 pin 2  (AC21)
    input  wire        ch376_int,       // J11 pin 9  (AF23)
    output wire        ch376_rst,       // J11 pin 4  (AE22)

    // Joystick 1 — PMOD J10 (active low, DB-9)
    input  wire        joy1_up,         // J10 pin 1 (AF20)
    input  wire        joy1_down,       // J10 pin 2 (AE19)
    input  wire        joy1_left,       // J10 pin 3 (AC19)
    input  wire        joy1_right,      // J10 pin 4 (AE18)
    input  wire        joy1_fire,       // J10 pin 7 (AF21)

    // LEDs on core board (active high)
    output wire [1:0]  led
);

    Atari800Ep4cgx150DualPllTop top (
        .io_clk_in       (clk_in),

        .io_vga_hs       (vga_hs),
        .io_vga_vs       (vga_vs),
        .io_vga_r        (vga_r),
        .io_vga_g        (vga_g),
        .io_vga_b        (vga_b),

        .io_uartTx       (uart_tx),
        .io_uartRx       (uart_rx),

        .io_sdram_ADDR   (sdram_ADDR),
        .io_sdram_BA     (sdram_BA),
        .io_sdram_DQ     (sdram_DQ),
        .io_sdram_DQM    (sdram_DQM),
        .io_sdram_CKE    (sdram_CKE),
        .io_sdram_CSn    (sdram_CSn),
        .io_sdram_RASn   (sdram_RASn),
        .io_sdram_CASn   (sdram_CASn),
        .io_sdram_WEn    (sdram_WEn),
        .io_sdram_clk    (sdram_clk),

        .io_ch376Sck     (ch376_sck),
        .io_ch376Mosi    (ch376_mosi),
        .io_ch376Miso    (ch376_miso),
        .io_ch376Cs      (ch376_cs),
        .io_ch376Int     (ch376_int),
        .io_ch376Rst     (ch376_rst),

        .io_joy1Up       (joy1_up),
        .io_joy1Down     (joy1_down),
        .io_joy1Left     (joy1_left),
        .io_joy1Right    (joy1_right),
        .io_joy1Fire     (joy1_fire),

        .io_led          (led)
    );

endmodule
