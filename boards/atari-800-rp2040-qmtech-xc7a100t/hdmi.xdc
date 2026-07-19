# -----------------------------------------------------------------------------
# HDMI / DVI TMDS output — QMTech XC7A100T core board, header U4 (banks 34/35)
# Seed constraints. Fold into the full project XDC once the top-level exists.
#
# REQUIRES: 3.3 V supplied to VCCO_34_35 (U4 pins 3 & 4) — banks 34/35 are dead
#           without it, and 3.3 V is what enables TMDS_33.
#
# Balls are the anchor (silk U2/U4 were once swapped; the ball names are correct).
# Data channel order is free in HDL — only the CLOCK pair is fixed. Keep P->HDMI+,
# N->HDMI-. Drive each pair with OBUFDS + IOSTANDARD TMDS_33, serialized by OSERDESE2.
#
# Adjust the get_ports names to match your top-level port names.
# -----------------------------------------------------------------------------

# TMDS Clock  — bank35 L15  (nearest U4 pin 1)
set_property -dict { PACKAGE_PIN B5  IOSTANDARD TMDS_33 } [get_ports { hdmi_clk_p }]
set_property -dict { PACKAGE_PIN A5  IOSTANDARD TMDS_33 } [get_ports { hdmi_clk_n }]

# TMDS Data 0 — bank35 L16
set_property -dict { PACKAGE_PIN B4  IOSTANDARD TMDS_33 } [get_ports { hdmi_d_p[0] }]
set_property -dict { PACKAGE_PIN A4  IOSTANDARD TMDS_33 } [get_ports { hdmi_d_n[0] }]

# TMDS Data 1 — bank35 L20
set_property -dict { PACKAGE_PIN A3  IOSTANDARD TMDS_33 } [get_ports { hdmi_d_p[1] }]
set_property -dict { PACKAGE_PIN A2  IOSTANDARD TMDS_33 } [get_ports { hdmi_d_n[1] }]

# TMDS Data 2 — bank35 L14
set_property -dict { PACKAGE_PIN D4  IOSTANDARD TMDS_33 } [get_ports { hdmi_d_p[2] }]
set_property -dict { PACKAGE_PIN C4  IOSTANDARD TMDS_33 } [get_ports { hdmi_d_n[2] }]

# Note: for OBUFDS you may instead constrain only the _p port and let Vivado infer
# the _n from the differential pair; both styles are valid. Constraining both is
# explicit and unambiguous.
