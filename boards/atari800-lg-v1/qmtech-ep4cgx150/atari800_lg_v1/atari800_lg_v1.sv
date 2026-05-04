// Atari 800 + JOP top for ATARI-800-LG-V1 base board + QMTECH EP4CGX150 core.
// Thin wrapper: maps SpinalHDL io_xxx port names to board signal names.
// Single PLL: JOP and Atari both at 56.67 MHz. Shared SDRAM.

module atari800_lg_v1_top (
    input  wire        clk_in,          // PIN_B14  50 MHz oscillator

    // UART — CH340 USB-serial
    output wire        uart_tx,         // U5 pin 7  (AF24) — to CH340 RXD
    input  wire        uart_rx,         // U5 pin 9  (AC21) — from CH340 TXD

    // SDRAM — W9825G6JH6 (32 MB, 16-bit) on core board
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

    // VGA — 5-bit R/G/B resistor DAC on base board
    output wire        vga_hs,
    output wire        vga_vs,
    output wire [4:0]  vga_r,
    output wire [4:0]  vga_g,
    output wire [4:0]  vga_b,

    // CH376T — SPI mode (USB host + SD card)
    output wire        ch376_sck,       // U4 pin 43 (A7)  — SPI clock
    output wire        ch376_mosi,      // U4 pin 46 (B6)  — SPI data to CH376T
    input  wire        ch376_miso,      // U4 pin 45 (B7)  — SPI data from CH376T
    output wire        ch376_cs,        // U4 pin 44 (A6)  — SPI chip select
    input  wire        ch376_int,       // U4 pin 35 (B11) — interrupt (active low)
    output wire        ch376_rst,       // U4 pin 37 (B10) — reset (active high)
    output wire        ch376_spi_n,     // U4 pin 39 (C10) — SPI mode select (low=SPI)

    // LEDs on core board (active high)
    output wire [1:0]  led
);

    // Directly instantiate the SpinalHDL design.
    // Unused outputs are left unconnected; unused inputs get safe defaults.

    // VGA: SpinalHDL outputs 5-bit R, 6-bit G, 5-bit B; board has 5-bit each
    wire [5:0] vga_g_6bit;
    assign vga_g = vga_g_6bit[5:1];  // take top 5 of 6-bit green

    Atari800Ep4cgx150DualPllTop top (
        .io_clk_in       (clk_in),

        .io_vga_hs       (vga_hs),
        .io_vga_vs       (vga_vs),
        .io_vga_r        (vga_r),
        .io_vga_g        (vga_g_6bit),
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

        // CH376T SPI — mapped to sdSpi device
        .io_sdClk        (ch376_sck),    // sdSpi sclk → CH376T SCK
        .io_sdCmd        (ch376_mosi),   // sdSpi mosi → CH376T DI
        .io_sdDat0       (ch376_miso),   // sdSpi miso ← CH376T DO
        .io_sdDat3       (ch376_cs),     // sdSpi cs   → CH376T CS
        .io_sdCd         (ch376_int),    // sdSpi cd   ← CH376T INT#

        // Joystick 1: active low, tie inactive
        .io_joy1Up       (1'b1),
        .io_joy1Down     (1'b1),
        .io_joy1Left     (1'b1),
        .io_joy1Right    (1'b1),
        .io_joy1Fire     (1'b1),

        .io_led          (led)
    );

    // CH376T mode: SPI (active low) — must be stable BEFORE reset release
    assign ch376_spi_n = 1'b0;

    // CH376T reset sequencer: hold RSTI high for ~100ms after FPGA config,
    // then release. This ensures SPI# is sampled as low (SPI mode) during
    // the CH376T's reset exit. Without this, the CH376T may enter UART mode
    // because its internal power-on reset completes before FPGA outputs are stable.
    //
    // At 50 MHz input clock: 100ms = 5,000,000 cycles. Use 23-bit counter.
    reg [22:0] rst_cnt = 0;
    wire       rst_done = rst_cnt[22];  // bit 22 set after ~4.2M cycles = ~84ms
    always @(posedge clk_in) begin
        if (!rst_done)
            rst_cnt <= rst_cnt + 1'b1;
    end
    assign ch376_rst = ~rst_done;  // HIGH (reset) until counter expires

endmodule
