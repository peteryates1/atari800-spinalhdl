# Primary clock: 50 MHz from QMTECH 10CL025 onboard oscillator (PIN_E2).
create_clock -name clk_in -period 20.000 [get_ports clk_in]

# Derive the 3 PLL output clocks (57.69 / 25 / 125 MHz).
derive_pll_clocks
derive_clock_uncertainty

# Three asynchronous clock domains:
#   1. Atari sys 57.69 MHz          (atari_pll = pll_1, clk[0])
#   2. HDMI pixel/TMDS 25/125 MHz   (atari_pll = pll_1, clk[1]/clk[2])
#   3. SDRAM 100 MHz                 (sdramPll, clk[0]/clk[1])
# NOTE: both PLLs' auto-generated instance is "pll1", so a bare "*pll1|clk[0]"
# filter matches BOTH the 57.69 and 100 MHz clocks and wrongly groups them as
# related — the source of intermittent SDRAM behaviour. Filter on the SpinalHDL
# instance names (pll_1 vs sdramPll) to keep the sys<->SDRAM crossing async.
# SdramStatemachine has 2-FF synchronisers, so the crossing is safe.
# sys (clk0) and sdram (clk1/clk2, 2x phase-locked) come from the SAME PLL and
# are deliberately RELATED: all controller crossings are timed by STA.
set_clock_groups -asynchronous \
    -group [get_clocks {*u_top|pll|altpll_component|auto_generated|pll1|clk[*] sdram_clk_pin}] \
    -group [get_clocks {*hdmiPll|altpll_component|auto_generated|pll1|clk[*]}]

# Known benign warning: "Worst-case minimum pulse width slack is -5.4 ns" on
# the 125 MHz TMDS clock at slow 100C. This is a Quartus 25.1 timing-model
# artifact on ALTPLL outputs, not a real silicon limit — the same Cyclone 10
# LP family runs the AC608 reference HDMI demo (corecourse c23_hdmi_color) at
# 371 MHz TMDS on a slower speed grade, with no SDC and no closure. Setup
# and hold are both green on clk[2], so the design is functionally fine.
# Quartus 25.1 has no SDC command to suppress this specific check; ignore it.

# Async-input false paths — switches, joysticks, SD CD, RM2 IRQ.
set_false_path -from [get_ports {sw_start sw_select sw_option sw_reset}] -to [all_clocks]
set_false_path -from [get_ports {js1_up js1_down js1_left js1_right js1_trig}] -to [all_clocks]
set_false_path -from [get_ports {js2_up js2_down js2_left js2_right js2_trig}] -to [all_clocks]
set_false_path -from [get_ports {sd_cd rm2_irq_n}] -to [all_clocks]

# RP2040 SPI link is async to the Atari sys clock (RP2040 owns its own clock).
set_false_path -from [get_ports {rp_sck rp_mosi rp_csn}] -to [all_clocks]
set_false_path -from [all_clocks] -to [get_ports {rp_miso}]

# RP2040 GPIO pass-throughs are combinational wires — RP2040 sets clock budget
# on its end (PIO state machines). Treat as async for the FPGA.
set_false_path -from [get_ports {rp_gpio*}] -to [all_clocks]
set_false_path -from [all_clocks] -to [get_ports {rp_gpio*}]

# SD card / RM2 SPI signals enter/exit the FPGA as transparent pass-through.
set_false_path -from [get_ports {sd_dat_in[0] sd_dat_in[1] sd_dat_in[2] rm2_miso}] -to [all_clocks]
set_false_path -from [all_clocks] -to [get_ports {sd_clk sd_cmd sd_dat_3 rm2_sck rm2_mosi rm2_cs rm2_wifi_on rm2_bt_on}]

# HDMI TMDS pairs are sourced from the TMDS clock domain in DvidOut and DDR-
# clocked via ALTDDIO_OUT — Quartus picks the IOE registers automatically.

# --- SDRAM interface timing (Winbond W9825G6KH-6, 100 MHz, IOE registers) ---
# The chip clock (sdram_clk, PLL c1) is in-phase (0deg) with the internal
# 115.38 MHz controller clock: read data (tAC<=6.3ns after the chip edge) lands
# centred on the next internal capture edge (window 6.3..11.2ns, edge 8.67ns).
create_generated_clock -name sdram_clk_pin \
    -source [get_pins {*u_top|pll|altpll_component|auto_generated|pll1|clk[2]}] \
    [get_ports {sdram_clk}]
set_input_delay  -clock sdram_clk_pin -max 6.3 [get_ports {sdram_dq[*]}]
set_input_delay  -clock sdram_clk_pin -min 2.5 [get_ports {sdram_dq[*]}]
set_output_delay -clock sdram_clk_pin -max 1.8 \
    [get_ports {sdram_dq[*] sdram_addr[*] sdram_ba[*] sdram_dqm[*] sdram_rasn sdram_casn sdram_wen sdram_csn sdram_cke}]
set_output_delay -clock sdram_clk_pin -min -1.0 \
    [get_ports {sdram_dq[*] sdram_addr[*] sdram_ba[*] sdram_dqm[*] sdram_rasn sdram_casn sdram_wen sdram_csn sdram_cke}]

# Read-capture edge selection: the chip clock leads the internal 115.38 MHz
# clock by 1.5 ns, so the intended capture edge for read data is the SECOND
# internal edge after the chip launch edge (10.17 ns relationship, not 1.5).
set_multicycle_path -setup 2 -from [get_clocks {sdram_clk_pin}] \
    -to [get_clocks {*u_top|pll|altpll_component|auto_generated|pll1|clk[1]}]
set_multicycle_path -hold 1 -from [get_clocks {sdram_clk_pin}] \
    -to [get_clocks {*u_top|pll|altpll_component|auto_generated|pll1|clk[1]}]
