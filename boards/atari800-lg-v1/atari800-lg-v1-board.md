# ATARI-800-LG-V1 Base Board

## Overview

Prototype base board for QMTECH EP4CGX150 core board. Provides Atari 800
peripherals: VGA output (5-5-5 resistor DAC), two DB-9 joystick ports with
analog paddle inputs (MCP3208 ADC), CH376T USB/SD controller, CH340 UART,
audio output (stereo 3.5mm jack), four tactile switches, and four LEDs.

The QMTECH core board plugs in via dual 32x2 headers. U7 on the base board
spans both QMTECH U4 (pins 1-64) and U5 (pins 65-128).

Schematic: `ATARI-800-LG-V1-Schematic.svg` / `.png`
Netlist: `~/Netlist_ATARI-800-QMTechCB-LG_2026-04-18.enet`

## Complete I/O Pin Mapping

### VGA (5-bit R, 5-bit G, 5-bit B resistor DAC)

Active accent to QMTECH U4 connector (I/O bank 5/6/7).

| Signal | U7 Pin | QMTECH | FPGA Pin |
|--------|--------|--------|----------|
| HSYNC  | 7      | U4:7   | PIN_C21  |
| VSYNC  | 9      | U4:9   | PIN_B23  |
| RED[4] | 32     | U4:32  | PIN_C11  |
| RED[3] | 34     | U4:34  | PIN_A12  |
| RED[2] | 36     | U4:36  | PIN_A11  |
| RED[1] | 38     | U4:38  | PIN_A10  |
| RED[0] | 40     | U4:40  | PIN_B9   |
| GRN[4] | 20     | U4:20  | PIN_B18  |
| GRN[3] | 22     | U4:22  | PIN_B17  |
| GRN[2] | 24     | U4:24  | PIN_A16  |
| GRN[1] | 26     | U4:26  | PIN_A15  |
| GRN[0] | 28     | U4:28  | PIN_C14  |
| BLU[4] | 8      | U4:8   | PIN_B22  |
| BLU[3] | 10     | U4:10  | PIN_A23  |
| BLU[2] | 12     | U4:12  | PIN_A22  |
| BLU[1] | 14     | U4:14  | PIN_B19  |
| BLU[0] | 16     | U4:16  | PIN_A20  |

### Audio (stereo 3.5mm jack, RC filtered PWM/sigma-delta)

| Signal  | U7 Pin | QMTECH | FPGA Pin |
|---------|--------|--------|----------|
| AUDIO_L | 29     | U4:29  | PIN_C13  |
| AUDIO_R | 31     | U4:31  | PIN_C12  |

Audio path: FPGA pin -> 560R -> capacitor -> 3.5mm jack.
Cross-coupled 1K between L and R channels.

### CH376T (USB host + SD card, SPI mode)

Active accent to QMTECH U4 connector.

| Signal       | U7 Pin | QMTECH | FPGA Pin | Direction |
|--------------|--------|--------|----------|-----------|
| CH376T_CK    | 43     | U4:43  | PIN_A7   | FPGA->CH376T |
| CH376T_DI    | 46     | U4:46  | PIN_B6   | FPGA->CH376T (MOSI) |
| CH376T_DO    | 45     | U4:45  | PIN_B7   | CH376T->FPGA (MISO) |
| CH376T_CS    | 44     | U4:44  | PIN_A6   | FPGA->CH376T |
| CH376T_INT#  | 35     | U4:35  | PIN_B11  | CH376T->FPGA (active low) |
| CH376T_RSTI  | 37     | U4:37  | PIN_B10  | FPGA->CH376T (active high) |
| CH376T_SPI#  | 39     | U4:39  | PIN_C10  | FPGA->CH376T (low=SPI mode) |
| CH376T_TXD   | 42     | U4:42  | PIN_A8   | CH376T->FPGA (UART mode, optional) |
| CH376T_RXD   | 41     | U4:41  | PIN_A9   | FPGA->CH376T (UART mode, optional) |

**Gotcha:** SD card DI/DO are swapped on V1 PCB (see ch376t.md gotcha #9).
Physical wire swap required on V1 prototype.

### UART (CH340 USB-serial)

Active accent to QMTECH U5 connector (I/O bank 3/4).

| Signal   | U7 Pin | QMTECH | FPGA Pin |
|----------|--------|--------|----------|
| UART_TXD | 73     | U5:9   | PIN_AC21 |
| UART_RXD | 71     | U5:7   | PIN_AF24 |
| UART_DTR | 72     | U5:8   | PIN_AF25 |

Note: UART_TXD is FPGA->CH340 RXD, UART_RXD is CH340 TXD->FPGA.

### Joystick 1 (active low, active accent to U5)

DB-9 connector J4. Active low with 3.3V pull-ups.

| Signal    | U7 Pin | QMTECH | FPGA Pin | DB-9 Pin |
|-----------|--------|--------|----------|----------|
| JS1_UP    | 110    | U5:46  | PIN_AF9  | 1        |
| JS1_DOWN  | 107    | U5:43  | PIN_AC10 | 2        |
| JS1_LEFT  | 108    | U5:44  | PIN_AD10 | 3        |
| JS1_RIGHT | 105    | U5:41  | PIN_AF11 | 4        |
| JS1_TRIG  | 109    | U5:45  | PIN_AE9  | 6        |

Analog paddles via MCP3208 ADC (U5): JS1_POT0 (pin 9), JS1_POT1 (pin 5).

### Joystick 2 (active low, active accent to U5)

DB-9 connector J5. Active low with 3.3V pull-ups.

| Signal    | U7 Pin | QMTECH | FPGA Pin | DB-9 Pin |
|-----------|--------|--------|----------|----------|
| JS2_UP    | 82     | U5:18  | PIN_AF21 | 1        |
| JS2_DOWN  | 80     | U5:16  | PIN_AE21 | 2        |
| JS2_LEFT  | 78     | U5:14  | PIN_AF22 | 3        |
| JS2_RIGHT | 76     | U5:12  | PIN_AF23 | 4        |
| JS2_TRIG  | 79     | U5:15  | PIN_AD20 | 6        |

Analog paddles via MCP3208 ADC (U5): JS2_POT0 (pin 9), JS2_POT1 (pin 5).

### Console Switches (active low, active accent to U5)

Tactile push buttons, active low (button press connects to GND).

| Signal | U7 Pin | QMTECH | FPGA Pin | Function |
|--------|--------|--------|----------|----------|
| SW2    | 100    | U5:36  | PIN_AE15 | Reset    |
| SW3    | 102    | U5:38  | PIN_AD15 | Option   |
| SW4    | 104    | U5:40  | PIN_AD14 | Select   |
| SW5    | 106    | U5:42  | PIN_AF12 | Start    |

### LEDs (active high, active accent to U5)

Through 3K current-limiting resistors.

| Signal | U7 Pin | QMTECH | FPGA Pin |
|--------|--------|--------|----------|
| LED_D2 | 97     | U5:33  | PIN_AC16 |
| LED_D3 | 99     | U5:35  | PIN_AE14 |
| LED_D4 | 101    | U5:37  | PIN_AC15 |
| LED_D5 | 103    | U5:39  | PIN_AC14 |

### Paddle ADC (MCP3208, SPI)

MCP3208 12-bit 8-channel ADC (U5) for analog paddle inputs.

| Signal     | U7 Pin | QMTECH | FPGA Pin |
|------------|--------|--------|----------|
| ADC_SPI_CK | TBD    | TBD    | TBD      |
| ADC_SPI_DI | TBD    | TBD    | TBD      |
| ADC_SPI_DO | TBD    | TBD    | TBD      |
| ADC_SPI_SEL| TBD    | TBD    | TBD      |

ADC channels: CH0=JS1_POT1, CH1=JS1_POT0, CH2=JS2_POT1, CH3=JS2_POT0.

## Core Board

- **FPGA**: EP4CGX150DF27I7 (Cyclone IV GX, 149,760 LEs, 6,635 Kbit BRAM)
- **Clock**: 50 MHz oscillator (PIN_B14)
- **SDRAM**: W9825G6JH6 32 MB 16-bit (on core board)
- **LEDs**: 2 on core board (PIN_A25, PIN_A24)

## Power

- 5V input via barrel jack (J1, 2.5mm ID)
- SW1 power switch
- U2: DSN-MINI-360 buck converter 5V (for USB, joystick pull-ups)
- U4: DSN-MINI-360 buck converter 3.3V (FPGA, CH376T, CH340, ADC)

## Known Issues (V1 Prototype)

1. SD card DI/DO wires swapped — CH376T SD_DI (its MISO) connected to
   SD module DI (also MISO). Physical wire swap needed.
2. No direct FPGA-to-SD-card SPI path — all SD access goes through CH376T.
3. Paddle ADC pins not yet mapped to FPGA (need schematic trace for SPI bus).
