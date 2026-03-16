// ALTPLL for QMTECH EP4CGX150: 50 MHz -> 56.67 MHz (multiply=17, divide=15)
// c0: 56.67 MHz system clock
// c1: 56.67 MHz SDRAM clock (-3 ns phase shift for read timing)
// c2, c3: unused
module atari_pll (
    input  wire areset,
    input  wire inclk0,
    output wire c0,
    output wire c1,
    output wire c2,
    output wire c3,
    output wire locked
);

    wire [4:0] clk_int;

    altpll #(
        .bandwidth_type("AUTO"),
        .clk0_divide_by(15),
        .clk0_duty_cycle(50),
        .clk0_multiply_by(17),
        .clk0_phase_shift("0"),
        .clk1_divide_by(15),
        .clk1_duty_cycle(50),
        .clk1_multiply_by(17),
        .clk1_phase_shift("-3000"),
        .compensate_clock("CLK0"),
        .inclk0_input_frequency(20000),
        .intended_device_family("Cyclone IV GX"),
        .lpm_hint("CBX_MODULE_PREFIX=atari_pll"),
        .lpm_type("altpll"),
        .operation_mode("NORMAL"),
        .pll_type("AUTO"),
        .port_activeclock("PORT_UNUSED"),
        .port_areset("PORT_USED"),
        .port_clkbad0("PORT_UNUSED"),
        .port_clkbad1("PORT_UNUSED"),
        .port_clkloss("PORT_UNUSED"),
        .port_clkswitch("PORT_UNUSED"),
        .port_configupdate("PORT_UNUSED"),
        .port_fbin("PORT_UNUSED"),
        .port_inclk0("PORT_USED"),
        .port_inclk1("PORT_UNUSED"),
        .port_locked("PORT_USED"),
        .port_pfdena("PORT_UNUSED"),
        .port_phasecounterselect("PORT_UNUSED"),
        .port_phasedone("PORT_UNUSED"),
        .port_phasestep("PORT_UNUSED"),
        .port_phaseupdown("PORT_UNUSED"),
        .port_pllena("PORT_UNUSED"),
        .port_scanaclr("PORT_UNUSED"),
        .port_scanclk("PORT_UNUSED"),
        .port_scanclkena("PORT_UNUSED"),
        .port_scandata("PORT_UNUSED"),
        .port_scandataout("PORT_UNUSED"),
        .port_scandone("PORT_UNUSED"),
        .port_scanread("PORT_UNUSED"),
        .port_scanwrite("PORT_UNUSED"),
        .port_clk0("PORT_USED"),
        .port_clk1("PORT_USED"),
        .port_clk2("PORT_UNUSED"),
        .port_clk3("PORT_UNUSED"),
        .port_clk4("PORT_UNUSED"),
        .port_clk5("PORT_UNUSED"),
        .self_reset_on_loss_lock("OFF"),
        .using_fbmimicbidir_port("OFF"),
        .width_clock(5)
    ) altpll_component (
        .areset(areset),
        .inclk({1'b0, inclk0}),
        .clk(clk_int),
        .locked(locked),
        .activeclock(),
        .clkbad(),
        .clkena({6{1'b1}}),
        .clkloss(),
        .clkswitch(1'b0),
        .configupdate(1'b0),
        .enable0(),
        .enable1(),
        .extclk(),
        .extclkena({4{1'b1}}),
        .fbin(1'b1),
        .pfdena(1'b1),
        .phasecounterselect({4{1'b0}}),
        .phasedone(),
        .phasestep(1'b1),
        .phaseupdown(1'b1),
        .pllena(1'b1),
        .scanaclr(1'b0),
        .scanclk(1'b0),
        .scanclkena(1'b1),
        .scandata(1'b0),
        .scandataout(),
        .scandone(),
        .scanread(1'b0),
        .scanwrite(1'b0),
        .sclkout0(),
        .sclkout1(),
        .vcooverrange(),
        .vcounderrange()
    );

    assign c0 = clk_int[0];
    assign c1 = clk_int[1];
    assign c2 = 1'b0;
    assign c3 = 1'b0;

endmodule
