// Atari 800 + JOP top for QMTECH EP4CGX150 + DB_FPGA daughter board.
// Thin wrapper: maps SpinalHDL io_xxx port names to board signal names.
// Single PLL: JOP and Atari both at 56.67 MHz. Shared SDRAM.

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

    // SD Card — DB_FPGA onboard microSD slot (SPI mode)
    output wire        sd_clk,          // J3:9  (B21)
    output wire        sd_cmd,          // J3:10 (A22) — MOSI
    input  wire        sd_dat0,         // J3:8  (A23) — MISO
    output wire        sd_dat1,         // J3:7  (B23) — pull high in SPI mode
    output wire        sd_dat2,         // J3:1  (B19) — pull high in SPI mode
    output wire        sd_dat3,         // J3:11 (C19) — CS
    input  wire        sd_cd,           // J3:6  (B22) — card detect

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

        .io_sdramAddr    (sdram_ADDR),
        .io_sdramBa      (sdram_BA),
        .io_sdramDq      (sdram_DQ),
        .io_sdramDqm     (sdram_DQM),
        .io_sdramCke     (sdram_CKE),
        .io_sdramCsN     (sdram_CSn),
        .io_sdramRasN    (sdram_RASn),
        .io_sdramCasN    (sdram_CASn),
        .io_sdramWeN     (sdram_WEn),
        .io_sdramClk     (sdram_clk),

        .io_sdClk        (sd_clk),
        .io_sdCmd        (sd_cmd),
        .io_sdDat0       (sd_dat0),
        .io_sdDat3       (sd_dat3),
        .io_sdCd         (sd_cd),

        .io_joy1Up       (joy1_up),
        .io_joy1Down     (joy1_down),
        .io_joy1Left     (joy1_left),
        .io_joy1Right    (joy1_right),
        .io_joy1Fire     (joy1_fire),

        .io_led          (led)
    );

    // SD DAT1/DAT2 driven high for SPI mode (not used by SdSpi controller)
    assign sd_dat1 = 1'b1;
    assign sd_dat2 = 1'b1;

endmodule
