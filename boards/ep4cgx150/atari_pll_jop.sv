// atari_pll_jop.sv — ALTPLL for EP4CGX150 JOP build
// c0: 56.67 MHz system clock  (50 MHz × 17 / 15)
// c1: 56.67 MHz SDRAM clock   (-3 ns phase shift for W9825G6JH6)
// c2: 25 MHz VGA text pixel clock (50 MHz ÷ 2)

module atari_pll_jop (
    input  wire areset,
    input  wire inclk0,
    output wire c0,
    output wire c1,
    output wire c2,
    output wire locked
);

    wire [4:0] clk_int;

    altpll #(
        // c0: 56.67 MHz system clock
        .clk0_divide_by         (15),
        .clk0_multiply_by       (17),
        .clk0_phase_shift       ("0"),
        .clk0_duty_cycle        (50),

        // c1: 56.67 MHz SDRAM clock (-3 ns phase shift)
        .clk1_divide_by         (15),
        .clk1_multiply_by       (17),
        .clk1_phase_shift       ("-3000"),
        .clk1_duty_cycle        (50),

        // c2: 25 MHz VGA text pixel clock (50 MHz / 2)
        .clk2_divide_by         (2),
        .clk2_multiply_by       (1),
        .clk2_phase_shift       ("0"),
        .clk2_duty_cycle        (50),

        // Input clock: 50 MHz (period = 20000 ps)
        .inclk0_input_frequency (20000),
        .intended_device_family ("Cyclone IV GX"),
        .operation_mode         ("NORMAL"),
        .pll_type               ("AUTO"),
        .compensate_clock       ("CLK0"),

        // Port usage
        .port_areset            ("PORT_USED"),
        .port_inclk0            ("PORT_USED"),
        .port_locked            ("PORT_USED"),
        .port_clk0              ("PORT_USED"),
        .port_clk1              ("PORT_USED"),
        .port_clk2              ("PORT_USED"),
        .port_clk3              ("PORT_UNUSED"),
        .port_clk4              ("PORT_UNUSED"),
        .port_inclk1            ("PORT_UNUSED"),
        .port_phasecounterselect("PORT_UNUSED"),
        .port_phasedone         ("PORT_UNUSED"),
        .port_phasestep         ("PORT_UNUSED"),
        .port_phaseupdown       ("PORT_UNUSED"),
        .port_scanclk           ("PORT_UNUSED"),
        .port_scanclkena        ("PORT_UNUSED"),
        .port_scandata          ("PORT_UNUSED"),
        .port_scandataout       ("PORT_UNUSED"),
        .port_scandone          ("PORT_UNUSED"),
        .port_sclkout0          ("PORT_UNUSED"),
        .port_sclkout1          ("PORT_UNUSED"),
        .port_activeclock       ("PORT_UNUSED"),
        .port_clkbad0           ("PORT_UNUSED"),
        .port_clkbad1           ("PORT_UNUSED"),
        .port_clkloss           ("PORT_UNUSED"),
        .port_clkswitch         ("PORT_UNUSED"),
        .port_configupdate      ("PORT_UNUSED"),
        .port_enable0           ("PORT_UNUSED"),
        .port_enable1           ("PORT_UNUSED"),
        .port_extclk0           ("PORT_UNUSED"),
        .port_extclk1           ("PORT_UNUSED"),
        .port_extclk2           ("PORT_UNUSED"),
        .port_extclk3           ("PORT_UNUSED"),
        .port_extclkena0        ("PORT_UNUSED"),
        .port_extclkena1        ("PORT_UNUSED"),
        .port_extclkena2        ("PORT_UNUSED"),
        .port_extclkena3        ("PORT_UNUSED"),
        .port_fbin              ("PORT_UNUSED"),
        .self_reset_on_loss_lock("OFF"),
        .valid_lock_multiplier  (1)
    ) altpll_component (
        .areset (areset),
        .inclk  ({1'b0, inclk0}),
        .clk    (clk_int),
        .locked (locked),
        .activeclock    (),
        .clkbad         (),
        .clkloss        (),
        .clkswitch      (1'b0),
        .configupdate   (1'b0),
        .enable0        (),
        .enable1        (),
        .extclk         (),
        .extclkena      (4'b1111),
        .fbin           (1'b1),
        .pfdena         (1'b1),
        .phasecounterselect (4'b0),
        .phasedone      (),
        .phasestep      (1'b0),
        .phaseupdown    (1'b0),
        .scanclk        (1'b0),
        .scanclkena     (1'b1),
        .scandata       (1'b0),
        .scandataout    (),
        .scandone       (),
        .sclkout0       (),
        .sclkout1       ()
    );

    assign c0 = clk_int[0];   // 56.67 MHz system
    assign c1 = clk_int[1];   // 56.67 MHz SDRAM (-3 ns)
    assign c2 = clk_int[2];   // 25 MHz VGA text

endmodule
