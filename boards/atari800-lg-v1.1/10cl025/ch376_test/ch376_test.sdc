create_clock -name clk_in -period 20.000 [get_ports clk_in]
derive_pll_clocks
derive_clock_uncertainty

set_false_path -from [get_ports {uart_rx}] -to [all_clocks]
set_false_path -from [all_clocks] -to [get_ports {uart_tx}]
set_false_path -from [get_ports {ch376_kb_miso ch376_sd_miso ch376_kb_int ch376_sd_int}] -to [all_clocks]
set_false_path -from [all_clocks] -to [get_ports {ch376_kb_sck ch376_kb_mosi ch376_kb_cs ch376_kb_rst}]
set_false_path -from [all_clocks] -to [get_ports {ch376_sd_sck ch376_sd_mosi ch376_sd_cs ch376_sd_rst}]
