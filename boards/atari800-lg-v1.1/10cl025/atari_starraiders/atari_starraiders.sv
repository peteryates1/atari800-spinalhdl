// V1.1 + QMTECH 10CL025: full Atari 800 with Star Raiders cartridge, no JOP.
//
// Reuses the existing SpinalHDL-generated Atari800LgV1Top (designed for V1
// base board), and adapts its IO to the V1.1 pin layout:
//   * V1 had 4 base LEDs + 2 core LEDs; V1.1 has 8 base + 1 core.
//   * V1 had one CH376T (USB+SD time-shared); V1.1 has two — KB CH376T only
//     since the SD CH376T's data traces are damaged. SD is unused here.
//   * V1.1 needs a 12 MHz clock output to drive the CH376T's XI pin
//     (their crystals are removed); we add a dedicated ch376_pll.
//   * V1's ch376SpiN (SPI mode select) is no longer routed — the V1.1
//     base board hardwires the chip into SPI mode.

module atari_starraiders (
    input  wire        clk_in,         // PIN_E2  50 MHz on 10CL025

    // VGA — 5-bit R/G/B resistor DAC on V1.1 base board
    output wire        vga_hs,
    output wire        vga_vs,
    output wire [4:0]  vga_r,
    output wire [4:0]  vga_g,
    output wire [4:0]  vga_b,

    // Audio — sigma-delta 1-bit DAC
    output wire        audio_l,
    output wire        audio_r,

    // Joystick 1 (active-low)
    input  wire        js1_up,
    input  wire        js1_down,
    input  wire        js1_left,
    input  wire        js1_right,
    input  wire        js1_trig,

    // Joystick 2 (active-low)
    input  wire        js2_up,
    input  wire        js2_down,
    input  wire        js2_left,
    input  wire        js2_right,
    input  wire        js2_trig,

    // Console switches (active-low — pull-ups in v1_1_pins.tcl)
    input  wire        sw_start,
    input  wire        sw_select,
    input  wire        sw_option,
    input  wire        sw_reset,

    // CH376T_KB (USB keyboard) — SPI mode
    output wire        ch376_kb_sck,
    output wire        ch376_kb_mosi,
    input  wire        ch376_kb_miso,
    output wire        ch376_kb_cs,
    input  wire        ch376_kb_int,
    output wire        ch376_kb_rst,

    // 12 MHz clock OUT to both CH376Ts' XI pin
    output wire        ch376_12mhz,

    // UART (CH340) — Atari core only emits TX
    output wire        uart_tx,
    input  wire        uart_rx,         // unused but kept so v1_1_pins.tcl applies

    // LEDs
    output wire [7:0]  led_base,
    output wire [0:0]  led_core
);

    // suppress unused-input warning
    wire _rx_keep = uart_rx;

    // -----------------------------------------------------------------------
    // 12 MHz PLL → ch376_12mhz pin (drives XI of both CH376Ts via U9.50)
    // -----------------------------------------------------------------------
    wire ch376_pll_locked;
    ch376_pll u_pll12 (
        .areset (1'b0),
        .inclk0 (clk_in),
        .c0     (ch376_12mhz),
        .locked (ch376_pll_locked)
    );

    // -----------------------------------------------------------------------
    // Atari core — SpinalHDL-generated, configured for 48K BRAM + 800 OS +
    // Star Raiders cart (cartridge_rom replaces the upper 8K of RAM, so the
    // total BRAM footprint is 58 of the 10CL025's 66 M9K blocks).
    // -----------------------------------------------------------------------
    wire [1:0] v1_led;          // 2-bit core LED bus from the V1 design
    wire [3:0] v1_led_base;     // 4-bit base LED bus from the V1 design
    wire       v1_ch376_spi_n;  // V1 drove this to GND for SPI-mode select;
                                // unused on V1.1 (hardwired on the base board)

    Atari800LgV1Top u_atari (
        .io_clk_in       (clk_in),

        .io_vga_hs       (vga_hs),
        .io_vga_vs       (vga_vs),
        .io_vga_r        (vga_r),
        .io_vga_g        (vga_g),
        .io_vga_b        (vga_b),

        .io_audio_l      (audio_l),
        .io_audio_r      (audio_r),

        .io_joy1Up       (js1_up),
        .io_joy1Down     (js1_down),
        .io_joy1Left     (js1_left),
        .io_joy1Right    (js1_right),
        .io_joy1Fire     (js1_trig),

        .io_joy2Up       (js2_up),
        .io_joy2Down     (js2_down),
        .io_joy2Left     (js2_left),
        .io_joy2Right    (js2_right),
        .io_joy2Fire     (js2_trig),

        .io_consolStart  (sw_start),
        .io_consolSelect (sw_select),
        .io_consolOption (sw_option),
        .io_consolReset  (sw_reset),

        // KB CH376T provides the USB keyboard. SD CH376T is unused (broken).
        .io_ch376Sck     (ch376_kb_sck),
        .io_ch376Mosi    (ch376_kb_mosi),
        .io_ch376Miso    (ch376_kb_miso),
        .io_ch376Cs      (ch376_kb_cs),
        .io_ch376Int     (ch376_kb_int),
        .io_ch376Rst     (ch376_kb_rst),
        .io_ch376SpiN    (v1_ch376_spi_n),    // dangling

        .io_uartTx       (uart_tx),

        .io_led          (v1_led),
        .io_ledBase      (v1_led_base)
    );

    // -----------------------------------------------------------------------
    // LED mapping V1 → V1.1:
    //   v1_led[0]      → led_core[0]
    //   v1_led[1]      → dropped (V1.1 has no second core LED)
    //   v1_led_base    → led_base[3:0]
    //   led_base[7:4]  → status indicators (12 MHz PLL lock + flat reserved)
    // -----------------------------------------------------------------------
    assign led_core[0]    = v1_led[0];
    assign led_base[3:0]  = v1_led_base;
    assign led_base[4]    = ch376_pll_locked;
    assign led_base[7:5]  = 3'b000;

endmodule
