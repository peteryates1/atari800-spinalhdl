# Colorlight i9+ (XC7A50T-FGG484) — 1080p60 HDMI serializer timing probe.
# 25 MHz osc on K4; the four bank-34 TMDS pairs (P/N per README pin map).
set_property CFGBVS VCCO [current_design]
set_property CONFIG_VOLTAGE 3.3 [current_design]

set_property -dict {PACKAGE_PIN K4 IOSTANDARD LVCMOS33} [get_ports clk25]
create_clock -period 40.000 -name clk25 [get_ports clk25]

# TMDS (bank 34, TMDS_33). P on the master ball, N on its diff partner.
set_property -dict {PACKAGE_PIN AA1 IOSTANDARD TMDS_33} [get_ports tmds_clk_p]
set_property -dict {PACKAGE_PIN AB1 IOSTANDARD TMDS_33} [get_ports tmds_clk_n]
set_property -dict {PACKAGE_PIN Y3  IOSTANDARD TMDS_33} [get_ports {tmds_d_p[0]}]
set_property -dict {PACKAGE_PIN AA3 IOSTANDARD TMDS_33} [get_ports {tmds_d_n[0]}]
set_property -dict {PACKAGE_PIN Y6  IOSTANDARD TMDS_33} [get_ports {tmds_d_p[1]}]
set_property -dict {PACKAGE_PIN AA6 IOSTANDARD TMDS_33} [get_ports {tmds_d_n[1]}]
set_property -dict {PACKAGE_PIN AA8 IOSTANDARD TMDS_33} [get_ports {tmds_d_p[2]}]
set_property -dict {PACKAGE_PIN AB8 IOSTANDARD TMDS_33} [get_ports {tmds_d_n[2]}]
