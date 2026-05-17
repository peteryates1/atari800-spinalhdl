# QMTECH EP4CGX150 onboard 50 MHz oscillator.
create_clock -name clk_in -period 20.000 [get_ports clk_in]
derive_clock_uncertainty
