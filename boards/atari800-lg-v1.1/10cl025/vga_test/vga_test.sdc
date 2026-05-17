create_clock -name clk_in -period 20.000 [get_ports clk_in]
derive_pll_clocks
derive_clock_uncertainty

# VGA outputs to monitor — async to the FPGA's clocks.
set_false_path -from [all_clocks] -to [get_ports {vga_hs vga_vs vga_r[*] vga_g[*] vga_b[*]}]
