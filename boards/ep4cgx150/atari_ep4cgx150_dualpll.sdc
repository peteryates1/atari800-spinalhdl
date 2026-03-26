# Timing constraints for Atari 800 + JOP on QMTECH EP4CGX150
# Single PLL (atari_pll): c0=56.67 MHz (JOP + Atari), c1=56.67 MHz SDRAM
# VGA text overlay runs at sys clock — no separate pixel clock domain.

create_clock -period 20.000 -name clk_in [get_ports clk_in]

derive_pll_clocks
derive_clock_uncertainty
