# ATARI-800-QMTechCB-RP2040-STAMP-HDMI-LG — connector pin mapping

Alternative base board to the V1.1/V1.2 LG family. Targets the
**QMTECH 10CL025 only** (Cyclone 10 LP — 10CL025YU256I7G). Replaces the
VGA resistor-DAC with HDMI, the dual-CH376T USB hosts with a
**RP2040-STAMP** (USB host + supervisor), the MCP3208 paddle ADC with
the RP2040's onboard 12-bit ADC, and adds an optional
**Raspberry Pi Radio Module 2** (WiFi/BT) on a dedicated FPGA SPI port.

V1.x's external IS63LV1024L SRAM is removed — the 10CL025's onboard
W9825 SDRAM is the only memory off-FPGA.

Source: `~/Netlist_ATARI-800-QMTechCB-RP2040-STAMP-HDMI-LG_2026-05-20.enet`
(KiCad netlist export). FPGA pin table from
[`/srv/git/qmtech/QMTECH_Cyclone10_10CL006_025/10CL025YU256/Connectors.csv`](https://github.com/ChinaQMTECH/QMTECH_Cyclone10_10CL006_025/blob/master/10CL025YU256/Connectors.csv).

See [QMTECH 10CL025 board doc](https://github.com/peteryates1/jop-spinalhdl/blob/main/docs/boards/qmtech-10cl025-board.md)
for FPGA spec, U7/U8 connector layout, and pinout gotchas.

## Convention

The QMTECH 10CL025 mates with this board via its two 32×2 headers (U8 +
U7 on the core board). In the schematic the combined connector is
designated **U4** with 128 pins, where:

| U4 pin range | QMTECH 10CL025 header | I/O range |
|--------------|-----------------------|-----------|
| 1–64         | U8 (banks 4 / 5 / 6 / 7) | pins 7–60 |
| 65–128       | U7 (banks 1 / 2 / 7 / 8) | pins 7–46; 47–60 NC on core board |

Power/ground convention: pins 1,2,5,6,61,62,65,66,69,70,125,126 = GND;
pins 3,4,67,68 = 3V3; pins 63,64,127,128 = VIN.

Note: 10CL025 input-only clock pins (A8/B8/A9/B9 = U7 pins 39–42 = U4
pins 103–106) are left unconnected — same workaround as V1.1.

## Major design choices vs V1.1

- **No CH376T USB-host chips.** Keyboard support runs through the RP2040
  STAMP's native USB host. Frees U4 pins 35, 37, 39–44 from CH376T_KB
  signals and pins 43, 45–50 from CH376T_SD.
- **HDMI replaces VGA.** Four TMDS pairs from 10CL025 bank-4 cluster on
  U4.7–20 drive the HDMI-A connector J12 directly (pseudo-differential
  LVCMOS33, AC-coupled, no DDC/HPD per ACC2361). See
  [reference_10cl025_hdmi_pins.md](../../../.claude/projects/-home-peter-atari800-spinalhdl/memory/reference_10cl025_hdmi_pins.md).
- **SD card to FPGA (SDIO 4-bit).** J7 (DM3AT-SF-PEJM5) wired with full
  CLK + CMD + DAT[3:0] + CD interface for native SDIO; can also run as
  SPI mode (CLK, CMD=MOSI, DAT0=MISO, DAT3=CS). The FPGA can re-export
  to the RP2040 over the RP2040 ↔ FPGA SPI link if desired.
- **RP2040 ↔ FPGA SPI link.** RP2040 is the SPI master (FPGA_CLK, FPGA_CSN,
  FPGA_DI from RP2040; FPGA_DO back to RP2040) on U4.95–98. RP2040 is also
  given a 13-line general-purpose GPIO bus (U4.83–94, 99–101) for parallel
  control or bytewise UART substitute.
- **Raspberry Pi Radio Module 2** (U6, optional fit) connects to the
  FPGA via a dedicated SPI port (CLK/MOSI/MISO/CS/IRQ + WIFI_ON +
  BLUETOOTH_ON) on U4.38–44 — i.e. on the FPGA side, not the RP2040
  side. SDIO mode is **not** wired.
- **No base-board LEDs.** The 8× user LEDs at V1.1's U9.53–60 are gone;
  only the core board's `led_core[0]` (PIN_N9) remains, plus the power
  LED D1.
- **No external SRAM.** All RAM is onboard W9825 SDRAM (or BRAM).
- **No analog paddle ADC on the FPGA.** Joystick POT0/POT1 lines from
  J10/J11 go to the RP2040's onboard ADC (GPIO26-29), not the FPGA.

## U4 pin → FPGA pin → net cross-reference

The "Notes" column links the net to the off-board destination component.

| U4  | Net               | 10CL025 pin | DIFFIO    | Notes |
|----:|-------------------|-------------|-----------|-------|
|   7 | FPGA_DVI_CLK_P    | R11         | B17p      | HDMI J12.10 (TMDS CLK+) |
|   8 | FPGA_DVI_CLK_N    | T11         | B17n      | HDMI J12.12 (TMDS CLK−) |
|   9 | FPGA_DVI_TX0_P    | R12         | B18p      | HDMI J12.7  (TMDS D0+, blue+sync) |
|  10 | FPGA_DVI_TX0_N    | T12         | B18n      | HDMI J12.9  (TMDS D0−) |
|  15 | FPGA_DVI_TX1_P    | R13         | B20p      | HDMI J12.4  (TMDS D1+, green) |
|  16 | FPGA_DVI_TX1_N    | T13         | B20n      | HDMI J12.6  (TMDS D1−) |
|  18 | FPGA_DVI_TX2_P    | T14         | B23p      | HDMI J12.1  (TMDS D2+, red) |
|  20 | FPGA_DVI_TX2_N    | T15         | B23n      | HDMI J12.3  (TMDS D2−) |
|  25 | AUDIO_L           | N15         |           | RC filter → J3 (3.5mm jack) |
|  26 | SW_RESET          | P16         |           | SW3 (active-low) |
|  28 | SW_OPTION         | N16         |           | SW4 |
|  29 | AUDIO_R           | L16         |           | RC filter → J3 |
|  30 | SW_SELECT         | L13         |           | SW5 |
|  32 | SW_START          | K15         |           | SW6 |
|  38 | RM2_SPI_CLK       | F14         |           | RPi Radio Module 2 (U6.3) |
|  39 | RM2_SPI_DI        | J16         |           | U6.5 (FPGA → RM2, MOSI) |
|  40 | RM2_SPI_DO        | J15         |           | U6.6 (RM2 → FPGA, MISO) |
|  41 | RM2_SPI_CS        | G16         |           | U6.9 |
|  42 | RM2_NIRQ          | G15         |           | U6.10 (active-low interrupt) |
|  43 | RM2_WIFI_ON       | F16 (nCEO)  |           | U6.12 — needs CYCLONEII_RESERVE_NCEO |
|  44 | RM2_BLUETOOTH_ON  | F15         |           | U6.13 |
|  46 | CD                | D16         |           | SD card detect (J7.9, active-low) |
|  47 | DAT1              | D14         |           | SD card DAT1 (J7.8) |
|  48 | DAT0              | C14         |           | SD card DAT0 / SPI MISO (J7.7) |
|  49 | CLK               | C16         |           | SD card CLK (J7.5) |
|  50 | CMD               | C15         |           | SD card CMD / SPI MOSI (J7.3) |
|  51 | DAT3              | B16         |           | SD card DAT3 / SPI CS (J7.2) |
|  52 | DAT2              | A15         |           | SD card DAT2 (J7.1) |
|  76 | JS2_RIGHT         | D3          |           | J11 DB9 pin 4 |
|  78 | JS2_LEFT          | B3          |           | J11 DB9 pin 3 |
|  80 | JS2_DOWN          | B4          |           | J11 DB9 pin 2 |
|  81 | JS2_TRIG          | F8          |           | J11 DB9 pin 6 |
|  82 | JS2_UP            | A2          |           | J11 DB9 pin 1 |
|  83 | GPIO25            | A4          |           | RP2040 GPIO25 (U1.26) |
|  85 | GPIO24            | D6          |           | RP2040 GPIO24 (U1.25) |
|  86 | GPIO4             | C6          |           | RP2040 GPIO4  (U1.5) |
|  87 | GPIO23            | B5          |           | RP2040 GPIO23 (U1.24) |
|  88 | GPIO5             | E7          |           | RP2040 GPIO5  (U1.6) |
|  89 | GPIO22            | A5          |           | RP2040 GPIO22 (U1.23) |
|  90 | GPIO10            | B6          |           | RP2040 GPIO10 (U1.11) |
|  91 | GPIO21            | A6          |           | RP2040 GPIO21 (U1.22) |
|  92 | GPIO11            | B7          |           | RP2040 GPIO11 (U1.12) — also breaks out to U5/J5 |
|  93 | GPIO20            | E8          |           | RP2040 GPIO20 (U1.21) |
|  94 | GPIO12            | A7          |           | RP2040 GPIO12 (U1.13) — also breaks out to U5/J5 |
|  95 | FPGA_DI           | D8          |           | RP2040→FPGA SPI MOSI (U1.20) |
|  96 | FPGA_CLK          | C8          |           | RP2040→FPGA SPI CLK  (U1.19) |
|  97 | FPGA_CSN          | C9          |           | RP2040→FPGA SPI CS   (U1.18, active-low) |
|  98 | FPGA_DO           | D9          |           | FPGA→RP2040 SPI MISO (U1.17) |
|  99 | GPIO15            | F9          |           | RP2040 GPIO15 (U1.16) |
| 100 | GPIO14            | E9          |           | RP2040 GPIO14 (U1.15) |
| 101 | GPIO13            | E10         |           | RP2040 GPIO13 (U1.14) |
| 102 | JS1_RIGHT         | E11         |           | J10 DB9 pin 4 |
| 107 | JS1_DOWN          | A10         |           | J10 DB9 pin 2 |
| 108 | JS1_LEFT          | B10         |           | J10 DB9 pin 3 |
| 109 | JS1_TRIG          | C11         |           | J10 DB9 pin 6 |
| 110 | JS1_UP            | D11         |           | J10 DB9 pin 1 |

U4 pins **17, 19, 21–24, 27, 31, 33–37, 45, 53–60** (first header) and
**71–75, 77, 79, 84, 103–106, 111–124, 127, 128** (second header) are
unconnected on this board. All four HDMI TMDS pairs are true bank-4
DIFFIO pairs (B17/B18/B20/B23) on adjacent connector pins.

## RP2040 control flow

The RP2040-STAMP (U1) handles:
- USB host for keyboard (J8 = USB-A double connector to host devices)
- USB device link to development PC (J4 = USB-C, exposes CDC serial)
- Paddle ADC for J10/J11 POT0/POT1 lines (RP2040 GPIO26–29 onboard ADC)
- JTAG header J2 (TMS/TCK/TDI/TDO) for RP2040 SWD/JTAG
- Slide switch SW7 (likely BOOTSEL or power-mode) on net `$1N8384`
- Push-button SW2 (likely RUN/RESET) on net `$1N84409`

The RP2040 communicates with the FPGA via the **FPGA_DI/CLK/CSN/DO**
SPI link (U4.95–98 — FPGA is the slave). A 13-line general-purpose
GPIO bus (GPIO4/5, 10–15, 20–25) provides parallel signalling that the
RP2040 can use for IRQ-style notifications, sideband control, or as a
faster alternative to SPI for bulk transfers.

## Comparison vs V1.1

| Subsystem      | V1.1                       | RP2040-STAMP-HDMI-LG       |
|----------------|----------------------------|----------------------------|
| Video out      | VGA (5-bit resistor DAC)   | HDMI (4× TMDS pairs)       |
| Keyboard       | CH376T USB host (chip)     | RP2040-STAMP USB host      |
| SD card        | CH376T (SPI mode)          | Direct FPGA SDIO 4-bit     |
| Paddle ADC     | MCP3208 (FPGA SPI)         | RP2040 onboard ADC         |
| User LEDs      | 8× base + 1× core          | 1× core only (+ power LED) |
| External SRAM  | IS63LV1024L 128K×8         | (removed — SDRAM only)     |
| WiFi / BT      | none                       | RPi Radio Module 2 (option)|
| Host serial    | CH340 UART (U9.71–73)      | RP2040 USB-CDC via J4      |
| Supported FPGAs| EP4CGX150 / 10CL025 / XC7A100T | 10CL025 only            |
