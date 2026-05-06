# V1.1 base-board pin assignments for QMTECH 10CL025 (Cyclone 10 LP) core.
# Source from a project .qsf with:
#   set_global_assignment -name SOURCE_TCL_SCRIPT_FILE ../v1_1_pins.tcl
#
# Source: ../pin-mapping.md  (generated from
#         Netlist_ATARI-800-QMTechCB-LG-V1_1_2026-04-28.enet)
# FPGA pin tables: /srv/git/qmtech/QMTECH_Cyclone10_10CL006_025/10CL025YU256/Connectors.csv

# ---------- Clock (QMTECH 10CL025 onboard 50 MHz oscillator) ----------
set_location_assignment PIN_E2 -to clk_in

# ---------- VGA (5-bit R/G/B resistor DAC) ----------
set_location_assignment PIN_T11 -to vga_hs        ;# U9.8
set_location_assignment PIN_R11 -to vga_vs        ;# U9.7
set_location_assignment PIN_R16 -to vga_r[0]      ;# U9.24 RED0
set_location_assignment PIN_N14 -to vga_r[1]      ;# U9.21 RED1
set_location_assignment PIN_P14 -to vga_r[2]      ;# U9.22 RED2
set_location_assignment PIN_N12 -to vga_r[3]      ;# U9.19 RED3
set_location_assignment PIN_T15 -to vga_r[4]      ;# U9.20 RED4
set_location_assignment PIN_R14 -to vga_g[0]      ;# U9.17 GRN0
set_location_assignment PIN_T14 -to vga_g[1]      ;# U9.18 GRN1
set_location_assignment PIN_R13 -to vga_g[2]      ;# U9.15 GRN2
set_location_assignment PIN_T13 -to vga_g[3]      ;# U9.16 GRN3
set_location_assignment PIN_P11 -to vga_g[4]      ;# U9.13 GRN4
set_location_assignment PIN_N11 -to vga_b[0]      ;# U9.14 BLU0
set_location_assignment PIN_M10 -to vga_b[1]      ;# U9.11 BLU1
set_location_assignment PIN_P9  -to vga_b[2]      ;# U9.12 BLU2
set_location_assignment PIN_R12 -to vga_b[3]      ;# U9.9  BLU3
set_location_assignment PIN_T12 -to vga_b[4]      ;# U9.10 BLU4

# ---------- Audio (sigma-delta to RC filter) ----------
set_location_assignment PIN_L16 -to audio_l       ;# U9.29
set_location_assignment PIN_L14 -to audio_r       ;# U9.31

# ---------- CH376T USB host (keyboard) — SPI mode ----------
set_location_assignment PIN_G16 -to ch376_kb_sck  ;# U9.41 CH376T_KB_CK
set_location_assignment PIN_G15 -to ch376_kb_mosi ;# U9.42 CH376T_KB_DI
set_location_assignment PIN_J16 -to ch376_kb_miso ;# U9.39 CH376T_KB_DO
set_location_assignment PIN_F15 -to ch376_kb_cs   ;# U9.44 CH376T_KB_CS
set_location_assignment PIN_J14 -to ch376_kb_int  ;# U9.35 CH376T_KB_INTN
set_location_assignment PIN_D12 -to ch376_kb_rst  ;# U9.37 CH376T_KB_RESI
set_instance_assignment -name WEAK_PULL_UP_RESISTOR ON -to ch376_kb_miso
set_instance_assignment -name WEAK_PULL_UP_RESISTOR ON -to ch376_kb_int

# ---------- CH376T SD card — SPI mode (V1.1 fixes V1's DI/DO swap) ----------
set_location_assignment PIN_C14 -to ch376_sd_sck  ;# U9.48 CH376T_SD_CK
set_location_assignment PIN_D14 -to ch376_sd_mosi ;# U9.47 CH376T_SD_DI
set_location_assignment PIN_D16 -to ch376_sd_miso ;# U9.46 CH376T_SD_DO
set_location_assignment PIN_C16 -to ch376_sd_cs   ;# U9.49 CH376T_SD_CS
set_location_assignment PIN_F16 -to ch376_sd_int  ;# U9.43 CH376T_SD_INTN
set_location_assignment PIN_D15 -to ch376_sd_rst  ;# U9.45 CH376T_SD_RESI
set_instance_assignment -name WEAK_PULL_UP_RESISTOR ON -to ch376_sd_miso
set_instance_assignment -name WEAK_PULL_UP_RESISTOR ON -to ch376_sd_int

# ---------- CH376T 12 MHz clock — FPGA OUTPUT to CH376T_KB CLK input ----------
# FPGA PLL replaces the CH376T's crystal — drive a clean 12 MHz here.
set_location_assignment PIN_C15 -to ch376_12mhz   ;# U9.50

# ---------- Base-board user LEDs (8x active-high, V1.1 D1-D8) ----------
set_location_assignment PIN_A14 -to led_base[0]   ;# U9.54
set_location_assignment PIN_B14 -to led_base[1]   ;# U9.53
set_location_assignment PIN_A13 -to led_base[2]   ;# U9.56
set_location_assignment PIN_B13 -to led_base[3]   ;# U9.55
set_location_assignment PIN_A12 -to led_base[4]   ;# U9.58
set_location_assignment PIN_B12 -to led_base[5]   ;# U9.57
set_location_assignment PIN_A11 -to led_base[6]   ;# U9.60
set_location_assignment PIN_B11 -to led_base[7]   ;# U9.59

# ---------- Core-board user LED (QMTECH 10CL025, 1x active-high at PIN_N9) ----------
set_location_assignment PIN_N9 -to led_core[0]

# ---------- UART — CH340 USB-serial ----------
# uart_tx = FPGA output → CH340 RXD (= base-board net "RXD")
# uart_rx = FPGA input  ← CH340 TXD (= base-board net "TXD")
set_location_assignment PIN_G1 -to uart_tx        ;# U9.71 RXD (base-board)
set_location_assignment PIN_D1 -to uart_rx        ;# U9.73 TXD (base-board)
set_location_assignment PIN_G2 -to uart_dtr       ;# U9.72 DTR
set_instance_assignment -name WEAK_PULL_UP_RESISTOR ON -to uart_rx

# ---------- Console switches (active-low) ----------
set_location_assignment PIN_C2 -to sw_start       ;# U9.74
set_location_assignment PIN_B1 -to sw_select      ;# U9.75
set_location_assignment PIN_C3 -to sw_option      ;# U9.77
set_location_assignment PIN_A3 -to sw_reset       ;# U9.79
set_instance_assignment -name WEAK_PULL_UP_RESISTOR ON -to sw_start
set_instance_assignment -name WEAK_PULL_UP_RESISTOR ON -to sw_select
set_instance_assignment -name WEAK_PULL_UP_RESISTOR ON -to sw_option
set_instance_assignment -name WEAK_PULL_UP_RESISTOR ON -to sw_reset

# ---------- Joystick 1 (active-low) ----------
set_location_assignment PIN_D11 -to js1_up        ;# U9.110
set_location_assignment PIN_A10 -to js1_down      ;# U9.107
set_location_assignment PIN_B10 -to js1_left      ;# U9.108
set_location_assignment PIN_E11 -to js1_right     ;# U9.102
set_location_assignment PIN_C11 -to js1_trig      ;# U9.109
set_instance_assignment -name WEAK_PULL_UP_RESISTOR ON -to js1_up
set_instance_assignment -name WEAK_PULL_UP_RESISTOR ON -to js1_down
set_instance_assignment -name WEAK_PULL_UP_RESISTOR ON -to js1_left
set_instance_assignment -name WEAK_PULL_UP_RESISTOR ON -to js1_right
set_instance_assignment -name WEAK_PULL_UP_RESISTOR ON -to js1_trig

# ---------- Joystick 2 (active-low) ----------
set_location_assignment PIN_A2 -to js2_up         ;# U9.82
set_location_assignment PIN_B4 -to js2_down       ;# U9.80
set_location_assignment PIN_B3 -to js2_left       ;# U9.78
set_location_assignment PIN_D3 -to js2_right      ;# U9.76
set_location_assignment PIN_F8 -to js2_trig       ;# U9.81
set_instance_assignment -name WEAK_PULL_UP_RESISTOR ON -to js2_up
set_instance_assignment -name WEAK_PULL_UP_RESISTOR ON -to js2_down
set_instance_assignment -name WEAK_PULL_UP_RESISTOR ON -to js2_left
set_instance_assignment -name WEAK_PULL_UP_RESISTOR ON -to js2_right
set_instance_assignment -name WEAK_PULL_UP_RESISTOR ON -to js2_trig

# ---------- Paddle ADC — MCP3208 SPI ----------
set_location_assignment PIN_D9 -to adc_sck        ;# U9.98 ADC_SPI_CK
set_location_assignment PIN_C8 -to adc_mosi       ;# U9.96 ADC_SPI_DI
set_location_assignment PIN_C9 -to adc_miso       ;# U9.97 ADC_SPI_DO
set_location_assignment PIN_D8 -to adc_cs         ;# U9.95 ADC_SPI_SEL
set_instance_assignment -name WEAK_PULL_UP_RESISTOR ON -to adc_miso

# ---------- External SRAM — IS63LV1024L 128K x 8 async ----------
set_location_assignment PIN_P15 -to sram_a[0]     ;# U9.23
set_location_assignment PIN_P16 -to sram_a[1]     ;# U9.26
set_location_assignment PIN_N15 -to sram_a[2]     ;# U9.25
set_location_assignment PIN_N16 -to sram_a[3]     ;# U9.28
set_location_assignment PIN_F14 -to sram_a[4]     ;# U9.38
set_location_assignment PIN_J15 -to sram_a[5]     ;# U9.40
set_location_assignment PIN_A15 -to sram_a[6]     ;# U9.52
set_location_assignment PIN_B16 -to sram_a[7]     ;# U9.51
set_location_assignment PIN_F9  -to sram_a[8]     ;# U9.99
set_location_assignment PIN_E9  -to sram_a[9]     ;# U9.100
set_location_assignment PIN_E8  -to sram_a[10]    ;# U9.93
set_location_assignment PIN_A7  -to sram_a[11]    ;# U9.94
set_location_assignment PIN_A6  -to sram_a[12]    ;# U9.91
set_location_assignment PIN_D6  -to sram_a[13]    ;# U9.85
set_location_assignment PIN_C6  -to sram_a[14]    ;# U9.86
set_location_assignment PIN_A4  -to sram_a[15]    ;# U9.83
set_location_assignment PIN_E6  -to sram_a[16]    ;# U9.84
set_location_assignment PIN_L13 -to sram_dq[0]    ;# U9.30
set_location_assignment PIN_K15 -to sram_dq[1]    ;# U9.32
set_location_assignment PIN_J13 -to sram_dq[2]    ;# U9.34
set_location_assignment PIN_K16 -to sram_dq[3]    ;# U9.33
set_location_assignment PIN_B7  -to sram_dq[4]    ;# U9.92
set_location_assignment PIN_A5  -to sram_dq[5]    ;# U9.89
set_location_assignment PIN_B6  -to sram_dq[6]    ;# U9.90
set_location_assignment PIN_B5  -to sram_dq[7]    ;# U9.87
set_location_assignment PIN_L15 -to sram_ce_n     ;# U9.27 SRAM_CE#
set_location_assignment PIN_F13 -to sram_we_n     ;# U9.36 SRAM_WE#
set_location_assignment PIN_E7  -to sram_oe_n     ;# U9.88 SRAM_OE#

# ---------- SDRAM — onboard W9825G6JH-6 (32 MB, 16-bit) on QMTECH 10CL025 ----------
# Pinout from QMTECH reference project Test04_SDRAM
# (/srv/git/qmtech/QMTECH_Cyclone10_10CL006_025/10CL025YU256/Software/Test04_SDRAM).
# A BRAM-only Atari build is also viable on this device (~58 of 66 M9K used).
set_location_assignment PIN_P2  -to sdram_clk
set_location_assignment PIN_P8  -to sdram_csn
set_location_assignment PIN_R1  -to sdram_cke
set_location_assignment PIN_P6  -to sdram_wen
set_location_assignment PIN_M8  -to sdram_rasn
set_location_assignment PIN_M7  -to sdram_casn
set_location_assignment PIN_N8  -to sdram_ba[0]
set_location_assignment PIN_L8  -to sdram_ba[1]
set_location_assignment PIN_R7  -to sdram_addr[0]
set_location_assignment PIN_T7  -to sdram_addr[1]
set_location_assignment PIN_T10 -to sdram_addr[2]
set_location_assignment PIN_R10 -to sdram_addr[3]
set_location_assignment PIN_R6  -to sdram_addr[4]
set_location_assignment PIN_T5  -to sdram_addr[5]
set_location_assignment PIN_R5  -to sdram_addr[6]
set_location_assignment PIN_T4  -to sdram_addr[7]
set_location_assignment PIN_R4  -to sdram_addr[8]
set_location_assignment PIN_T3  -to sdram_addr[9]
set_location_assignment PIN_T6  -to sdram_addr[10]
set_location_assignment PIN_R3  -to sdram_addr[11]
set_location_assignment PIN_T2  -to sdram_addr[12]
set_location_assignment PIN_N6  -to sdram_dqm[0]   ;# LDQM
set_location_assignment PIN_P1  -to sdram_dqm[1]   ;# UDQM
set_location_assignment PIN_K5  -to sdram_dq[0]
set_location_assignment PIN_L3  -to sdram_dq[1]
set_location_assignment PIN_L4  -to sdram_dq[2]
set_location_assignment PIN_L7  -to sdram_dq[3]
set_location_assignment PIN_N3  -to sdram_dq[4]
set_location_assignment PIN_M6  -to sdram_dq[5]
set_location_assignment PIN_P3  -to sdram_dq[6]
set_location_assignment PIN_N5  -to sdram_dq[7]
set_location_assignment PIN_N2  -to sdram_dq[8]
set_location_assignment PIN_N1  -to sdram_dq[9]
set_location_assignment PIN_L1  -to sdram_dq[10]
set_location_assignment PIN_L2  -to sdram_dq[11]
set_location_assignment PIN_K1  -to sdram_dq[12]
set_location_assignment PIN_K2  -to sdram_dq[13]
set_location_assignment PIN_J1  -to sdram_dq[14]
set_location_assignment PIN_J2  -to sdram_dq[15]
