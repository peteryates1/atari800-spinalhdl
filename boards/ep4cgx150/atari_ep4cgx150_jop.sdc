# Timing constraints for Atari 800 + JOP on QMTECH EP4CGX150 (BRAM-only)
# PLL input: 50 MHz; PLL outputs: 56.67 MHz system + 25 MHz VGA text

create_clock -period 20.000 -name clk_in [get_ports clk_in]

derive_pll_clocks
derive_clock_uncertainty
