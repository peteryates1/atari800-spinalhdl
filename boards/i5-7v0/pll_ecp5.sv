// ECP5 PLL: 25 MHz -> 37.5 MHz (Atari system clock, BRAM-only)
// 37.5 MHz / cycle_length=21 = 1.786 MHz 6502 (PAL target 1.79 MHz, -0.2%)
// Params from `ecppll --clkin 25 --clkout0 37.5`: Fpfd = 25/CLKI_DIV(2) = 12.5 MHz,
// Fvco = 12.5 * CLKFB_DIV(3) * CLKOP_DIV(16) = 600 MHz (legal 400-800), CLKOP = 600/16 = 37.5.
// (The earlier CLKI_DIV=1/CLKFB_DIV=24 form made nextpnr derive Fvco=9600 MHz -> a phantom
//  600 MHz constraint on the sys clock; these values model correctly.)
// c1 (CLKOS) is a 90-deg copy for a future SDRAM controller; unused in BRAM-only tops.
module PllAtari800 (
    input  wire inclk0,
    output wire c0,
    output wire c1,      // SDRAM clock (90 deg phase) — unused in BRAM-only
    output wire c2,      // unused
    output wire locked
);

    wire clkop, clkos, lock_w;

    (* FREQUENCY_PIN_CLKI="25" *)
    (* FREQUENCY_PIN_CLKOP="37.5" *)
    (* FREQUENCY_PIN_CLKOS="37.5" *)
    (* ICP_CURRENT="12" *) (* LPF_RESISTOR="8" *)
    EHXPLLL #(
        .PLLRST_ENA       ("DISABLED"),
        .INTFB_WAKE       ("DISABLED"),
        .STDBY_ENABLE      ("DISABLED"),
        .DPHASE_SOURCE     ("DISABLED"),
        .OUTDIVIDER_MUXA   ("DIVA"),
        .OUTDIVIDER_MUXB   ("DIVB"),
        .OUTDIVIDER_MUXC   ("DIVC"),
        .OUTDIVIDER_MUXD   ("DIVD"),
        .CLKI_DIV          (2),
        .CLKFB_DIV         (3),
        .CLKOP_ENABLE      ("ENABLED"),
        .CLKOP_DIV         (16),
        .CLKOP_CPHASE      (8),
        .CLKOP_FPHASE      (0),
        .CLKOS_ENABLE      ("ENABLED"),
        .CLKOS_DIV         (16),
        .CLKOS_CPHASE      (12),    // ~90 degree phase shift for SDRAM (future)
        .CLKOS_FPHASE      (0),
        .FEEDBK_PATH       ("CLKOP")
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
        .LOCK       (lock_w)
    );

    assign c0     = clkop;
    assign c1     = clkos;
    assign c2     = inclk0;
    assign locked = lock_w;

endmodule
