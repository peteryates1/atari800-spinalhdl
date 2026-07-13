# Colorlight i9+ v6.1 (Artix-7 XC7A50T) — board notes

FPGA module: **XC7A50T-FGG484** on a **DDR2-SODIMM-200P** module (Vivado flow,
`/opt/xilinx/2025.2`). Same socket as the ECP5 i5 (LFE5U-25F) and i9 (LFE5U-45F)
modules — the basis for one base board that accepts either family. On-module:
8 MB SDRAM (M12L64322A-6B, 32-bit SDR, 166 MHz), SPI flash, 2× Gigabit PHY (B50612D).

The Atari core uses ~23 % LUT / 17 % BRAM here (see `synth_util.rpt`), so 1080p +
supervisor + heavier future cores fit with room. The Artix's hard OSERDESE2 + MMCM
do **native 1080p60** comfortably (1.485 Gbps/lane) — which the ECP5 -6 cannot
(its PLL caps ~400 MHz and the serializer needs ~742 MHz; 720p is the ECP5 ceiling).

## HDMI — differential pairs common to BOTH the ECP5 and Artix modules

For a **single base board** that drives clean differential HDMI on *either* module,
the HDMI TMDS lanes must land on SODIMM pins that are a **true differential pair on
both** the Artix (i9+) *and* the ECP5 (i5/i9). There are exactly four such pairs —
enough for HDMI (clock + 3 data) — all in **Artix I/O bank 34**:

| Lane | SODIMM balls | Artix P / N | ECP5 P / N | Ax bank |
|------|:---:|:---:|:---:|:---:|
| CLK  | 69 / 71  | AA1 / AB1 | U18 / U17 | 34 |
| TX0  | 83 / 79  | Y3 / AA3  | L18 / M18 | 34 |
| TX1  | 95 / 99  | Y6 / AA6  | G20 / F20 | 34 |
| TX2  | 109 / 111| AA8 / AB8 | E19 / D20 | 34 |

- **SODIMM pins** are the module edge-connector pins (all on the top/odd row →
  routable as adjacent pairs on the base board).
- **Balls** are the FPGA package balls: `Artix P/N` on the XC7A50T-FGG484, `ECP5 P/N`
  on the LFE5U-\*F-BG381 (i5 25F and i9 45F share the BG381 package/pinout).

### Final base-board connector wiring + per-chip inversion
Chosen (Peter, board layout) so the **HDMI connector needs no P/N swap** — the cable
runs straight through and any polarity fix is done in the FPGA:

| Lane | HDMI − → SODIMM | HDMI + → SODIMM |
|------|:---:|:---:|
| CLK  | 69  | **71**  |
| TX0  | 79  | **83**  |
| TX1  | 95  | **99**  |
| TX2  | 109 | **111** |

Each chip drives its **True(+)** output on a fixed SODIMM pin. Where that True pin is
**not** the connector-**+** pin above, the FPGA must **invert** (drive the complement
stream on the +pin — swap the P/N ball map in the constraints, or invert the lane's
10-bit symbol):

| Lane | HDMI + pin | Artix True(+) on | **Artix inv** | ECP5 True(+) on | **ECP5 inv** |
|------|:---:|:---:|:---:|:---:|:---:|
| CLK  | 71  | 69  | **Invert**  | 69  | **Invert**  |
| TX0  | 83  | 83  | none        | 83  | none        |
| TX1  | 99  | 95  | **Invert**  | 99  | none        |
| TX2  | 111 | 109 | **Invert**  | 111 | none        |

- **The base board is common to both families** — only the **TX1/TX2 inversion flips
  per build**. CLK and TX0 are identical on both chips.
- Why TX1/TX2 differ: those are the *swapped* pairs — Artix and ECP5 put True/Comp on
  **opposite** SODIMM pins there. The connector-+ (99/111) is the **ECP5's** True pin
  but the **Artix's** complement pin. So: **Artix build** inverts CLK+TX1+TX2; **ECP5
  build** inverts CLK only. (Verified against the ECP5 iodb + Artix `DIFF_PAIR_PIN`;
  the method reproduces CLK/TX0 independently and diverges only on the swapped lanes.)

### Driving them
- **Artix (i9+):** `OBUFDS` (true differential), fed by `OSERDESE2` (master+slave for
  10:1) at the 5× serial clock from an `MMCM` → 1080p60. Bank 34 is HR (3.3 V) and has
  clock-capable pins for the `BUFIO` serial clock.
- **ECP5 (i5/i9):** drive the pair with `LVCMOS33D` (3.3 V pseudo-diff, as our working
  `Ecp5DvidOut` already does) or `LVDS` if that bank is set to 2.5 V VCCIO. Because
  these are *matched* pairs (unlike the ext-board's scrambled HDMI pins), routing is
  clean either way. 720p ceiling on the -6 part.

### Why not the stock ext-board HDMI pins
The i9+ ext board wires HDMI to SODIMM 87/91/93/97/101/103/113/115 (Artix balls
AA4/AB5/AA5/AB6/Y7/AB7/Y8/W7). Vivado shows those are **not matched diff pairs** — the
lanes are split across L10/L11/L19/L20/L23 — so it's **pseudo-differential** single-ended
TMDS. Fine at 720p, marginal at 1080p (1.485 Gbps) over a SODIMM + base-board hop. The
four pairs above give **proper differential** signaling for reliable 1080p.

### Caveats before committing copper
- **Free carrier I/O — VERIFIED for the i9+ (no schematic needed).** The
  `colorlight_i9plus_v6.1.md` doc lists both the SODIMM edge pinout *and* every
  on-module peripheral's FPGA balls (SDRAM U6, flash U12, ETH-PHY0/1, LED, clock,
  JTAG — 135 balls). Our 8 HDMI balls (AA1/AB1/Y3/AA3/Y6/AA6/AA8/AB8) appear **only**
  in the edge table and in **none** of the peripheral tables; they're also in the
  opposite package corner (rows Y/AA/AB = bank 34) from the peripherals (rows A–H).
  Source is the community reverse-engineered pinout, not Colorlight's schematic, but
  it's internally consistent (edge vs peripheral tables agree). Unlike the `ETH*`
  pairs (SODIMM 15–36, which go to the PHY), these are clean FPGA I/O.
  - *Optional hardware-definitive check when the board arrives:* JTAG boundary-scan
    (EXTEST) each ball and meter the edge pin, or toggle all 8 while SDRAM/Ethernet/
    flash run and confirm they stay independent.
- **Validate 1080p SI on hardware** — pseudo/true-diff TMDS through a SODIMM connector is
  worth a real test before a board spin.

## On-module SDRAM — M12L64322A-6B
64 Mbit (**8 MB**) SDR SDRAM, 4 banks × 512K × **32-bit**, **166 MHz** (-6). Same
class as the ECP5 modules' EM638325BK-6H, so it's a drop-in for our existing
SpinalHDL SDR controller (`SdramStatemachine`/`SdramArbiter`) — just the M12L64322A
timing constants (tRCD/tRP/tRFC, CL2/3). No new controller architecture.

Fit for our use — **no blockers**:
- **Framebuffer-only, off the Atari's critical path.** On this large part the Atari
  (48 KB RAM + OS + cart) stays in **BRAM**, so the ANTIC-vs-CPU SDRAM contention that
  caused display jitter on the 10CL025 **can't recur** — SDRAM only holds video.
- **Bandwidth OK for 1080p (single-buffer).** 32-bit × 166 MHz = **664 MB/s** peak;
  1080p60 scan-out needs ~373 MB/s (3 B/px) to ~498 MB/s (4 B/px) — comfortable on
  sequential reads (~85 % eff.), with headroom for the tiny Atari writes.
- **Capacity is the one constraint.** 8 MB holds **one** 1080p24 framebuffer (6.2 MB)
  but **not a double-buffer** (12.4 MB); RGB565 double-buffer (8.3 MB) also just misses.
  → single-buffer + genlock to avoid tearing, not page-flipping.
- **Optional if genlocked.** With HDMI genlocked to the Atari + a BRAM line-scaler
  (the ECP5 plan), video needs no framebuffer and the SDRAM is spare headroom.
- I/O is **3.3 V** — set that FPGA bank's VCCIO to 3.3 V. 166 MHz is well above any
  clock we'd run it at.

## Base-board netlist review

Reviewed the exported netlist (`Netlist_Atari_800_Lite_-_Colorlight_Module-LG_*.enet`)
for the shared i5/i9(/i9+) base board: SODIMM socket (CN1), HDMI-A (HDMI1), RP2040-Stamp
(U1), 2× DB9 joystick (J1/J2), microSD (J8), 2× USB-A host + native USB (J4/J6), audio
jack (J11), power (J10/U2/SW1), console keys, ESD arrays (D1/D2).

### Verified good
- **HDMI TMDS → SODIMM** matches the pin map above exactly, correct polarity on all
  four pairs: connector → 100 nF AC-coupling cap (C1–C8) → SODIMM (CLK 71/69, TX0
  83/79, TX1 99/95, TX2 111/109).
- **Joysticks** dir/trigger → FPGA (CN1); **POT** lines → RP2040 ADC (GPIO26–29).
  **Console keys** RESET/OPTION/SELECT/START → FPGA. **Audio** L/R → FPGA → J11.
- **Native USB → J6** (real connector); **2× USB-A host** on PIO GPIO4/5 + 6/7.
  **SWD → U3**, RESET → SW3, BOOTSEL → SW2.

### RP2040 pin map on this board (differs from the QMTech board — firmware re-pin needed)
| Function | RP2040 GPIO |
|---|---|
| microSD (SPI0) | CIPO=20, CS=21, SCK=22, COPI=23, **CD=19** |
| RP2040↔FPGA link | GPIO10–18 → CN1.67/65/63/61/59/57/51/49/41 |
| FPGA JTAG (loader) | GPIO0–3 (TMS/TCK/TDI/TDO) → header **J5** |
| USB host (PIO) | 4/5 and 6/7 → J4 |
| Paddle POT ADC | GPIO26–29 → J1/J2 POT |

`sd_spi.c` must move from SPI1/GPIO10-13 to **SPI0/GPIO20-23** for this board (GPIO10-18
are the FPGA link here). CD is wired (GPIO19), so card-detect can be re-enabled.

### Decisions taken
- **ESD arrays (D1/D2) moved to the connector side of the AC caps** — correct place to
  clamp ESD at the entry point.
- **HDMI +5 V (pin 18):** add a **ferrite bead** (≈600 Ω@100 MHz, <0.5 Ω DC, ≥0.5 A) in
  series for EMI, plus a **resettable polyfuse, 150 mA hold (~300 mA trip), ≥6 V** for
  short protection (spec load is ≤55 mA; a plain series R would have to be ≤5 Ω and gives
  no fault protection). Example: Littelfuse 1206L015 / Bourns MF-MSMF015-2.
- **Joystick pin 7 stays on 3V3** (not 5 V): paddle pots are **ratiometric**, so a 3.3 V
  top rail swings the POT lines 0→3.3 V = full ADC scale with **no divider and no
  over-voltage** on the (non-5 V-tolerant) RP2040 ADC pins. Best if that 3V3 is the ADC
  VREF rail. (5 V there would mandate dividers on all four POT lines — avoided.)

### Open items before fab
- **FPGA configuration path:** RP2040 JTAG (GPIO0–3) reaches header **J5 only, not the
  FPGA (CN1)** — so the current design's *RP2040-loads-FPGA-from-SD over JTAG* (SD-side
  boot) is **not wired**. OK **if** the FPGA boots from its **on-module flash** and J5 is
  the external JTAG programming header — confirm this is the intent.
- **Declare the 4 TMDS pairs as length-matched 100 Ω differential pairs** in the PCB tool
  (netlist `differentialPair` list is empty; may just be absent from the export — verify).
- **DDC (SCL/SDA) + HPD are not routed to the FPGA** → no EDID read / hot-plug sense.
  Fine for fixed-resolution output; EDID would need DDC to the FPGA with 3.3 V level
  shifting (it's pulled to 5 V).
- Confirm the **RP2040-Stamp 3V3 regulator** budget covers SD (~100 mA peak) + joystick
  pull-ups; and that 2× PIO-USB host + the FPGA-config PIO coexist.

## Provenance (how the pairs were derived)
- Artix diff pairs: Vivado `get_package_pins … DIFF_PAIR_PIN` on `xc7a50tfgg484-1`.
- ECP5 diff pairs: prjtrellis `iodb.json` (CABGA381) — PIO A/B and C/D at the same
  row/col are a pair; A/C = True(P).
- SODIMM↔ball maps: the `DDR2-SODIMM-200P` tables in
  `Colorlight-FPGA-Projects/colorlight_i9plus_v6.1.md` (Artix) and `colorlight_i9_v7.2.md`
  (ECP5), then intersected for pin-pairs that are differential on both.
