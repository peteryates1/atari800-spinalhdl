set_property CFGBVS VCCO [current_design]
set_property CONFIG_VOLTAGE 3.3 [current_design]
set_property BITSTREAM.CONFIG.UNUSEDPIN PULLNONE [current_design]
set_property -dict {PACKAGE_PIN K4 IOSTANDARD LVCMOS33} [get_ports clk25]
create_clock -period 40.000 -name clk25 [get_ports clk25]
# Conductors by CONNECTOR pin (netlist 2026-10-10, DVI_* -> cap -> ball).
set_property -dict {PACKAGE_PIN Y3  IOSTANDARD LVCMOS33} [get_ports {line[0]}] ;# D0+  HDMI pin 7
set_property -dict {PACKAGE_PIN AA3 IOSTANDARD LVCMOS33} [get_ports {line[1]}] ;# D0-  pin 9
set_property -dict {PACKAGE_PIN AA6 IOSTANDARD LVCMOS33} [get_ports {line[2]}] ;# D1+  pin 4
set_property -dict {PACKAGE_PIN Y6  IOSTANDARD LVCMOS33} [get_ports {line[3]}] ;# D1-  pin 6
set_property -dict {PACKAGE_PIN AB8 IOSTANDARD LVCMOS33} [get_ports {line[4]}] ;# D2+  pin 1
set_property -dict {PACKAGE_PIN AA8 IOSTANDARD LVCMOS33} [get_ports {line[5]}] ;# D2-  pin 3
set_property -dict {PACKAGE_PIN AB1 IOSTANDARD LVCMOS33} [get_ports {line[6]}] ;# CLK+ pin 10
set_property -dict {PACKAGE_PIN AA1 IOSTANDARD LVCMOS33} [get_ports {line[7]}] ;# CLK- pin 12
set_property -dict {PACKAGE_PIN M1 IOSTANDARD LVCMOS33} [get_ports {led[0]}]
set_property -dict {PACKAGE_PIN L1 IOSTANDARD LVCMOS33} [get_ports {led[1]}]
set_property -dict {PACKAGE_PIN K1 IOSTANDARD LVCMOS33} [get_ports {led[2]}]
set_property -dict {PACKAGE_PIN J1 IOSTANDARD LVCMOS33} [get_ports {led[3]}]
set_property -dict {PACKAGE_PIN H3 IOSTANDARD LVCMOS33} [get_ports {led[4]}]
set_property -dict {PACKAGE_PIN U1 IOSTANDARD LVCMOS33} [get_ports {led[5]}]
set_property -dict {PACKAGE_PIN V5 IOSTANDARD LVCMOS33} [get_ports {led[6]}]
set_property -dict {PACKAGE_PIN U5 IOSTANDARD LVCMOS33} [get_ports {led[7]}]
