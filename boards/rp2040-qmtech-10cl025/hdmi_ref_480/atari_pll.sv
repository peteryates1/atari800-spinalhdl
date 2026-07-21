// ALTPLL for Cyclone 10 LP — 3-output for ATARI-800-RP2040-STAMP-HDMI-LG.
// Single VCO at 50 MHz × 15 = 750 MHz.
//   c0: 57.69  MHz (750/13) — Atari system clock (1.8% faster than V1.1's 56.67)
//   c1: 25.00  MHz (750/30) — HDMI pixel clock (standard VGA 640×480@60)
//   c2: 125.00 MHz (750/6)  — HDMI TMDS clock (5× pixel for DDR 10:1 serialize)
//   c3: unused
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
        // c0 → 57.69 MHz system clock
        .clk0_divide_by(13),
        .clk0_duty_cycle(50),
        .clk0_multiply_by(15),
        .clk0_phase_shift("0"),
        // c1 → 25 MHz HDMI pixel clock
        .clk1_divide_by(30),
        .clk1_duty_cycle(50),
        .clk1_multiply_by(15),
        .clk1_phase_shift("0"),
        // c2 → 125 MHz HDMI TMDS clock
        .clk2_divide_by(6),
        .clk2_duty_cycle(50),
        .clk2_multiply_by(15),
        .clk2_phase_shift("0"),
        .compensate_clock("CLK0"),
        .inclk0_input_frequency(20000),
        .intended_device_family("Cyclone 10 LP"),
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
        .port_clk2("PORT_USED"),
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
    assign c2 = clk_int[2];
    assign c3 = 1'b0;

endmodule
