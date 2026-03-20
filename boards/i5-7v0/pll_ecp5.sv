// ECP5 PLL: 25 MHz -> 37.5 MHz (Atari system clock, BRAM-only)
// 37.5 MHz / cycle_length=21 = 1.786 MHz 6502 (PAL target 1.79 MHz, -0.2%)
// Fvco = 25 * 24 / 1 = 600 MHz, Fout = 600 / 16 = 37.5 MHz
module PllAtari800 (
    input  wire inclk0,
    output wire c0,
    output wire c1,      // SDRAM clock (phase shifted) — unused in BRAM-only
    output wire c2,      // unused
    output wire locked
);

    wire clkop, clkos, lock_w;

    (* FREQUENCY_PIN_CLKI="25" *)
    (* FREQUENCY_PIN_CLKOP="37.5" *)
    EHXPLLL #(
        .PLLRST_ENA       ("DISABLED"),
        .INTFB_WAKE       ("DISABLED"),
        .STDBY_ENABLE      ("DISABLED"),
        .DPHASE_SOURCE     ("DISABLED"),
        .OUTDIVIDER_MUXA   ("DIVA"),
        .OUTDIVIDER_MUXB   ("DIVB"),
        .OUTDIVIDER_MUXC   ("DIVC"),
        .OUTDIVIDER_MUXD   ("DIVD"),
        .CLKI_DIV          (1),
        .CLKFB_DIV         (24),
        .CLKOP_ENABLE      ("ENABLED"),
        .CLKOP_DIV         (16),
        .CLKOP_CPHASE      (7),
        .CLKOP_FPHASE      (0),
        .CLKOS_ENABLE      ("ENABLED"),
        .CLKOS_DIV         (16),
        .CLKOS_CPHASE      (11),    // ~90 degree phase shift for SDRAM (future)
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
    assign c2     = inclk0;
    assign locked = lock_w;

endmodule
