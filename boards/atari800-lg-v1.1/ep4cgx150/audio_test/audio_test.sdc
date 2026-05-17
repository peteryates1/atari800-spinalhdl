create_clock -name clk_in -period 20.000 [get_ports clk_in]
derive_clock_uncertainty

# Audio outputs go through external RC filters — async to clk_in.
set_false_path -from [all_clocks] -to [get_ports {audio_l audio_r}]
