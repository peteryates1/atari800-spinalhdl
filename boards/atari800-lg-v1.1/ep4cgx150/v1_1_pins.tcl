# V1.1 base-board pin assignments for QMTECH EP4CGX150 core.
# Source from a project .qsf with:
#   set_global_assignment -name SOURCE_TCL_SCRIPT_FILE ../v1_1_pins.tcl
# (or copy the assignments into the project .qsf directly).
#
# Net names match the V1.1 base-board schematic. Use these names verbatim
# on the FPGA top-level ports, or wire them through with a thin wrapper.
#
# Source: ../pin-mapping.md  (generated from
#         Netlist_ATARI-800-QMTechCB-LG-V1_1_2026-04-28.enet)
# FPGA pin tables: jop-spinalhdl/docs/boards/qmtech-ep4cgx150-board.md

# ---------- Clock (QMTECH EP4CGX150 onboard 50 MHz oscillator) ----------
set_location_assignment PIN_B14 -to clk_in

# ---------- VGA (5-bit R/G/B resistor DAC) ----------
set_location_assignment PIN_B22 -to vga_hs        ;# U9.8
set_location_assignment PIN_C21 -to vga_vs        ;# U9.7
set_location_assignment PIN_A16 -to vga_r[0]      ;# U9.24 RED0
set_location_assignment PIN_C16 -to vga_r[1]      ;# U9.21 RED1
set_location_assignment PIN_B17 -to vga_r[2]      ;# U9.22 RED2
set_location_assignment PIN_C17 -to vga_r[3]      ;# U9.19 RED3
set_location_assignment PIN_B18 -to vga_r[4]      ;# U9.20 RED4
set_location_assignment PIN_A19 -to vga_g[0]      ;# U9.17 GRN0
set_location_assignment PIN_A18 -to vga_g[1]      ;# U9.18 GRN1
set_location_assignment PIN_A21 -to vga_g[2]      ;# U9.15 GRN2
set_location_assignment PIN_A20 -to vga_g[3]      ;# U9.16 GRN3
set_location_assignment PIN_C19 -to vga_g[4]      ;# U9.13 GRN4
set_location_assignment PIN_B19 -to vga_b[0]      ;# U9.14 BLU0
set_location_assignment PIN_B21 -to vga_b[1]      ;# U9.11 BLU1
set_location_assignment PIN_A22 -to vga_b[2]      ;# U9.12 BLU2
set_location_assignment PIN_B23 -to vga_b[3]      ;# U9.9  BLU3
set_location_assignment PIN_A23 -to vga_b[4]      ;# U9.10 BLU4

# ---------- Audio (sigma-delta to RC filter) ----------
set_location_assignment PIN_C13 -to audio_l       ;# U9.29
set_location_assignment PIN_C12 -to audio_r       ;# U9.31

# ---------- CH376T USB host (keyboard) — SPI mode ----------
set_location_assignment PIN_A9  -to ch376_kb_sck  ;# U9.41 CH376T_KB_CK
set_location_assignment PIN_A8  -to ch376_kb_mosi ;# U9.42 CH376T_KB_DI
set_location_assignment PIN_C10 -to ch376_kb_miso ;# U9.39 CH376T_KB_DO
set_location_assignment PIN_A6  -to ch376_kb_cs   ;# U9.44 CH376T_KB_CS
set_location_assignment PIN_B11 -to ch376_kb_int  ;# U9.35 CH376T_KB_INTN
set_location_assignment PIN_B10 -to ch376_kb_rst  ;# U9.37 CH376T_KB_RESI
set_instance_assignment -name WEAK_PULL_UP_RESISTOR ON -to ch376_kb_miso
set_instance_assignment -name WEAK_PULL_UP_RESISTOR ON -to ch376_kb_int

# ---------- CH376T SD card — SPI mode (V1.1 fixes V1's DI/DO swap) ----------
set_location_assignment PIN_A5  -to ch376_sd_sck  ;# U9.48 CH376T_SD_CK
set_location_assignment PIN_B5  -to ch376_sd_mosi ;# U9.47 CH376T_SD_DI
set_location_assignment PIN_B6  -to ch376_sd_miso ;# U9.46 CH376T_SD_DO
set_location_assignment PIN_B4  -to ch376_sd_cs   ;# U9.49 CH376T_SD_CS
set_location_assignment PIN_A7  -to ch376_sd_int  ;# U9.43 CH376T_SD_INTN
set_location_assignment PIN_B7  -to ch376_sd_rst  ;# U9.45 CH376T_SD_RESI
set_instance_assignment -name WEAK_PULL_UP_RESISTOR ON -to ch376_sd_miso
set_instance_assignment -name WEAK_PULL_UP_RESISTOR ON -to ch376_sd_int

# ---------- CH376T 12 MHz clock — FPGA OUTPUT to CH376T_KB CLK input ----------
# FPGA PLL replaces the CH376T's crystal — drive a clean 12 MHz here.
set_location_assignment PIN_A4  -to ch376_12mhz   ;# U9.50

# ---------- Base-board user LEDs (8x active-high, V1.1 D1-D8) ----------
set_location_assignment PIN_A2  -to led_base[0]   ;# U9.54
set_location_assignment PIN_A3  -to led_base[1]   ;# U9.53
set_location_assignment PIN_B1  -to led_base[2]   ;# U9.56
set_location_assignment PIN_B2  -to led_base[3]   ;# U9.55
set_location_assignment PIN_C1  -to led_base[4]   ;# U9.58
set_location_assignment PIN_D1  -to led_base[5]   ;# U9.57
set_location_assignment PIN_E1  -to led_base[6]   ;# U9.60
set_location_assignment PIN_E2  -to led_base[7]   ;# U9.59

# ---------- Core-board user LEDs (QMTECH EP4CGX150, 2x active-high) ----------
set_location_assignment PIN_A25 -to led_core[0]
set_location_assignment PIN_A24 -to led_core[1]

# ---------- UART — CH340 USB-serial ----------
# Names below follow the FPGA's perspective (uart_tx = FPGA output drives
# CH340's RXD = base-board net "RXD"; uart_rx = FPGA input from CH340's TXD).
set_location_assignment PIN_AF24 -to uart_tx      ;# U9.71 RXD (base-board)
set_location_assignment PIN_AC21 -to uart_rx      ;# U9.73 TXD (base-board)
set_location_assignment PIN_AF25 -to uart_dtr     ;# U9.72 DTR
set_instance_assignment -name WEAK_PULL_UP_RESISTOR ON -to uart_rx

# ---------- Console switches (active-low) ----------
set_location_assignment PIN_AD21 -to sw_start     ;# U9.74
set_location_assignment PIN_AE23 -to sw_select    ;# U9.75
set_location_assignment PIN_AE22 -to sw_option    ;# U9.77
set_location_assignment PIN_AD20 -to sw_reset     ;# U9.79
set_instance_assignment -name WEAK_PULL_UP_RESISTOR ON -to sw_start
set_instance_assignment -name WEAK_PULL_UP_RESISTOR ON -to sw_select
set_instance_assignment -name WEAK_PULL_UP_RESISTOR ON -to sw_option
set_instance_assignment -name WEAK_PULL_UP_RESISTOR ON -to sw_reset

# ---------- Joystick 1 (active-low) ----------
set_location_assignment PIN_AF9  -to js1_up       ;# U9.110
set_location_assignment PIN_AC10 -to js1_down     ;# U9.107
set_location_assignment PIN_AD10 -to js1_left     ;# U9.108
set_location_assignment PIN_AD15 -to js1_right    ;# U9.102
set_location_assignment PIN_AE9  -to js1_trig     ;# U9.109
set_instance_assignment -name WEAK_PULL_UP_RESISTOR ON -to js1_up
set_instance_assignment -name WEAK_PULL_UP_RESISTOR ON -to js1_down
set_instance_assignment -name WEAK_PULL_UP_RESISTOR ON -to js1_left
set_instance_assignment -name WEAK_PULL_UP_RESISTOR ON -to js1_right
set_instance_assignment -name WEAK_PULL_UP_RESISTOR ON -to js1_trig

# ---------- Joystick 2 (active-low) ----------
set_location_assignment PIN_AF21 -to js2_up       ;# U9.82
set_location_assignment PIN_AE21 -to js2_down     ;# U9.80
set_location_assignment PIN_AF22 -to js2_left     ;# U9.78
set_location_assignment PIN_AF23 -to js2_right    ;# U9.76
set_location_assignment PIN_AF20 -to js2_trig     ;# U9.81
set_instance_assignment -name WEAK_PULL_UP_RESISTOR ON -to js2_up
set_instance_assignment -name WEAK_PULL_UP_RESISTOR ON -to js2_down
set_instance_assignment -name WEAK_PULL_UP_RESISTOR ON -to js2_left
set_instance_assignment -name WEAK_PULL_UP_RESISTOR ON -to js2_right
set_instance_assignment -name WEAK_PULL_UP_RESISTOR ON -to js2_trig

# ---------- Paddle ADC — MCP3208 SPI ----------
set_location_assignment PIN_AD16 -to adc_sck      ;# U9.98 ADC_SPI_CK
set_location_assignment PIN_AF16 -to adc_mosi     ;# U9.96 ADC_SPI_DI
set_location_assignment PIN_AC16 -to adc_miso     ;# U9.97 ADC_SPI_DO
set_location_assignment PIN_AF15 -to adc_cs       ;# U9.95 ADC_SPI_SEL
set_instance_assignment -name WEAK_PULL_UP_RESISTOR ON -to adc_miso

# ---------- External SRAM — IS63LV1024L 128K x 8 async ----------
set_location_assignment PIN_A17 -to sram_a[0]     ;# U9.23
set_location_assignment PIN_A15 -to sram_a[1]     ;# U9.26
set_location_assignment PIN_B15 -to sram_a[2]     ;# U9.25
set_location_assignment PIN_C14 -to sram_a[3]     ;# U9.28
set_location_assignment PIN_A10 -to sram_a[4]     ;# U9.38
set_location_assignment PIN_B9  -to sram_a[5]     ;# U9.40
set_location_assignment PIN_C4  -to sram_a[6]     ;# U9.52
set_location_assignment PIN_C5  -to sram_a[7]     ;# U9.51
set_location_assignment PIN_AE14 -to sram_a[8]    ;# U9.99
set_location_assignment PIN_AE15 -to sram_a[9]    ;# U9.100
set_location_assignment PIN_AC17 -to sram_a[10]   ;# U9.93
set_location_assignment PIN_AD17 -to sram_a[11]   ;# U9.94
set_location_assignment PIN_AE17 -to sram_a[12]   ;# U9.91
set_location_assignment PIN_AC19 -to sram_a[13]   ;# U9.85
set_location_assignment PIN_AD19 -to sram_a[14]   ;# U9.86
set_location_assignment PIN_AE19 -to sram_a[15]   ;# U9.83
set_location_assignment PIN_AF19 -to sram_a[16]   ;# U9.84
set_location_assignment PIN_B13  -to sram_dq[0]   ;# U9.30
set_location_assignment PIN_C11  -to sram_dq[1]   ;# U9.32
set_location_assignment PIN_A12  -to sram_dq[2]   ;# U9.34
set_location_assignment PIN_A13  -to sram_dq[3]   ;# U9.33
set_location_assignment PIN_AF17 -to sram_dq[4]   ;# U9.92
set_location_assignment PIN_AC18 -to sram_dq[5]   ;# U9.89
set_location_assignment PIN_AD18 -to sram_dq[6]   ;# U9.90
set_location_assignment PIN_AE18 -to sram_dq[7]   ;# U9.87
set_location_assignment PIN_C15  -to sram_ce_n    ;# U9.27 SRAM_CE#
set_location_assignment PIN_A11  -to sram_we_n    ;# U9.36 SRAM_WE#
set_location_assignment PIN_AF18 -to sram_oe_n    ;# U9.88 SRAM_OE#

# ---------- SDRAM — W9825G6JH6 (32 MB, 16-bit) on QMTECH core board ----------
set_location_assignment PIN_E22 -to sdram_clk
set_location_assignment PIN_H26 -to sdram_csn
set_location_assignment PIN_K24 -to sdram_cke
set_location_assignment PIN_G25 -to sdram_wen
set_location_assignment PIN_H25 -to sdram_rasn
set_location_assignment PIN_G26 -to sdram_casn
set_location_assignment PIN_J25 -to sdram_ba[0]
set_location_assignment PIN_J26 -to sdram_ba[1]
set_location_assignment PIN_L25 -to sdram_addr[0]
set_location_assignment PIN_L26 -to sdram_addr[1]
set_location_assignment PIN_M25 -to sdram_addr[2]
set_location_assignment PIN_M26 -to sdram_addr[3]
set_location_assignment PIN_N22 -to sdram_addr[4]
set_location_assignment PIN_N23 -to sdram_addr[5]
set_location_assignment PIN_N24 -to sdram_addr[6]
set_location_assignment PIN_M22 -to sdram_addr[7]
set_location_assignment PIN_M24 -to sdram_addr[8]
set_location_assignment PIN_L23 -to sdram_addr[9]
set_location_assignment PIN_K26 -to sdram_addr[10]
set_location_assignment PIN_L24 -to sdram_addr[11]
set_location_assignment PIN_K23 -to sdram_addr[12]
set_location_assignment PIN_F26 -to sdram_dqm[0]
set_location_assignment PIN_H24 -to sdram_dqm[1]
set_location_assignment PIN_B25 -to sdram_dq[0]
set_location_assignment PIN_B26 -to sdram_dq[1]
set_location_assignment PIN_C25 -to sdram_dq[2]
set_location_assignment PIN_C26 -to sdram_dq[3]
set_location_assignment PIN_D25 -to sdram_dq[4]
set_location_assignment PIN_D26 -to sdram_dq[5]
set_location_assignment PIN_E25 -to sdram_dq[6]
set_location_assignment PIN_E26 -to sdram_dq[7]
set_location_assignment PIN_H23 -to sdram_dq[8]
set_location_assignment PIN_G24 -to sdram_dq[9]
set_location_assignment PIN_G22 -to sdram_dq[10]
set_location_assignment PIN_F24 -to sdram_dq[11]
set_location_assignment PIN_F23 -to sdram_dq[12]
set_location_assignment PIN_E24 -to sdram_dq[13]
set_location_assignment PIN_D24 -to sdram_dq[14]
set_location_assignment PIN_C24 -to sdram_dq[15]
