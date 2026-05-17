create_clock -name clk_in -period 20.000 [get_ports clk_in]
derive_clock_uncertainty

set_false_path -from [get_ports {uart_rx adc_miso}] -to [all_clocks]
set_false_path -from [all_clocks] -to [get_ports {uart_tx adc_sck adc_mosi adc_cs}]
