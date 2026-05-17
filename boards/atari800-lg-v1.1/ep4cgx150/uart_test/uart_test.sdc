# QMTECH EP4CGX150 onboard 50 MHz oscillator.
create_clock -name clk_in -period 20.000 [get_ports clk_in]
derive_clock_uncertainty

# UART is asynchronous to clk_in — don't time it.
set_false_path -from [get_ports {uart_rx}] -to [all_clocks]
set_false_path -from [all_clocks] -to [get_ports {uart_tx}]
