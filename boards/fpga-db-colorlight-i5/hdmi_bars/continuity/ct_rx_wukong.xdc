set_property CFGBVS VCCO [current_design]
set_property CONFIG_VOLTAGE 3.3 [current_design]
set_property -dict {PACKAGE_PIN M21 IOSTANDARD LVCMOS33} [get_ports clk50]
create_clock -period 20.000 -name clk50 [get_ports clk50]
set_property -dict {PACKAGE_PIN E3 IOSTANDARD LVCMOS33} [get_ports uart_tx]
# HDMI connector (wukong_hdmi.xdc), as inputs. PULLDOWN gives the far end's
# AC coupling a DC reference; the input clamp restores the swing.
set_property -dict {PACKAGE_PIN E1 IOSTANDARD LVCMOS33 PULLTYPE PULLDOWN} [get_ports {line[0]}] ;# D0+
set_property -dict {PACKAGE_PIN D1 IOSTANDARD LVCMOS33 PULLTYPE PULLDOWN} [get_ports {line[1]}] ;# D0-
set_property -dict {PACKAGE_PIN F2 IOSTANDARD LVCMOS33 PULLTYPE PULLDOWN} [get_ports {line[2]}] ;# D1+
set_property -dict {PACKAGE_PIN E2 IOSTANDARD LVCMOS33 PULLTYPE PULLDOWN} [get_ports {line[3]}] ;# D1-
set_property -dict {PACKAGE_PIN G2 IOSTANDARD LVCMOS33 PULLTYPE PULLDOWN} [get_ports {line[4]}] ;# D2+
set_property -dict {PACKAGE_PIN G1 IOSTANDARD LVCMOS33 PULLTYPE PULLDOWN} [get_ports {line[5]}] ;# D2-
set_property -dict {PACKAGE_PIN D4 IOSTANDARD LVCMOS33 PULLTYPE PULLDOWN} [get_ports {line[6]}] ;# CLK+
set_property -dict {PACKAGE_PIN C4 IOSTANDARD LVCMOS33 PULLTYPE PULLDOWN} [get_ports {line[7]}] ;# CLK-
