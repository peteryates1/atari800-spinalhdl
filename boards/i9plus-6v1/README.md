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
- **Verify these SODIMM pins are free carrier I/O** on both modules (not tied to
  on-module SDRAM/flash/PHY). The module tables label them with plain FPGA balls (a good
  sign) but confirm against the schematics — the `ETH*` pairs (SODIMM 15–36), for
  example, go to the PHY, *not* the FPGA.
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

## Provenance (how the pairs were derived)
- Artix diff pairs: Vivado `get_package_pins … DIFF_PAIR_PIN` on `xc7a50tfgg484-1`.
- ECP5 diff pairs: prjtrellis `iodb.json` (CABGA381) — PIO A/B and C/D at the same
  row/col are a pair; A/C = True(P).
- SODIMM↔ball maps: the `DDR2-SODIMM-200P` tables in
  `Colorlight-FPGA-Projects/colorlight_i9plus_v6.1.md` (Artix) and `colorlight_i9_v7.2.md`
  (ECP5), then intersected for pin-pairs that are differential on both.
