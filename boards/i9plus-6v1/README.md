# Colorlight i9+ v6.1 (Artix-7 XC7A50T) — board notes

FPGA module: **XC7A50T-FGG484** on a **DDR2-SODIMM-200P** module (Vivado flow,
`/opt/xilinx/2025.2`). Same socket as the ECP5 i5 (LFE5U-25F) and i9 (LFE5U-45F)
modules — the basis for one base board that accepts either family. On-module:
8 MB SDRAM (M12L64322A-6B, 32-bit SDR, 166 MHz), SPI flash, 2× Gigabit PHY (B50612D).

The Atari core uses roughly a quarter of the LUTs and well under a fifth of the
BRAM here, so logic/BRAM are not the constraint.

**HDMI 1080p60 — achievable on a -2 module (corrected 2026-07-20).** An earlier note
here claimed the 50T "caps at 720p, needs a -3." That was wrong — it read a
*conservative* clock spec as a hard wall. A real OSERDESE2 10:1 serializer (the same
Digilent `rgb2dvi` we run at 1080p on the Wukong) placed+routed on the i9+ bank-34
pins at 1080p60 (742.5 MHz serial) shows the **only failing check is the BUFG
min-period on the serial clock — not the OSERDES, not the SODIMM pins:**

| Part | Serial-clock min-period slack | Note |
|---|---|---|
| XC7A50T **-1** | **−0.808 ns** (BUFG rated ~464 MHz) | ~60 % over spec — marginal |
| XC7A50T **-2** | **−0.246 ns** | matches the Wukong |
| XC7A100T **-2** (Wukong, 1080p verified in HW) | **−0.245 ns** | conservative flag; runs fine |

The -2 i9+ has the **same serial-clock margin as the proven Wukong**, whose identical
−0.245 ns "violation" is a known conservative BUFG min-period model — 1080p60 works in
hardware. So a **-2 i9+ does 1080p60**; a **-1** is genuinely marginal (would need a
BUFIO/BUFR serial-clock topology to sidestep the BUFG limit, or fall back to 720p).
Reproduce with `hdmi_test/build_hdmi.tcl` (`-tclargs xc7a50tfgg484-{1,2} s{1,2}`).

**Two things still to confirm before trusting 1080p on this board:** (1) the module's
**actual speed grade** — this doc's pinout note says -2, but verify the chip marking
(the earlier fit-check had defaulted to -1); (2) **signal integrity** of 1.485 Gbps through
the SODIMM + base-board + AC-coupling caps — the FPGA closes timing, but the analog
path over the connector is a separate hardware validation (see the differential-pair /
length-match notes below). At -2 with clean SI, the i9+ is a 1080p60 board like the
Wukong; if SI proves marginal, 720p is the safe fallback (same class as the ECP5 i5).

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

### FPGA configuration (JTAG)
Base-board **J5 → the module's own JTAG header** (flying lead, or pogo), so the RP2040
SD-side FPGA load still works — RP2040 GPIO0–3 = TMS/TCK/TDI/TDO drive J5. JTAG can't
run over the SODIMM: TDO/TMS/TCK are on the edge (pins 68/76/94) but **TDI is not**, so
the module header is mandatory. **The module JTAG header is in a different physical spot
per family**, so fixed pogos are per-module — the J5 header + flying lead is the portable
choice:
- **Artix i9+:** 1×4 pads on the **right edge** (between U6 SDRAM and U12 flash):
  **J2=TDO, J3=TDI, J4=TMS, J5=TCK**.
- **ECP5 i9 / i5:** 1×4 pads at the **top-left corner** (by U17), silk `J30 J32 J31 J27`:
  **J30=TDO, J32=TDI, J31=TMS, J27=TCK**.

#### Artix i9+ module JTAG pad coordinates (measured) + board-placement formula
Calipered on the i9+ v6.1 module, **top side, from the bottom-left corner** (gold-finger
edge = Y datum, left edge = X datum). 1×4 column at **2.54 mm (0.1″)** pitch, **J2 nearest
the gold-finger edge**:

| Pad | Sig | Module (x, y) mm |
|-----|-----|:---:|
| J2  | TDO | 63.84, **8.18**  |
| J3  | TDI | 63.84, **10.72** |
| J4  | TMS | 63.84, **13.26** |
| J5  | TCK | 63.84, **15.80** |

*(J2 x/y measured; J3–J5 stepped +2.54 mm in +Y. Module ≈67.60 mm wide — the standard
DDR2-SODIMM / MO-224 edge.)*

**Board coordinates from an arbitrary connector location.** `(Cx, Cy)` is a *specific
physical point*, not the footprint's arbitrary origin: the **midpoint of the seated
module's gold-finger edge** —
- `Cx` = **slot centerline** (horizontal centre of the 200-contact array = midpoint
  between the two end contacts; the module's width-centre sits here),
- `Cy` = **card-seating line** (Y where the module's gold-finger edge rests — the card-edge
  stop from the connector datasheet's recommended-PCB figure).

Set the connector footprint's origin to this point so its placed coordinate *is* `(Cx, Cy)`;
otherwise add the offset from your origin to it.

*For this board's connector (TE **1473006**, 0.6 mm-pitch 200-pos DDR2 socket — datasheet
`Colorlight-FPGA-Projects/doc/ENG_CD_1473006_M2.pdf`, sheet 2):* `Cx` = the **CONNECTOR
CENTER LINE** (center of symmetry — midway between the ø1.6/ø1.1 end posts and pin 1↔199;
**NOT** the MECHANICAL KEY CENTER LINE, which is offset 3.1 mm), and `Cy` = the
**card-seating line** ≈ midway between the two solder-pad rows (or the insertion edge of the
sheet-1 "RECOMMENDED MATING P.C.B OUTLINE"), good to ~±1 mm.

The module seats **component-side up** (no mirror). Because the connector is horizontal the
module can only sit two ways — socket mounted at 0° or 180° — and **180° is an in-plane
point-reflection through (Cx, Cy) that flips X *and* Y together** (you can't flip one without
the other; treating them independently is wrong):
```
Orientation A (0°)  :  pad X = Cx + 30.04     pad Y = Cy + 8.18 + 2.54·n
Orientation B (180°):  pad X = Cx − 30.04     pad Y = Cy − 8.18 − 2.54·n
                       n = 0..3 → J2,J3,J4,J5   (30.04 = 63.84 − 33.80)
```
Pick the one whose pads land where the module physically sits (check the seated-module
outline): **A** = JTAG column right & *below* the slot, **B** = left & *above*.

**This board is orientation B.** Worked example — footprint recentred on the connector
centre line, Cx = −11.8, Cy = −0.7:

| Pad | Sig | Board (x, y) mm |
|-----|-----|:---:|
| J2  | TDO | −41.84, **−8.88**  |
| J3  | TDI | −41.84, **−11.42** |
| J4  | TMS | −41.84, **−13.96** |
| J5  | TCK | −41.84, **−16.50** |

(Separately: **component-side DOWN** would be a true *reflection* — additionally negate the
X term. And `Cy` must be the **card-seating line**, not the connector-body centre.)

Collision note: the pads are 8.18–15.80 mm off the slot line, so **J2/J3 typically sit in
the connector body/latch zone** — don't place a base-board pad/pogo there (use the flying
lead). *These coords are the Artix i9+ module only; the ECP5 i5/i9 JTAG is at the top-left
corner and needs its own measurement.*

**Chosen approach: a soldered flying lead from J5 to a mating connector at the module
JTAG header** — not pogo pins. J5 already carries GND (pin 1) + TMS/TCK/TDI/TDO, so it's
a 5-wire bundle. Reasons over pogo: no sub-mm placement accuracy needed (Colorlight
publishes no mechanical drawing, so a pogo nest would mean reverse-measuring pad centres
through the SODIMM seating datum), and it's portable to either module — a pogo nest would
be per-family since the headers are in different spots. JTAG is only a few MHz, so a
few-cm lead is fine. The module-end pad order differs per family (above), so use a
per-module adapter or labelled leads. (Photos: `Colorlight-FPGA-Projects/doc/
i9plus-v6.1-top.jpg`, `I9_V7.2_top.jpg`.)

### Open items before fab
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
