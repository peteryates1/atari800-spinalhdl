# Timing constraints for Atari 800 on QMTECH EP4CGX150
# PLL input: 50 MHz; PLL outputs: 56.67 MHz system + 56.67 MHz -3ns SDRAM

create_clock -period 20.000 -name clk_in [get_ports clk_in]

derive_pll_clocks
derive_clock_uncertainty

# SDRAM timing (W9825G6JH6, CAS=3, -6 speed grade)
# Clocked from c1 (56.67 MHz with -3 ns phase shift)
# Input: data valid 2.5-6.4 ns after SDRAM clock edge
set_input_delay  -clock { pll|altpll_component|auto_generated|pll1|clk[1] } -max  6.4 [get_ports {sdram_dq[*]}]
set_input_delay  -clock { pll|altpll_component|auto_generated|pll1|clk[1] } -min  2.5 [get_ports {sdram_dq[*]}]

# Output: SDRAM setup 1.5 ns, hold 0.8 ns before/after SDRAM clock edge
set_output_delay -clock { pll|altpll_component|auto_generated|pll1|clk[1] } -max  1.5 [get_ports {sdram_addr[*] sdram_ba[*] sdram_cas_n sdram_cs_n sdram_dq[*] sdram_dqmh sdram_dqml sdram_ras_n sdram_we_n}]
set_output_delay -clock { pll|altpll_component|auto_generated|pll1|clk[1] } -min -0.8 [get_ports {sdram_addr[*] sdram_ba[*] sdram_cas_n sdram_cs_n sdram_dq[*] sdram_dqmh sdram_dqml sdram_ras_n sdram_we_n}]
