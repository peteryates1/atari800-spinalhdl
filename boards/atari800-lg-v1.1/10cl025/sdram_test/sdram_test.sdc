create_clock -name clk_in    -period 20.000 [get_ports clk_in]
create_clock -name sdram_clk -period 20.000 [get_ports sdram_clk]
derive_clock_uncertainty

# UART async to clk_in.
set_false_path -from [get_ports {uart_rx}] -to [all_clocks]
set_false_path -from [all_clocks] -to [get_ports {uart_tx}]
