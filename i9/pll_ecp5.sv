// ECP5 PLL: 25 MHz -> 56.67 MHz (Atari system clock)
// Replaces PllAtari800 (Altera) for Colorlight i5
// CLKI=25MHz, CLKOP=56.67MHz: CLKI_DIV=11, CLKFB_DIV=25, CLKOP_DIV=11
module PllAtari800 (
    input  wire inclk0,
    output wire c0,
    output wire c1,      // SDRAM clock (phase shifted)
    output wire c2,      // VGA text clock (~25 MHz = CLKI passthrough)
    output wire locked
);

    wire clkop, clkos, lock_w;

    (* FREQUENCY_PIN_CLKI="25" *)
    (* FREQUENCY_PIN_CLKOP="56.818" *)
    EHXPLLL #(
        .PLLRST_ENA       ("DISABLED"),
        .INTFB_WAKE       ("DISABLED"),
        .STDBY_ENABLE      ("DISABLED"),
        .DPHASE_SOURCE     ("DISABLED"),
        .OUTDIVIDER_MUXA   ("DIVA"),
        .OUTDIVIDER_MUXB   ("DIVB"),
        .OUTDIVIDER_MUXC   ("DIVC"),
        .OUTDIVIDER_MUXD   ("DIVD"),
        .CLKI_DIV          (11),
        .CLKFB_DIV         (25),
        .CLKOP_ENABLE      ("ENABLED"),
        .CLKOP_DIV         (11),
        .CLKOP_CPHASE      (5),
        .CLKOP_FPHASE      (0),
        .CLKOS_ENABLE      ("ENABLED"),
        .CLKOS_DIV         (11),
        .CLKOS_CPHASE      (8),     // ~130 degree phase shift for SDRAM
        .CLKOS_FPHASE      (0),
        .FEEDBK_PATH       ("CLKOP"),
        .CLKOP_TRIM_POL    ("FALLING"),
        .CLKOP_TRIM_DELAY  (0),
        .CLKOS_TRIM_POL    ("FALLING"),
        .CLKOS_TRIM_DELAY  (0)
    ) pll_inst (
        .RST        (1'b0),
        .STDBY      (1'b0),
        .CLKI       (inclk0),
        .CLKOP      (clkop),
        .CLKOS      (clkos),
        .CLKFB      (clkop),
        .CLKINTFB   (),
        .PHASESEL0  (1'b0),
        .PHASESEL1  (1'b0),
        .PHASEDIR   (1'b0),
        .PHASESTEP  (1'b0),
        .PHASELOADREG (1'b0),
        .PLLWAKESYNC (1'b0),
        .ENCLKOP    (1'b0),
        .ENCLKOS    (1'b0),
        .ENCLKOS2   (1'b0),
        .ENCLKOS3   (1'b0),
        .LOCK       (lock_w)
    );

    assign c0     = clkop;
    assign c1     = clkos;
    assign c2     = inclk0;  // 25 MHz passthrough for VGA text
    assign locked = lock_w;

endmodule
