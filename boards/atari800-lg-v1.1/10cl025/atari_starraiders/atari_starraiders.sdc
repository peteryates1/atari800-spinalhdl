create_clock -name clk_in -period 20.000 [get_ports clk_in]
derive_pll_clocks
derive_clock_uncertainty

# UART async to clk_in.
set_false_path -from [get_ports {uart_rx}] -to [all_clocks]
set_false_path -from [all_clocks] -to [get_ports {uart_tx}]

# CH376T SPI + INT# are async (single CH376T_KB used; resync inside the
# generated core).
set_false_path -from [get_ports {ch376_kb_miso ch376_kb_int}] -to [all_clocks]
set_false_path -from [all_clocks] -to [get_ports {ch376_kb_sck ch376_kb_mosi ch376_kb_cs ch376_kb_rst}]

# All console switches / joysticks are async (resync in the SpinalHDL core).
set_false_path -from [get_ports {sw_start sw_select sw_option sw_reset}] -to [all_clocks]
set_false_path -from [get_ports {js1_up js1_down js1_left js1_right js1_trig}] -to [all_clocks]
set_false_path -from [get_ports {js2_up js2_down js2_left js2_right js2_trig}] -to [all_clocks]
