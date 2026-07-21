# SDRAM BIST at the main design's clocking - constraints mirror
# atari_starraiders.sdc (same PLL, same I/O timing, same MCP).
create_clock -name clk_in -period 20.000 [get_ports clk_in]
derive_pll_clocks
derive_clock_uncertainty

set_false_path -from [get_ports {rp_sck rp_mosi rp_csn}] -to [all_clocks]
set_false_path -from [all_clocks] -to [get_ports {rp_miso}]
set_false_path -from [all_clocks] -to [get_ports {led_core[*]}]

# SDRAM interface timing (W9825G6KH-6): chip clock pin from PLL c2 (-2400 ps).
create_generated_clock -name sdram_clk_pin \
    -source [get_pins {*pll|altpll_component|auto_generated|pll1|clk[2]}] \
    [get_ports {sdram_clk}]
set_input_delay  -clock sdram_clk_pin -max 6.3 [get_ports {sdram_dq[*]}]
set_input_delay  -clock sdram_clk_pin -min 2.5 [get_ports {sdram_dq[*]}]
set_output_delay -clock sdram_clk_pin -max 1.8 \
    [get_ports {sdram_dq[*] sdram_addr[*] sdram_ba[*] sdram_dqm[*] sdram_rasn sdram_casn sdram_wen sdram_csn sdram_cke}]
set_output_delay -clock sdram_clk_pin -min -1.0 \
    [get_ports {sdram_dq[*] sdram_addr[*] sdram_ba[*] sdram_dqm[*] sdram_rasn sdram_casn sdram_wen sdram_csn sdram_cke}]

set_multicycle_path -setup 2 -from [get_clocks {sdram_clk_pin}] \
    -to [get_clocks {*pll|altpll_component|auto_generated|pll1|clk[1]}]
set_multicycle_path -hold 1 -from [get_clocks {sdram_clk_pin}] \
    -to [get_clocks {*pll|altpll_component|auto_generated|pll1|clk[1]}]
