// ECP5 top wrapper: Atari 800 BRAM-only
// Colorlight i5 v7.0 — LFE5U-25F

module fit_check_top (
    input  wire        clk50,    // 25 MHz on Colorlight

    // VGA
    output wire [3:0]  vga_r, vga_g, vga_b,
    output wire        vga_hsync, vga_vsync,

    // Audio
    output wire        audio_l, audio_r,

    // Joystick 1 (active low)
    input  wire        joy1_up, joy1_down, joy1_left, joy1_right, joy1_fire,

    // Joystick 2 (active low)
    input  wire        joy2_up, joy2_down, joy2_left, joy2_right, joy2_fire,

    // Console switches (active low)
    input  wire        consol_option, consol_select, consol_start, consol_reset,

    // Keyboard (POKEY scan/response)
    input  wire [1:0]  kb_response,
    output wire [5:0]  kb_scan,

    // SIO
    input  wire        sio_rxd,
    output wire        sio_txd,
    output wire        sio_clockout,

    // LEDs
    output wire [3:0]  led
);

    Atari800Ecp5BramTop atari (
        .io_clk_in          (clk50),

        .io_vga_r           (vga_r),
        .io_vga_g           (vga_g),
        .io_vga_b           (vga_b),
        .io_vga_hs          (vga_hsync),
        .io_vga_vs          (vga_vsync),

        .io_audio_l         (audio_l),
        .io_audio_r         (audio_r),

        .io_joy1Up          (joy1_up),
        .io_joy1Down        (joy1_down),
        .io_joy1Left        (joy1_left),
        .io_joy1Right       (joy1_right),
        .io_joy1Fire        (joy1_fire),

        .io_joy2Up          (joy2_up),
        .io_joy2Down        (joy2_down),
        .io_joy2Left        (joy2_left),
        .io_joy2Right       (joy2_right),
        .io_joy2Fire        (joy2_fire),

        .io_consolOption    (consol_option),
        .io_consolSelect    (consol_select),
        .io_consolStart     (consol_start),
        .io_consolReset     (consol_reset),

        .io_keyboardResponse (kb_response),
        .io_keyboardScan    (kb_scan),

        .io_sioRxd          (sio_rxd),
        .io_sioTxd          (sio_txd),
        .io_sioClockout     (sio_clockout),

        .io_led             (led)
    );

endmodule
