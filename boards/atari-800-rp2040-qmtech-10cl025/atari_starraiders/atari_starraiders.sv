// Top-level wrapper for ATARI-800-QMTechCB-RP2040-STAMP-HDMI-LG +
// QMTECH 10CL025. Thin shim around the SpinalHDL-generated
// Atari800Rp2040HdmiLgTop — translates the io_* port names emitted by
// SpinalHDL into the names referenced by ../pins.tcl.

module atari_starraiders (
    input  wire        clk_in,           // PIN_E2  50 MHz

    // HDMI (4× TMDS pairs)
    output wire        hdmi_clk_p,
    output wire        hdmi_clk_n,
    output wire        hdmi_d0_p,
    output wire        hdmi_d0_n,
    output wire        hdmi_d1_p,
    output wire        hdmi_d1_n,
    output wire        hdmi_d2_p,
    output wire        hdmi_d2_n,

    // Audio
    output wire        audio_l,
    output wire        audio_r,

    // Console switches
    input  wire        sw_reset,
    input  wire        sw_option,
    input  wire        sw_select,
    input  wire        sw_start,

    // Raspberry Pi Radio Module 2 SPI + control
    output wire        rm2_sck,
    output wire        rm2_mosi,
    input  wire        rm2_miso,
    output wire        rm2_cs,
    input  wire        rm2_irq_n,
    output wire        rm2_wifi_on,
    output wire        rm2_bt_on,

    // SD card (SDIO 4-bit wiring; FPGA drives in SPI mode here so DAT[2:1]
    // are inputs that go nowhere internally — declared so Quartus places them)
    input  wire        sd_cd,
    output wire        sd_clk,
    output wire        sd_cmd,
    input  wire [2:0]  sd_dat_in,       // bits 0/1/2 used; 1+2 unused in SPI mode
    output wire        sd_dat_3,        // DAT3 = SPI CS (output)

    // Joystick 2 (J11)
    input  wire        js2_right,
    input  wire        js2_left,
    input  wire        js2_down,
    input  wire        js2_trig,
    input  wire        js2_up,

    // Joystick 1 (J10)
    input  wire        js1_right,
    input  wire        js1_down,
    input  wire        js1_left,
    input  wire        js1_trig,
    input  wire        js1_up,

    // RP2040 ↔ FPGA dedicated SPI slave port
    input  wire        rp_mosi,
    input  wire        rp_sck,
    input  wire        rp_csn,
    output wire        rp_miso,

    // RP2040 GPIO bus — one port per actually-used line.
    //   Inputs to FPGA: 4, 5, 10, 11, 13, 20, 21, 23
    //   Outputs from FPGA: 12, 14, 15, 22, 24, 25
    input  wire        rp_gpio4,
    input  wire        rp_gpio5,
    input  wire        rp_gpio10,
    input  wire        rp_gpio11,
    output wire        rp_gpio12,
    input  wire        rp_gpio13,
    output wire        rp_gpio14,
    output wire        rp_gpio15,
    input  wire        rp_gpio20,
    input  wire        rp_gpio21,
    output wire        rp_gpio22,
    input  wire        rp_gpio23,
    output wire        rp_gpio24,
    output wire        rp_gpio25,

    // Core-board LED
    output wire [0:0]  led_core
);

    Atari800Rp2040HdmiLgTop u_top (
        .io_clk_in       (clk_in),

        .io_hdmi_clk_p   (hdmi_clk_p),
        .io_hdmi_clk_n   (hdmi_clk_n),
        .io_hdmi_d0_p    (hdmi_d0_p),
        .io_hdmi_d0_n    (hdmi_d0_n),
        .io_hdmi_d1_p    (hdmi_d1_p),
        .io_hdmi_d1_n    (hdmi_d1_n),
        .io_hdmi_d2_p    (hdmi_d2_p),
        .io_hdmi_d2_n    (hdmi_d2_n),

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

        .io_consolOption (sw_option),
        .io_consolSelect (sw_select),
        .io_consolStart  (sw_start),
        .io_consolReset  (sw_reset),

        .io_sd_clk       (sd_clk),
        .io_sd_cmd       (sd_cmd),
        .io_sd_dat0      (sd_dat_in[0]),
        .io_sd_dat1      (sd_dat_in[1]),
        .io_sd_dat2      (sd_dat_in[2]),
        .io_sd_dat3      (sd_dat_3),
        .io_sd_cd        (sd_cd),

        .io_rm2_sck      (rm2_sck),
        .io_rm2_mosi     (rm2_mosi),
        .io_rm2_miso     (rm2_miso),
        .io_rm2_cs       (rm2_cs),
        .io_rm2_irq_n    (rm2_irq_n),
        .io_rm2_wifi_on  (rm2_wifi_on),
        .io_rm2_bt_on    (rm2_bt_on),

        .io_rp_sck       (rp_sck),
        .io_rp_mosi      (rp_mosi),
        .io_rp_csn       (rp_csn),
        .io_rp_miso      (rp_miso),

        .io_rp_gpio4_in  (rp_gpio4),
        .io_rp_gpio5_in  (rp_gpio5),
        .io_rp_gpio10_in (rp_gpio10),
        .io_rp_gpio11_in (rp_gpio11),
        .io_rp_gpio12_out(rp_gpio12),
        .io_rp_gpio13_in (rp_gpio13),
        .io_rp_gpio14_out(rp_gpio14),
        .io_rp_gpio15_out(rp_gpio15),
        .io_rp_gpio20_in (rp_gpio20),
        .io_rp_gpio21_in (rp_gpio21),
        .io_rp_gpio22_out(rp_gpio22),
        .io_rp_gpio23_in (rp_gpio23),
        .io_rp_gpio24_out(rp_gpio24),
        .io_rp_gpio25_out(rp_gpio25),

        .io_led_core     (led_core)
    );

endmodule
