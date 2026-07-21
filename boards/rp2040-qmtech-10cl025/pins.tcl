# Pin assignments for ATARI-800-QMTechCB-RP2040-STAMP-HDMI-LG base board
# using the QMTECH 10CL025 (Cyclone 10 LP) core board.
#
# Source from a project .qsf with:
#   set_global_assignment -name SOURCE_TCL_SCRIPT_FILE ../pins.tcl
#
# Net names from ./pin-mapping.md (extracted from
# Netlist_ATARI-800-QMTechCB-RP2040-STAMP-HDMI-LG_2026-05-20.enet).
# FPGA pin table from
# /srv/git/qmtech/QMTECH_Cyclone10_10CL006_025/10CL025YU256/Connectors.csv.

# ---------- Free dual-purpose configuration pins for regular IO ----------
# PIN_F16 (nCEO by default) carries RM2_WIFI_ON on this board. Release it.
set_global_assignment -name CYCLONEII_RESERVE_NCEO_AFTER_CONFIGURATION "USE AS REGULAR IO"

# ---------- Clock (QMTECH 10CL025 onboard 50 MHz oscillator) ----------
set_location_assignment PIN_E2 -to clk_in

# ---------- HDMI (4× TMDS pairs, no DDC/HPD per ACC2361) ----------
# Pseudo-differential at 3.3V LVCMOS. AC-coupling + source termination at J12.
# All four pairs are true bank-4 DIFFIO pairs; even connector pin = p, odd = n.
set_location_assignment PIN_R11 -to hdmi_clk_p     ;# U4.7  FPGA_DVI_CLK_P  (B17p)
set_location_assignment PIN_T11 -to hdmi_clk_n     ;# U4.8  FPGA_DVI_CLK_N  (B17n)
set_location_assignment PIN_R12 -to hdmi_d0_p      ;# U4.9  FPGA_DVI_TX0_P  (B18p)
set_location_assignment PIN_T12 -to hdmi_d0_n      ;# U4.10 FPGA_DVI_TX0_N  (B18n)
set_location_assignment PIN_R13 -to hdmi_d1_p      ;# U4.15 FPGA_DVI_TX1_P  (B20p)
set_location_assignment PIN_T13 -to hdmi_d1_n      ;# U4.16 FPGA_DVI_TX1_N  (B20n)
set_location_assignment PIN_T14 -to hdmi_d2_p      ;# U4.18 FPGA_DVI_TX2_P  (B23p)
set_location_assignment PIN_T15 -to hdmi_d2_n      ;# U4.20 FPGA_DVI_TX2_N  (B23n)

# ---------- Audio (sigma-delta to RC filter, then 3.5mm jack J3) ----------
set_location_assignment PIN_N15 -to audio_l        ;# U4.25
set_location_assignment PIN_L16 -to audio_r        ;# U4.29

# ---------- Console switches (active-low, SW3-6 + GND) ----------
set_location_assignment PIN_P16 -to sw_reset       ;# U4.26
set_location_assignment PIN_N16 -to sw_option      ;# U4.28
set_location_assignment PIN_L13 -to sw_select      ;# U4.30
set_location_assignment PIN_K15 -to sw_start       ;# U4.32
set_instance_assignment -name WEAK_PULL_UP_RESISTOR ON -to sw_reset
set_instance_assignment -name WEAK_PULL_UP_RESISTOR ON -to sw_option
set_instance_assignment -name WEAK_PULL_UP_RESISTOR ON -to sw_select
set_instance_assignment -name WEAK_PULL_UP_RESISTOR ON -to sw_start

# ---------- Raspberry Pi Radio Module 2 — SPI + control (U6) ----------
set_location_assignment PIN_F14 -to rm2_sck        ;# U4.38 RM2_SPI_CLK
set_location_assignment PIN_J16 -to rm2_mosi       ;# U4.39 RM2_SPI_DI
set_location_assignment PIN_J15 -to rm2_miso       ;# U4.40 RM2_SPI_DO
set_location_assignment PIN_G16 -to rm2_cs         ;# U4.41 RM2_SPI_CS
set_location_assignment PIN_G15 -to rm2_irq_n      ;# U4.42 RM2_NIRQ
set_location_assignment PIN_F16 -to rm2_wifi_on    ;# U4.43 RM2_WIFI_ON  (needs CYCLONEII_RESERVE_NCEO above)
set_location_assignment PIN_F15 -to rm2_bt_on      ;# U4.44 RM2_BLUETOOTH_ON
set_instance_assignment -name WEAK_PULL_UP_RESISTOR ON -to rm2_miso
set_instance_assignment -name WEAK_PULL_UP_RESISTOR ON -to rm2_irq_n

# ---------- SD card (SDIO 4-bit, J7 DM3AT-SF-PEJM5) ----------
# Native SDIO; can also run in SPI mode (CLK=clk, MOSI=cmd, MISO=dat0, CS=dat3).
# Split DAT[2:0] into sd_dat_in[2:0] (FPGA inputs) and DAT3 into sd_dat_3
# (FPGA output = SPI CS). The SV wrapper assigns directions per bit.
set_location_assignment PIN_D16 -to sd_cd          ;# U4.46 CD   (active-low card detect)
set_location_assignment PIN_D14 -to sd_dat_in[1]   ;# U4.47
set_location_assignment PIN_C14 -to sd_dat_in[0]   ;# U4.48 (= MISO in SPI mode)
set_location_assignment PIN_C16 -to sd_clk         ;# U4.49
set_location_assignment PIN_C15 -to sd_cmd         ;# U4.50 (= MOSI in SPI mode)
set_location_assignment PIN_B16 -to sd_dat_3       ;# U4.51 (= CS in SPI mode)
set_location_assignment PIN_A15 -to sd_dat_in[2]   ;# U4.52
set_instance_assignment -name WEAK_PULL_UP_RESISTOR ON -to sd_cd
set_instance_assignment -name WEAK_PULL_UP_RESISTOR ON -to sd_dat_in[0]
set_instance_assignment -name WEAK_PULL_UP_RESISTOR ON -to sd_dat_in[1]
set_instance_assignment -name WEAK_PULL_UP_RESISTOR ON -to sd_dat_in[2]
set_instance_assignment -name WEAK_PULL_UP_RESISTOR ON -to sd_cmd

# ---------- Joystick 2 (active-low, J11 DB9) ----------
set_location_assignment PIN_D3 -to js2_right       ;# U4.76
set_location_assignment PIN_B3 -to js2_left        ;# U4.78
set_location_assignment PIN_B4 -to js2_down        ;# U4.80
set_location_assignment PIN_F8 -to js2_trig        ;# U4.81
set_location_assignment PIN_A2 -to js2_up          ;# U4.82
set_instance_assignment -name WEAK_PULL_UP_RESISTOR ON -to js2_right
set_instance_assignment -name WEAK_PULL_UP_RESISTOR ON -to js2_left
set_instance_assignment -name WEAK_PULL_UP_RESISTOR ON -to js2_down
set_instance_assignment -name WEAK_PULL_UP_RESISTOR ON -to js2_trig
set_instance_assignment -name WEAK_PULL_UP_RESISTOR ON -to js2_up

# ---------- Joystick 1 (active-low, J10 DB9) ----------
set_location_assignment PIN_E11 -to js1_right      ;# U4.102
set_location_assignment PIN_A10 -to js1_down       ;# U4.107
set_location_assignment PIN_B10 -to js1_left       ;# U4.108
set_location_assignment PIN_C11 -to js1_trig       ;# U4.109
set_location_assignment PIN_D11 -to js1_up         ;# U4.110
set_instance_assignment -name WEAK_PULL_UP_RESISTOR ON -to js1_right
set_instance_assignment -name WEAK_PULL_UP_RESISTOR ON -to js1_down
set_instance_assignment -name WEAK_PULL_UP_RESISTOR ON -to js1_left
set_instance_assignment -name WEAK_PULL_UP_RESISTOR ON -to js1_trig
set_instance_assignment -name WEAK_PULL_UP_RESISTOR ON -to js1_up

# ---------- RP2040 ↔ FPGA SPI link (RP2040 = master, FPGA = slave) ----------
# FPGA_DI / FPGA_CLK / FPGA_CSN are FPGA inputs driven by the RP2040.
# FPGA_DO is the FPGA's output back to the RP2040 (MISO).
set_location_assignment PIN_D8 -to rp_mosi         ;# U4.95 FPGA_DI    (RP2040 GPIO19 → FPGA in)
set_location_assignment PIN_C8 -to rp_sck          ;# U4.96 FPGA_CLK   (RP2040 GPIO18 → FPGA in)
set_location_assignment PIN_C9 -to rp_csn          ;# U4.97 FPGA_CSN   (RP2040 GPIO17 → FPGA in, active-low)
set_location_assignment PIN_D9 -to rp_miso         ;# U4.98 FPGA_DO    (FPGA out → RP2040 GPIO16)
set_instance_assignment -name WEAK_PULL_UP_RESISTOR ON -to rp_csn

# ---------- RP2040 GPIO bus (14 lines, general-purpose RP2040↔FPGA) ----------
# Each RP2040 GPIO pin is its own scalar net (rp_gpioNN); the SV wrapper
# fixes direction per bit. RP2040 GPIO numbering preserved in the net name.
set_location_assignment PIN_C6  -to rp_gpio4       ;# U4.86  (input: → rm2_bt_on)
set_location_assignment PIN_E7  -to rp_gpio5       ;# U4.88  (input: → rm2_wifi_on)
set_location_assignment PIN_B6  -to rp_gpio10      ;# U4.90  (input: → sd_clk)
set_location_assignment PIN_B7  -to rp_gpio11      ;# U4.92  (input: → sd_cmd/MOSI)
set_location_assignment PIN_A7  -to rp_gpio12      ;# U4.94  (output: ← sd_dat0/MISO)
set_location_assignment PIN_E10 -to rp_gpio13      ;# U4.101 (input: → sd_dat3/CS)
set_location_assignment PIN_E9  -to rp_gpio14      ;# U4.100 (output: ← sd_cd)
set_location_assignment PIN_F9  -to rp_gpio15      ;# U4.99  (output: spare/heartbeat)
set_location_assignment PIN_E8  -to rp_gpio20      ;# U4.93  (input: → rm2_sck)
set_location_assignment PIN_A6  -to rp_gpio21      ;# U4.91  (input: → rm2_mosi)
set_location_assignment PIN_A5  -to rp_gpio22      ;# U4.89  (output: ← rm2_miso)
set_location_assignment PIN_B5  -to rp_gpio23      ;# U4.87  (input: → rm2_cs)
set_location_assignment PIN_D6  -to rp_gpio24      ;# U4.85  (output: ← rm2_irq_n)
set_location_assignment PIN_A4  -to rp_gpio25      ;# U4.83  (output: PLL lock)

# ---------- Core-board user LED (QMTECH 10CL025, PIN_N9) ----------
set_location_assignment PIN_N9 -to led_core[0]

# ---------- SDRAM — onboard W9825G6JH-6 (32 MB, 16-bit) on QMTECH 10CL025 ----------
# Pinout from QMTECH reference project Test04_SDRAM.
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
