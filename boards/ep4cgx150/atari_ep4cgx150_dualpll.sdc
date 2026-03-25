# Timing constraints for Atari 800 + JOP dual-PLL on QMTECH EP4CGX150
# PLL 1 (dram_pll): 50 MHz -> 80 MHz (JOP), 25 MHz (VGA text)
# PLL 2 (atari_pll): 50 MHz -> 56.67 MHz (Atari)

create_clock -period 20.000 -name clk_in [get_ports clk_in]

derive_pll_clocks
derive_clock_uncertainty

# The two PLL domains are asynchronous — CDC handled by BufferCC synchronizers.
# Declare them as separate clock groups so timing analyzer doesn't check cross-domain paths.
set_clock_groups -asynchronous \
  -group [get_clocks {*dram_pll*}] \
  -group [get_clocks {*atari_pll*}]
