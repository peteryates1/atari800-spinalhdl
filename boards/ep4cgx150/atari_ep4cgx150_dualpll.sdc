# Timing constraints for Atari 800 + JOP on QMTECH EP4CGX150
# Single PLL (atari_pll): c0=56.67 MHz (JOP + Atari), c1=56.67 MHz SDRAM, c3=25 MHz VGA text

create_clock -period 20.000 -name clk_in [get_ports clk_in]

derive_pll_clocks
derive_clock_uncertainty

# VGA text (25 MHz, clk[3]) and main domain (56.67 MHz, clk[0]) are asynchronous —
# CDC handled by BufferCC synchronizers in the Atari area.
set_clock_groups -asynchronous \
  -group [get_clocks {*pll*clk\[3\]*}] \
  -group [get_clocks {*pll*clk\[0\]*}]
