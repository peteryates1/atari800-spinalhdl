create_clock -name clk_in -period 20.000 [get_ports clk_in]
derive_pll_clocks
derive_clock_uncertainty

# LED output is a slow visual indicator — not timing-critical.
set_false_path -from [all_clocks] -to [get_ports {led_core[*]}]
