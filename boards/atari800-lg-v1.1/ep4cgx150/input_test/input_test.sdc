create_clock -name clk_in -period 20.000 [get_ports clk_in]
derive_clock_uncertainty

# UART async to clk_in.
set_false_path -from [get_ports {uart_rx}] -to [all_clocks]
set_false_path -from [all_clocks] -to [get_ports {uart_tx}]

# Inputs (switches, joysticks) are async — gated by 2-FF sync.
set_false_path -from [get_ports {sw_start sw_select sw_option sw_reset}] -to [all_clocks]
set_false_path -from [get_ports {js1_up js1_down js1_left js1_right js1_trig}] -to [all_clocks]
set_false_path -from [get_ports {js2_up js2_down js2_left js2_right js2_trig}] -to [all_clocks]
