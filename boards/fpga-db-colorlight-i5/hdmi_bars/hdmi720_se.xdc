# 720p pseudo-diff (hdmi720_se_top.v): the pins of hdmi_se.xdc plus the gearbox CDC.
# fpga-db-colorlight-i5 + i9+ (XC7A50T-FGG484): 720p HDMI colour bars.
# Balls from Netlist_fpga-db-colorlight-i5_2026-10-10.enet.
set_property CFGBVS VCCO [current_design]
set_property CONFIG_VOLTAGE 3.3 [current_design]
# Sixteen RP2040 GPIOs land on FPGA balls: never pull against them.
set_property BITSTREAM.CONFIG.UNUSEDPIN PULLNONE [current_design]

set_property -dict {PACKAGE_PIN K4 IOSTANDARD LVCMOS33} [get_ports clk25]
create_clock -period 40.000 -name clk25 [get_ports clk25]

set_property -dict {PACKAGE_PIN M1 IOSTANDARD LVCMOS33} [get_ports {led[0]}]
set_property -dict {PACKAGE_PIN L1 IOSTANDARD LVCMOS33} [get_ports {led[1]}]
set_property -dict {PACKAGE_PIN K1 IOSTANDARD LVCMOS33} [get_ports {led[2]}]
set_property -dict {PACKAGE_PIN J1 IOSTANDARD LVCMOS33} [get_ports {led[3]}]
set_property -dict {PACKAGE_PIN H3 IOSTANDARD LVCMOS33} [get_ports {led[4]}]
set_property -dict {PACKAGE_PIN U1 IOSTANDARD LVCMOS33} [get_ports {led[5]}]
set_property -dict {PACKAGE_PIN V5 IOSTANDARD LVCMOS33} [get_ports {led[6]}]
set_property -dict {PACKAGE_PIN U5 IOSTANDARD LVCMOS33} [get_ports {led[7]}]

# HDMI, bank 34, PSEUDO-DIFFERENTIAL (see hdmi_se_top.v): the ports are named by
# the CONNECTOR's polarity. Netlist: DVI_CLK_P->AB1, TX0_P->Y3, TX1_P->AA6, TX2_P->AB8.
set_property -dict {PACKAGE_PIN AB1 IOSTANDARD LVCMOS33 SLEW FAST DRIVE 16} [get_ports hdmi_clk_p]
set_property -dict {PACKAGE_PIN AA1 IOSTANDARD LVCMOS33 SLEW FAST DRIVE 16} [get_ports hdmi_clk_n]
set_property -dict {PACKAGE_PIN Y3  IOSTANDARD LVCMOS33 SLEW FAST DRIVE 16} [get_ports {hdmi_d_p[0]}]
set_property -dict {PACKAGE_PIN AA3 IOSTANDARD LVCMOS33 SLEW FAST DRIVE 16} [get_ports {hdmi_d_n[0]}]
set_property -dict {PACKAGE_PIN AA6 IOSTANDARD LVCMOS33 SLEW FAST DRIVE 16} [get_ports {hdmi_d_p[1]}]
set_property -dict {PACKAGE_PIN Y6  IOSTANDARD LVCMOS33 SLEW FAST DRIVE 16} [get_ports {hdmi_d_n[1]}]
set_property -dict {PACKAGE_PIN AB8 IOSTANDARD LVCMOS33 SLEW FAST DRIVE 16} [get_ports {hdmi_d_p[2]}]
set_property -dict {PACKAGE_PIN AA8 IOSTANDARD LVCMOS33 SLEW FAST DRIVE 16} [get_ports {hdmi_d_n[2]}]

# Gearbox CDC, pixel -> word clock (same MMCM, so Vivado would otherwise time
# it edge-to-edge at the tightest 1.35 ns pair). The word side reads a buffer
# no sooner than two word clocks (21.6 ns) after `wsel` flips, and the buffer
# then stays put for another 53.9 ns, so 8 ns of datapath is safe.
set_max_delay -datapath_only 8.0 \
    -from [get_clocks -of_objects [get_pins mmcm/CLKOUT0]] \
    -to   [get_clocks -of_objects [get_pins mmcm/CLKOUT1]]
