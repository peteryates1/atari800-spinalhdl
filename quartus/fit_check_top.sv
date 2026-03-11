// Fit check top-level: Atari800JopTop with real JopCoreForAtari integrated
// All ports are real I/O to prevent Quartus from optimizing away logic.

module fit_check_top (
    input  wire        clk50,

    // SDRAM
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

    // VGA
    output wire [3:0]  vga_r, vga_g, vga_b,
    output wire        vga_hsync, vga_vsync,

    // Audio
    output wire        audio_l, audio_r,

    // SPI
    output wire        spi_sclk, spi_mosi,
    input  wire        spi_miso,
    output wire        spi_cs0, spi_cs1,

    // Joysticks (active-low)
    input  wire [4:0]  joy1, joy2, joy3, joy4,

    // Cart
    output wire [12:0] cart_addr,
    input  wire [7:0]  cart_data,
    output wire        cart_s4_n, cart_s5_n, cart_cctl_n, cart_phi2,
    input  wire        cart_rd4, cart_rd5,

    // UART
    output wire        uart_tx,
    input  wire        uart_rx,

    // HDMI
    output wire        hdmi_d0p, hdmi_d0n, hdmi_d1p, hdmi_d1n,
    output wire        hdmi_d2p, hdmi_d2n, hdmi_clkp, hdmi_clkn,

    // LEDs
    output wire [3:0]  led
);

    Atari800JopTop atari (
        .io_clock50       (clk50),
        .io_sdram_addr    (sdram_addr),
        .io_sdram_ba      (sdram_ba),
        .io_sdram_cs_n    (sdram_cs_n),
        .io_sdram_ras_n   (sdram_ras_n),
        .io_sdram_cas_n   (sdram_cas_n),
        .io_sdram_we_n    (sdram_we_n),
        .io_sdram_clk     (sdram_clk),
        .io_sdram_dqml    (sdram_dqml),
        .io_sdram_dqmh    (sdram_dqmh),
        .io_sdramDq       (sdram_dq),
        .io_vga_r         (vga_r),
        .io_vga_g         (vga_g),
        .io_vga_b         (vga_b),
        .io_vga_hsync     (vga_hsync),
        .io_vga_vsync     (vga_vsync),
        .io_hdmi_d0p      (hdmi_d0p),
        .io_hdmi_d0n      (hdmi_d0n),
        .io_hdmi_d1p      (hdmi_d1p),
        .io_hdmi_d1n      (hdmi_d1n),
        .io_hdmi_d2p      (hdmi_d2p),
        .io_hdmi_d2n      (hdmi_d2n),
        .io_hdmi_clkp     (hdmi_clkp),
        .io_hdmi_clkn     (hdmi_clkn),
        .io_audioL        (audio_l),
        .io_audioR        (audio_r),
        .io_spiSclk       (spi_sclk),
        .io_spiMosi       (spi_mosi),
        .io_spiMiso       (spi_miso),
        .io_spiCs0        (spi_cs0),
        .io_spiCs1        (spi_cs1),
        .io_joy1_up_n     (joy1[0]),
        .io_joy1_down_n   (joy1[1]),
        .io_joy1_left_n   (joy1[2]),
        .io_joy1_right_n  (joy1[3]),
        .io_joy1_fire_n   (joy1[4]),
        .io_joy2_up_n     (joy2[0]),
        .io_joy2_down_n   (joy2[1]),
        .io_joy2_left_n   (joy2[2]),
        .io_joy2_right_n  (joy2[3]),
        .io_joy2_fire_n   (joy2[4]),
        .io_joy3_up_n     (joy3[0]),
        .io_joy3_down_n   (joy3[1]),
        .io_joy3_left_n   (joy3[2]),
        .io_joy3_right_n  (joy3[3]),
        .io_joy3_fire_n   (joy3[4]),
        .io_joy4_up_n     (joy4[0]),
        .io_joy4_down_n   (joy4[1]),
        .io_joy4_left_n   (joy4[2]),
        .io_joy4_right_n  (joy4[3]),
        .io_joy4_fire_n   (joy4[4]),
        .io_cart_addr     (cart_addr),
        .io_cart_data     (cart_data),
        .io_cart_s4_n     (cart_s4_n),
        .io_cart_s5_n     (cart_s5_n),
        .io_cart_cctl_n   (cart_cctl_n),
        .io_cart_rd4      (cart_rd4),
        .io_cart_rd5      (cart_rd5),
        .io_cart_phi2     (cart_phi2),
        .io_uartTx        (uart_tx),
        .io_uartRx        (uart_rx),
        .io_led           (led)
    );

endmodule
