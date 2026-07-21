create_clock -name clk_in -period 20.000 [get_ports clk_in]
derive_pll_clocks
derive_clock_uncertainty

# TMDS outputs are source-synchronous to the serialiser; treat the pins as
# non-timing-critical for this bring-up (ALTDDIO handles the DDR relationship).
set_false_path -from [all_clocks] -to [get_ports {hdmi_* led_core[*]}]
