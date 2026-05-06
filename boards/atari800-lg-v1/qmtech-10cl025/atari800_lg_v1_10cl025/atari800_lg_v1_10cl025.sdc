# Timing constraints for Atari 800 BRAM on ATARI-800-LG-V1 + QMTECH 10CL025
# PLL: 50 MHz → 56.67 MHz (x17/÷15)

create_clock -period 20.000 -name clk_in [get_ports io_clk_in]

derive_pll_clocks
derive_clock_uncertainty
