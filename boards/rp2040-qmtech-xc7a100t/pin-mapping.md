# QMTech XC7A100T core board — connector pin mapping

FPGA: **XC7A100T** `fgg676`. The core board exposes user IO on two 64-pin 2.54 mm female headers,
silk **U2** and **U4**. This board places **HDMI on U4**.

Sources (all under `/srv/git/qmtech/QMTECH_XC7A75T-100T-200T_Core_Board/XC7A100T/`):
- `Info_SYM_QMTECH XC7A100T CORE BOARD_2026-07-19.csv` — board-pin → FPGA-ball map
  (part `.1` = **U2**, banks 13/14/15; part `.2` = **U4**, banks 34/35).
- `xc7a100tfgg676pkg.txt` — Xilinx package file (ball → bank → differential pair).
- `QMTECH_Artix-7_XC7A100T_Core_Board_User_Manual(Hardware)_V01.pdf` and the schematic PDF.
- `Netlist_ATARI-800-QMTech-XC7A100T-CB-RP2040-STAMP-HDMI_2026-07-19.enet` — the baseboard netlist
  (reviewed; the assignments below are realized in it).

## Header → bank map

| Header (silk) | CSV part | Artix banks | VCCO | Notes |
|---|---|---|---|---|
| **U2** | `.1` | 13 / 14 / 15 (HR) | **hardwired 3V3** (U2 pins 3/4) | pin-1 end = bank 15 |
| **U4** | `.2` | 34 / 35 (HR) | 3.3 V via core-board **R14/R15** (0 Ω, default) | pin-1 end = bank 35 |

> **Silk-label caution.** In an earlier symbol capture U2/U4 were **swapped** vs. the manual; that
> is now fixed so **U4 = banks 34/35** (the `B5/A5/B4/A4…` group). The **FPGA ball names below are
> the anchor** for the XDC regardless of any silk confusion — constrain to the balls, not the pin
> numbers.

## HDMI / TMDS — U4, Bank 35

Four differential pairs, all in **Bank 35**, clustered at the U4 pin-1 end, clock nearest pin 1:

| HDMI signal | FPGA **P** | FPGA **N** | Xilinx pair | Note |
|---|---|---|---|---|
| TMDS Clock  | **B5** | **A5** | bank35 `L15` | nearest U4 pin 1 |
| TMDS Data 0 | **B4** | **A4** | bank35 `L16` | |
| TMDS Data 1 | **A3** | **A2** | bank35 `L20` | |
| TMDS Data 2 | **D4** | **C4** | bank35 `L14` | (SRCC — unused for output) |

- **Bank-35 supply — powered by default, no baseboard wiring needed.** Per the core-board manual
  §2.2.1, banks 34/35 get 3.3 V through **R14/R15 (0 Ω, populated by default)**, so the `VCCO_34_35`
  pins on the U4 header can be left **NC** and `TMDS_33` still works. Only if you *remove* R14/R15
  (to run banks 34/35 at a non-3.3 V level) must you inject that voltage on U4 pins 3/4. **Keep
  R14/R15 populated for HDMI.** (This corrected an earlier "VCCO unconnected = dead bank" worry —
  it isn't, because of R14/R15. The 10CL025 board's equivalent header pins are a plain hardwired
  `3V3` pin, which is why that pattern looked satisfied.)
- Drive with **`IOSTANDARD TMDS_33`** on `OBUFDS`, serialized by `OSERDESE2`. No external resistor
  network needed. Reaches native 1080p60; 720p is trivial.
- **Clock needs no clock-capable pin** (it is an output forwarded from the pixel clock). **Data
  channel order is free** in HDL — reorder Data 0/1/2 across the three data pairs for cleanest
  baseboard routing; only the clock pair is fixed. Keep **P → HDMI+, N → HDMI−** within each pair.
- Bank 35 has **11 complete pairs** total, so alternatives exist — e.g. clock on the MRCC pair
  **E5/D5** (`L13`) if you prefer the clock on a clock-capable ball (not required for output).

### Length matching / impedance (what the core board does vs. what you must do)

- **Core board:** the manual states "*All IOs are precisely designed with length matching*," and
  each pair's P/N land on **adjacent header pins**, so on-board intra-pair skew is small. **But**
  controlled **100 Ω differential** impedance is called out **only for the DDR3** (schematic note
  "100 ohms differential trace impedance"), **not** the headers. Header pairs are length-matched
  but not differential-impedance-controlled. (From documentation claims — no Gerbers/`.brd` in the
  repo to confirm actual trace lengths.)
- **Your baseboard (dominant):** route each U4 → HDMI-jack pair as **100 Ω differential**, tight
  intra-pair, short, and roughly matched pair-to-pair (clock ↔ data). Add HDMI ESD protection at
  the connector.
- HDMI Type-A jack pin order is Data2±(1-3), Data1±(4-6), Data0±(7-9), Clock±(10-12); since channel
  assignment is free in HDL, orient the jack + pick pair→channel to minimize crossover.

## Other core-board (U4/bank-35) signals (from the netlist)

All on **bank 35**, so all covered by the same R14/R15 3.3 V supply as HDMI:

| Function | FPGA ball | Net |
|---|---|---|
| Console RESET  | C2 | `SW_RESET` |
| Console OPTION | B2 | `SW_OPTION` |
| Console SELECT | E5 | `SW_SELECT` |
| Console START  | D5 | `SW_START` |
| Audio L (Σ-Δ)  | G4 | `AUDIO_L` (→ 560 Ω + RC → 3.5 mm jack J5) |
| Audio R (Σ-Δ)  | J4 | `AUDIO_R` |

Joysticks (JS1/JS2 directions + triggers) land on the **U2 header (banks 13/14/15)**; the paddle
POTs go to the RP2040 ADC (see below).

## RP2040-STAMP (U3) assignments (from the netlist)

| GPIO | Use | | GPIO | Use |
|---|---|---|---|---|
| 0–3 | FPGA **JTAG** (TMS/TCK/TDI/TDO) → header **J10** | | 16 | RM2 CLK |
| 4–5 | USB host **port 1** (D−/D+) | | 17 | FPGA link spare |
| 6–7 | USB host **port 2** (D+/D−) | | 18–20 | **SPI0** → FPGA link (CLK/COPI/CIPO) |
| 8–12 | **SPI1** → microSD (CIPO/CS/CLK/COPI/CD) | | 21–25 | FPGA link spares |
| 13 | RM2 ON | | 26–29 | **ADC** — JS1/JS2 POT0/POT1 |
| 14 | RM2 CS | | USB0 | native USB (D−/D+) broken out |
| 15 | RM2 DATA (half-duplex: DataIn/DataOut/nIRQ) | | | |

- **RM2 is direct-wired to the RP2040** (not through the FPGA) — the lesson from the wireless work.
  Half-duplex single DATA on GPIO15. The supervisor firmware needs a `-DBOARD=xc7a100t` pin set with
  the CYW43 overrides ON=13/CS=14/DATA=15/CLK=16, PIO-USB bases at GPIO4 & 6, SPI1 SD on GPIO8–12.
- **SD is RP2040-mastered** on SPI1 (J3 microSD).
- **3V3 for RM2:** RM2 can be fed from its **own dedicated DSN-MINI-360 buck (3.3 V)** *or* the
  core-board 3V3 rail — both optional/selectable — so the RM2 WiFi-on peak (~250–400 mA) need not
  load the core-board regulator. (Good call: RM2 power integrity was the earlier failure mode.)

## Debug / programming

- **SWD (U6, 3-pin JST)** = **SWCLK / GND / SWDIO** (pin 1/2/3). This is the standard Raspberry Pi
  debug-connector order, so a **straight debug-probe cable works — no crossover.** (The 10CL025 V1.0
  board had these swapped; that bug is fixed here. See `memory/project_rp2040_qmtech_swd_swap_bug`.)
- **BOOTSEL** = SW2, **RESET** = SW3 (on the RP2040).
- **FPGA JTAG** is not on the 128-pin bus — RP2040 GPIO0–3 come out to **J10** (GND/TMS/TCK/TDI/TDO);
  a short cable joins J10 → the core board's own **6-pin JTAG header** for RP2040-driven FPGA config.
  The header's 6th pin (VCC/VREF, 3V3) is intentionally **not** on J10 — not needed, since the RP2040
  bit-bangs JTAG at 3.3 V and the FPGA JTAG bank is 3.3 V (no VREF sense / level translation). The
  four JTAG signals + GND are the complete set.

## TODO (later phases)

DDR3 (via MIG) and the reference clock are not yet pinned. Reserve a clock-capable (MRCC/SRCC) ball
for any differential reference-clock *input*.
