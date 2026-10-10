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

# TMDS, bank 34. OBUFDS P must sit on the P ball; the connector's + is on the
# N ball for CLK/TX1/TX2, which the RTL fixes by inverting those lanes.
set_property -dict {PACKAGE_PIN AA1 IOSTANDARD TMDS_33} [get_ports tmds_clk_p]
set_property -dict {PACKAGE_PIN AB1 IOSTANDARD TMDS_33} [get_ports tmds_clk_n]
set_property -dict {PACKAGE_PIN Y3  IOSTANDARD TMDS_33} [get_ports {tmds_d_p[0]}]
set_property -dict {PACKAGE_PIN AA3 IOSTANDARD TMDS_33} [get_ports {tmds_d_n[0]}]
set_property -dict {PACKAGE_PIN Y6  IOSTANDARD TMDS_33} [get_ports {tmds_d_p[1]}]
set_property -dict {PACKAGE_PIN AA6 IOSTANDARD TMDS_33} [get_ports {tmds_d_n[1]}]
set_property -dict {PACKAGE_PIN AA8 IOSTANDARD TMDS_33} [get_ports {tmds_d_p[2]}]
set_property -dict {PACKAGE_PIN AB8 IOSTANDARD TMDS_33} [get_ports {tmds_d_n[2]}]
