# Timing constraints for Atari 800 BRAM-only on QMTECH EP4CGX150
# PLL input: 50 MHz; PLL output: 56.67 MHz system clock
# SDRAM not used by Atari core — constraints will be added when JOP uses SDRAM

create_clock -period 20.000 -name clk_in [get_ports clk_in]

derive_pll_clocks
derive_clock_uncertainty
