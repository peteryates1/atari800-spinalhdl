# QMTECH EP4CGX150 onboard 50 MHz oscillator.
create_clock -name clk_in -period 20.000 [get_ports clk_in]
derive_clock_uncertainty

# UART async to clk_in.
set_false_path -from [get_ports {uart_rx}] -to [all_clocks]
set_false_path -from [all_clocks] -to [get_ports {uart_tx}]

# Async SRAM — no SRAM clock domain. Synchroniser handles dq_in. Treat all
# SRAM IO as async (we sequence via FSM cycles, not constrained timing).
set_false_path -from [all_clocks] -to [get_ports {sram_a[*] sram_dq[*] sram_ce_n sram_we_n sram_oe_n}]
set_false_path -from [get_ports {sram_dq[*]}] -to [all_clocks]
