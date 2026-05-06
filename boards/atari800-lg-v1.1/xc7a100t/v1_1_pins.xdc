# V1.1 base-board pin assignments for QMTECH XC7A100T (Artix-7) core.
# All I/O on V1.1 connectors is 3.3 V, so default to LVCMOS33.
#
# Source: ../pin-mapping.md  (generated from
#         Netlist_ATARI-800-QMTechCB-LG-V1_1_2026-04-28.enet)
# FPGA pin tables: schematic /srv/git/qmtech/QMTECH_XC7A75T-100T-200T_Core_Board/XC7A100T/U4.png and U2.png
# (NB: the qmtech-xc7a100t-board.md doc had an off-by-2 error in its U2/U4
#  pin tables; this XDC uses the schematic numbering: I/O at header pins 7-60.)

set_property IOSTANDARD LVCMOS33 [get_ports -of_objects [get_iobanks {14 15 34 35}]]

# ---------- Clock (QMTECH XC7A100T onboard 50 MHz oscillator, MRCC) ----------
set_property PACKAGE_PIN U22 [get_ports clk_in]
create_clock -name clk_in -period 20.000 [get_ports clk_in]

# ---------- VGA (5-bit R/G/B resistor DAC) ----------
set_property PACKAGE_PIN A5 [get_ports vga_hs]
set_property PACKAGE_PIN B5 [get_ports vga_vs]
set_property PACKAGE_PIN E2 [get_ports {vga_r[0]}]
set_property PACKAGE_PIN E1 [get_ports {vga_r[1]}]
set_property PACKAGE_PIN D1 [get_ports {vga_r[2]}]
set_property PACKAGE_PIN C1 [get_ports {vga_r[3]}]
set_property PACKAGE_PIN B1 [get_ports {vga_r[4]}]
set_property PACKAGE_PIN E5 [get_ports {vga_g[0]}]
set_property PACKAGE_PIN D5 [get_ports {vga_g[1]}]
set_property PACKAGE_PIN C2 [get_ports {vga_g[2]}]
set_property PACKAGE_PIN B2 [get_ports {vga_g[3]}]
set_property PACKAGE_PIN D4 [get_ports {vga_g[4]}]
set_property PACKAGE_PIN C4 [get_ports {vga_b[0]}]
set_property PACKAGE_PIN A3 [get_ports {vga_b[1]}]
set_property PACKAGE_PIN A2 [get_ports {vga_b[2]}]
set_property PACKAGE_PIN B4 [get_ports {vga_b[3]}]
set_property PACKAGE_PIN A4 [get_ports {vga_b[4]}]

# ---------- Audio ----------
set_property PACKAGE_PIN J4 [get_ports audio_l]
set_property PACKAGE_PIN H2 [get_ports audio_r]

# ---------- CH376T USB host (keyboard) — SPI mode ----------
set_property PACKAGE_PIN N3 [get_ports ch376_kb_sck]
set_property PACKAGE_PIN N2 [get_ports ch376_kb_mosi]
set_property PACKAGE_PIN M4 [get_ports ch376_kb_miso]
set_property PACKAGE_PIN M5 [get_ports ch376_kb_cs]   ;# M5_VREF on schematic
set_property PACKAGE_PIN M2 [get_ports ch376_kb_int]
set_property PACKAGE_PIN L5 [get_ports ch376_kb_rst]
set_property PULLUP TRUE [get_ports ch376_kb_miso]
set_property PULLUP TRUE [get_ports ch376_kb_int]

# ---------- CH376T SD card — SPI mode (V1.1 fixes V1's DI/DO swap) ----------
set_property PACKAGE_PIN P3 [get_ports ch376_sd_sck]
set_property PACKAGE_PIN R3 [get_ports ch376_sd_mosi]
set_property PACKAGE_PIN J1 [get_ports ch376_sd_miso]
set_property PACKAGE_PIN T4 [get_ports ch376_sd_cs]
set_property PACKAGE_PIN M6 [get_ports ch376_sd_int]
set_property PACKAGE_PIN K1 [get_ports ch376_sd_rst]
set_property PULLUP TRUE [get_ports ch376_sd_miso]
set_property PULLUP TRUE [get_ports ch376_sd_int]

# ---------- CH376T 12 MHz clock — FPGA OUTPUT to CH376T_KB CLK input ----------
# FPGA PLL replaces the CH376T's crystal — drive a clean 12 MHz here.
set_property PACKAGE_PIN T3 [get_ports ch376_12mhz]

# ---------- Base-board user LEDs (8x active-high, V1.1 D1-D8) ----------
set_property PACKAGE_PIN M1 [get_ports {led_base[0]}]
set_property PACKAGE_PIN N1 [get_ports {led_base[1]}]
set_property PACKAGE_PIN P1 [get_ports {led_base[2]}]
set_property PACKAGE_PIN R1 [get_ports {led_base[3]}]
set_property PACKAGE_PIN R2 [get_ports {led_base[4]}]
set_property PACKAGE_PIN T2 [get_ports {led_base[5]}]
set_property PACKAGE_PIN U1 [get_ports {led_base[6]}]
set_property PACKAGE_PIN U2 [get_ports {led_base[7]}]

# ---------- Core-board user LEDs (QMTECH XC7A100T, 2x active-low) ----------
set_property PACKAGE_PIN T23 [get_ports {led_core[0]}]
set_property PACKAGE_PIN R23 [get_ports {led_core[1]}]

# ---------- UART — CH340 USB-serial on base board ----------
# uart_tx = FPGA output → CH340 RXD (= base-board net "RXD")
# uart_rx = FPGA input  ← CH340 TXD (= base-board net "TXD")
set_property PACKAGE_PIN D26 [get_ports uart_tx]   ;# U9.71 RXD (base-board)
set_property PACKAGE_PIN D25 [get_ports uart_rx]   ;# U9.73 TXD (base-board)
set_property PACKAGE_PIN E26 [get_ports uart_dtr]  ;# U9.72 DTR
set_property PULLUP TRUE [get_ports uart_rx]

# ---------- Console switches (active-low) ----------
set_property PACKAGE_PIN E25 [get_ports sw_start]
set_property PACKAGE_PIN G26 [get_ports sw_select]
set_property PACKAGE_PIN E23 [get_ports sw_option]
set_property PACKAGE_PIN F22 [get_ports sw_reset]
set_property PULLUP TRUE [get_ports sw_start]
set_property PULLUP TRUE [get_ports sw_select]
set_property PULLUP TRUE [get_ports sw_option]
set_property PULLUP TRUE [get_ports sw_reset]

# ---------- Joystick 1 (active-low) ----------
set_property PACKAGE_PIN U21 [get_ports js1_up]
set_property PACKAGE_PIN T25 [get_ports js1_down]
set_property PACKAGE_PIN T24 [get_ports js1_left]
set_property PACKAGE_PIN N21 [get_ports js1_right]
set_property PACKAGE_PIN V21 [get_ports js1_trig]
set_property PULLUP TRUE [get_ports js1_up]
set_property PULLUP TRUE [get_ports js1_down]
set_property PULLUP TRUE [get_ports js1_left]
set_property PULLUP TRUE [get_ports js1_right]
set_property PULLUP TRUE [get_ports js1_trig]

# ---------- Joystick 2 (active-low) ----------
set_property PACKAGE_PIN J25 [get_ports js2_up]
set_property PACKAGE_PIN G22 [get_ports js2_down]
set_property PACKAGE_PIN F23 [get_ports js2_left]
set_property PACKAGE_PIN H26 [get_ports js2_right]
set_property PACKAGE_PIN J26 [get_ports js2_trig]
set_property PULLUP TRUE [get_ports js2_up]
set_property PULLUP TRUE [get_ports js2_down]
set_property PULLUP TRUE [get_ports js2_left]
set_property PULLUP TRUE [get_ports js2_right]
set_property PULLUP TRUE [get_ports js2_trig]

# ---------- Paddle ADC — MCP3208 SPI ----------
set_property PACKAGE_PIN R26 [get_ports adc_sck]
set_property PACKAGE_PIN L22 [get_ports adc_mosi]
set_property PACKAGE_PIN P26 [get_ports adc_miso]
set_property PACKAGE_PIN L23 [get_ports adc_cs]
set_property PULLUP TRUE [get_ports adc_miso]

# ---------- External SRAM — IS63LV1024L 128K x 8 async ----------
set_property PACKAGE_PIN F2  [get_ports {sram_a[0]}]    ;# U9.23
set_property PACKAGE_PIN F4  [get_ports {sram_a[1]}]    ;# U9.26
set_property PACKAGE_PIN G4  [get_ports {sram_a[2]}]    ;# U9.25
set_property PACKAGE_PIN G1  [get_ports {sram_a[3]}]    ;# U9.28
set_property PACKAGE_PIN K5  [get_ports {sram_a[4]}]    ;# U9.38
set_property PACKAGE_PIN L4  [get_ports {sram_a[5]}]    ;# U9.40
set_property PACKAGE_PIN P5  [get_ports {sram_a[6]}]    ;# U9.52  P5_VREF on schematic
set_property PACKAGE_PIN P6  [get_ports {sram_a[7]}]    ;# U9.51
set_property PACKAGE_PIN M25 [get_ports {sram_a[8]}]    ;# U9.99
set_property PACKAGE_PIN M24 [get_ports {sram_a[9]}]    ;# U9.100
set_property PACKAGE_PIN M26 [get_ports {sram_a[10]}]   ;# U9.93
set_property PACKAGE_PIN N26 [get_ports {sram_a[11]}]   ;# U9.94
set_property PACKAGE_PIN K23 [get_ports {sram_a[12]}]   ;# U9.91
set_property PACKAGE_PIN H22 [get_ports {sram_a[13]}]   ;# U9.85
set_property PACKAGE_PIN H21 [get_ports {sram_a[14]}]   ;# U9.86
set_property PACKAGE_PIN G21 [get_ports {sram_a[15]}]   ;# U9.83
set_property PACKAGE_PIN G20 [get_ports {sram_a[16]}]   ;# U9.84
set_property PACKAGE_PIN H4  [get_ports {sram_dq[0]}]   ;# U9.30
set_property PACKAGE_PIN H1  [get_ports {sram_dq[1]}]   ;# U9.32
set_property PACKAGE_PIN G9  [get_ports {sram_dq[2]}]   ;# U9.34
set_property PACKAGE_PIN H9  [get_ports {sram_dq[3]}]   ;# U9.33
set_property PACKAGE_PIN K22 [get_ports {sram_dq[4]}]   ;# U9.92
set_property PACKAGE_PIN K26 [get_ports {sram_dq[5]}]   ;# U9.89
set_property PACKAGE_PIN K25 [get_ports {sram_dq[6]}]   ;# U9.90
set_property PACKAGE_PIN J21 [get_ports {sram_dq[7]}]   ;# U9.87
set_property PACKAGE_PIN G2  [get_ports sram_ce_n]      ;# U9.27
set_property PACKAGE_PIN L2  [get_ports sram_we_n]      ;# U9.36
set_property PACKAGE_PIN K21 [get_ports sram_oe_n]      ;# U9.88

# NOTE: DDR3 SDRAM is on the XC7A100T core board (banks 35/34/15) but is
# not used by the V1.1 Atari build. See qmtech-xc7a100t-board.md for DDR3
# pin assignments if needed.
