# ATARI-800-QMTechCB-LG-V1.1 — U9 connector pin mapping

V1.1 of the LG base board uses a single 128-pin FPGA connector U9 that mates
with the two 32×2 headers of a QMTECH core board. Pins 1–64 of U9 align with
the core board's first header (e.g. EP4CGX150 U4 / 10CL025 U8 / XC7A100T U4);
pins 65–128 of U9 align with the second header (EP4CGX150 U5 / 10CL025 U7 /
XC7A100T U2).

Convention on V1.1: pins 1,2,5,6,61,62,65,66,69,70,125,126 = GND;
pins 3,4,67,68 = 3V3; pins 63,64,127,128 = VIN. All other pins are signals.

Source: `Netlist_ATARI-800-QMTechCB-LG-V1_1_2026-04-28.enet` (KiCad-exported
netlist for U9). FPGA pin tables from
[`qmtech-ep4cgx150-board.md`](../../jop-spinalhdl/docs/boards/qmtech-ep4cgx150-board.md),
[`/srv/git/qmtech/QMTECH_Cyclone10_10CL006_025/10CL025YU256/Connectors.csv`](file:///srv/git/qmtech/QMTECH_Cyclone10_10CL006_025/10CL025YU256/Connectors.csv),
and [`qmtech-xc7a100t-board.md`](../../jop-spinalhdl/docs/boards/qmtech-xc7a100t-board.md).

## Header → FPGA pin convention

| Header  | EP4CGX150 | 10CL025 | XC7A100T |
|---------|-----------|---------|----------|
| First   | U4        | U8      | U4       |
| Second  | U5        | U7      | U2       |
| I/O range | pins 7–60 | pins 7–60 (U7: 7–46, 47–60 NC) | pins 7–60 |

All three core boards use the same convention: pins 1–2 = GND, pins 3–4 =
VCCO/3V3, pins 5–6 = GND, pins 7–60 = I/O, pins 61–62 = GND, pins 63–64 =
VIN. (Note: the QMTECH XC7A100T board doc previously said pins 5–58 are
I/O — that's an off-by-2 error; the schematic in
`/srv/git/qmtech/QMTECH_XC7A75T-100T-200T_Core_Board/XC7A100T/U4.png`
confirms I/O is at pins 7–60.)

## V1.1 fixes versus V1

- **SD card DI/DO swap fixed.** V1 wired CH376T MOSI to the SD module's
  MISO pin (and vice-versa). On V1.1 the four SD signals (CS, CK, DI, DO)
  are correctly routed at U9.46–49.
- **10CL025 input-only clock pins avoided.** Cyclone 10 LP pins A8/B8/A9/B9
  (U7 pins 39–42) are dedicated clock inputs without weak pull-ups and are
  unsuitable as general I/O. V1.1 leaves U9 pins 103–106 unconnected, so
  no signal is forced onto those FPGA pins.
- **U9 pins 111–124 unconnected.** Aligns with 10CL025 U7 pins 47–60 which
  are NC on the core board — clean fit.
- **8 user LEDs.** V1 had only 2 (on the core board); V1.1 routes 8 base-board
  user LEDs (LED0–7) to U9.53–60.

## V1.1 U9 → FPGA pin cross-reference

10CL025 column shows the Cyclone 10 LP pin name (e.g. `R11`); for clock pins
the prefix `CLKn_` has been stripped — the underlying physical pin is shown.
NC means the V1.1 connector pin lands on a NC / power pin of the core board.

| U9  | Signal | EP4CGX150 (Cyc IV GX) | 10CL025 (Cyc 10 LP) | XC7A100T (Artix-7) | Notes |
|----:|--------|-----------------------|----------------------|---------------------|-------|
|   7 | VSYNC          | C21  | R11  | B5   | |
|   8 | HSYNC          | B22  | T11  | A5   | |
|   9 | BLU3           | B23  | R12  | B4   | |
|  10 | BLU4           | A23  | T12  | A4   | |
|  11 | BLU1           | B21  | M10  | A3   | |
|  12 | BLU2           | A22  | P9   | A2   | |
|  13 | GRN4           | C19  | P11  | D4   | |
|  14 | BLU0           | B19  | N11  | C4   | |
|  15 | GRN2           | A21  | R13  | C2   | |
|  16 | GRN3           | A20  | T13  | B2   | |
|  17 | GRN0           | A19  | R14  | E5   | |
|  18 | GRN1           | A18  | T14  | D5   | |
|  19 | RED3           | C17  | N12  | C1   | |
|  20 | RED4           | B18  | T15  | B1   | |
|  21 | RED1           | C16  | N14  | E1   | |
|  22 | RED2           | B17  | P14  | D1   | |
|  23 | SRAM_A0        | A17  | P15  | F2   | |
|  24 | RED0           | A16  | R16  | E2   | |
|  25 | SRAM_A2        | B15  | N15  | G4   | |
|  26 | SRAM_A1        | A15  | P16  | F4   | |
|  27 | SRAM_CE#       | C15  | L15  | G2   | |
|  28 | SRAM_A3        | C14  | N16  | G1   | |
|  29 | AUDIO_L        | C13  | L16  | J4   | |
|  30 | SRAM_D0        | B13  | L13  | H4   | |
|  31 | AUDIO_R        | C12  | L14  | H2   | |
|  32 | SRAM_D1        | C11  | K15  | H1   | |
|  33 | SRAM_D3        | A13  | K16  | H9   | |
|  34 | SRAM_D2        | A12  | J13  | G9   | |
|  35 | CH376T_KB_INTN | B11  | J14  | M2   | |
|  36 | SRAM_WE#       | A11  | F13  | L2   | |
|  37 | CH376T_KB_RESI | B10  | D12  | L5   | |
|  38 | SRAM_A4        | A10  | F14  | K5   | |
|  39 | CH376T_KB_DO   | C10  | J16  | M4   | |
|  40 | SRAM_A5        | B9   | J15  | L4   | |
|  41 | CH376T_KB_CK   | A9   | G16  | N3   | |
|  42 | CH376T_KB_DI   | A8   | G15  | N2   | |
|  43 | CH376T_SD_INTN | A7   | F16  | M6   | |
|  44 | CH376T_KB_CS   | A6   | F15  | M5   | XC7A100T pin is M5_VREF |
|  45 | CH376T_SD_RESI | B7   | D15  | K1   | |
|  46 | CH376T_SD_DO   | B6   | D16  | J1   | |
|  47 | CH376T_SD_DI   | B5   | D14  | R3   | |
|  48 | CH376T_SD_CK   | A5   | C14  | P3   | |
|  49 | CH376T_SD_CS   | B4   | C16  | T4   | |
|  50 | CH376T_12MHZ   | A4   | C15  | T3   | |
|  51 | SRAM_A7        | C5   | B16  | P6   | |
|  52 | SRAM_A6        | C4   | A15  | P5   | XC7A100T pin is P5_VREF |
|  53 | LED1           | A3   | B14  | N1   | |
|  54 | LED0           | A2   | A14  | M1   | |
|  55 | LED3           | B2   | B13  | R1   | |
|  56 | LED2           | B1   | A13  | P1   | |
|  57 | LED5           | D1   | B12  | T2   | |
|  58 | LED4           | C1   | A12  | R2   | |
|  59 | LED7           | E2   | B11  | U2   | |
|  60 | LED6           | E1   | A11  | U1   | |
|  71 | RXD            | AF24 | G1   | D26  | base-board side; FPGA UART_TX |
|  72 | DTR            | AF25 | G2   | E26  | |
|  73 | TXD            | AC21 | D1   | D25  | base-board side; FPGA UART_RX |
|  74 | SW_START       | AD21 | C2   | E25  | |
|  75 | SW_SELECT      | AE23 | B1   | G26  | |
|  76 | JS2_RIGHT      | AF23 | D3   | H26  | |
|  77 | SW_OPTION      | AE22 | C3   | E23  | |
|  78 | JS2_LEFT       | AF22 | B3   | F23  | |
|  79 | SW_RESET       | AD20 | A3   | F22  | |
|  80 | JS2_DOWN       | AE21 | B4   | G22  | |
|  81 | JS2_TRIG       | AF20 | F8   | J26  | |
|  82 | JS2_UP         | AF21 | A2   | J25  | |
|  83 | SRAM_A15       | AE19 | A4   | G21  | |
|  84 | SRAM_A16       | AF19 | E6   | G20  | |
|  85 | SRAM_A13       | AC19 | D6   | H22  | |
|  86 | SRAM_A14       | AD19 | C6   | H21  | |
|  87 | SRAM_D7        | AE18 | B5   | J21  | |
|  88 | SRAM_OE#       | AF18 | E7   | K21  | |
|  89 | SRAM_D5        | AC18 | A5   | K26  | |
|  90 | SRAM_D6        | AD18 | B6   | K25  | |
|  91 | SRAM_A12       | AE17 | A6   | K23  | |
|  92 | SRAM_D4        | AF17 | B7   | K22  | |
|  93 | SRAM_A10       | AC17 | E8   | M26  | |
|  94 | SRAM_A11       | AD17 | A7   | N26  | |
|  95 | ADC_SPI_SEL    | AF15 | D8   | L23  | |
|  96 | ADC_SPI_DI     | AF16 | C8   | L22  | |
|  97 | ADC_SPI_DO     | AC16 | C9   | P26  | |
|  98 | ADC_SPI_CK     | AD16 | D9   | R26  | |
|  99 | SRAM_A8        | AE14 | F9   | M25  | |
| 100 | SRAM_A9        | AE15 | E9   | M24  | |
| 102 | JS1_RIGHT      | AD15 | E11  | N21  | |
| 107 | JS1_DOWN       | AC10 | A10  | T25  | |
| 108 | JS1_LEFT       | AD10 | B10  | T24  | |
| 109 | JS1_TRIG       | AE9  | C11  | V21  | |
| 110 | JS1_UP         | AF9  | D11  | U21  | |

U9 pins 101, 103–106, 111–124 are unconnected on V1.1 (would land on
10CL025 input-only clocks or NC pads on smaller core boards).

## Signal naming notes

- **RXD / TXD** at U9.71/73 are labelled from the **base-board / CH340**
  side. From the FPGA's perspective, U9.71 is the FPGA's UART transmit
  output (drives the CH340's RXD input → host PC), and U9.73 is the FPGA's
  UART receive input (driven by CH340's TXD output).
- **SRAM** is the on-board IS63LV1024L 128K×8 async SRAM (U10).
- **CH376T_KB** = USB host device used as keyboard input; **CH376T_SD** =
  SD card socket driven by a second CH376T running in SPI mode.
- **CH376T_12MHZ** is an FPGA **output** that drives the CH376T's clock
  input — the FPGA's PLL replaces the CH376T's onboard crystal so the
  module runs from a clean, derived clock.
- **led[0..7]** in the table refers to the **base-board** LEDs at U9.53–60
  (D1–D8). The QMTECH core boards each have their own onboard user LEDs
  on dedicated FPGA pins outside the U9 connector — those are named
  `led_core[]` in the per-FPGA pin assignment files to avoid ambiguity.
- **ADC_SPI** drives the MCP3208 ADC for paddle pots (4 channels:
  JS1_POT0/1, JS2_POT0/1).
