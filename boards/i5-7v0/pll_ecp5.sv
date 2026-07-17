// ECP5 PLL: 25 MHz -> 37.5 MHz (Atari system clock, BRAM-only)
// 37.5 MHz / cycle_length=21 = 1.786 MHz 6502 (NTSC target 1.79 MHz).
// EXACT `ecppll --clkin 25 --clkout0 37.5` output (guaranteed to lock): Fpfd = 25/2 =
// 12.5 MHz, Fvco = 12.5 * 3 * 16 = 600 MHz (legal 400-800), CLKOP = 600/16 = 37.5.
// (The earlier CLKI_DIV=1/CLKFB_DIV=24 form gave Fvco=9600 MHz -> ILLEGAL -> the PLL
//  would not lock on silicon, freezing the core in reset.)
// c1/c2 are unused copies (BRAM-only tops don't use SDRAM).
module PllAtari800 (
    input  wire inclk0,
    output wire c0,
    output wire c1,      // unused
    output wire c2,      // unused
    output wire locked
);
    wire clkop;

    (* FREQUENCY_PIN_CLKI="25" *)
    (* FREQUENCY_PIN_CLKOP="37.5" *)
    (* ICP_CURRENT="12" *) (* LPF_RESISTOR="8" *) (* MFG_ENABLE_FILTEROPAMP="1" *) (* MFG_GMCREF_SEL="2" *)
    EHXPLLL #(
        .PLLRST_ENA        ("DISABLED"),
        .INTFB_WAKE        ("DISABLED"),
        .STDBY_ENABLE      ("DISABLED"),
        .DPHASE_SOURCE     ("DISABLED"),
        .OUTDIVIDER_MUXA   ("DIVA"),
        .OUTDIVIDER_MUXB   ("DIVB"),
        .OUTDIVIDER_MUXC   ("DIVC"),
        .OUTDIVIDER_MUXD   ("DIVD"),
        .CLKI_DIV          (2),
        .CLKOP_ENABLE      ("ENABLED"),
        .CLKOP_DIV         (16),
        .CLKOP_CPHASE      (8),
        .CLKOP_FPHASE      (0),
        .FEEDBK_PATH       ("CLKOP"),
        .CLKFB_DIV         (3)
    ) pll_i (
        .RST         (1'b0),
        .STDBY       (1'b0),
        .CLKI        (inclk0),
        .CLKOP       (clkop),
        .CLKFB       (clkop),
        .CLKINTFB    (),
        .PHASESEL0   (1'b0),
        .PHASESEL1   (1'b0),
        .PHASEDIR    (1'b1),
        .PHASESTEP   (1'b1),
        .PHASELOADREG(1'b1),
        .PLLWAKESYNC (1'b0),
        .ENCLKOP     (1'b0),
        .LOCK        (locked)
    );

    assign c0 = clkop;
    assign c1 = clkop;
    assign c2 = clkop;
endmodule
