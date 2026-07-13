# Colorlight i9+ v6.1 (Artix-7 XC7A50T) — board notes

FPGA module: **XC7A50T-FGG484** on a **DDR2-SODIMM-200P** module (Vivado flow,
`/opt/xilinx/2025.2`). Same socket as the ECP5 i5 (LFE5U-25F) and i9 (LFE5U-45F)
modules — the basis for one base board that accepts either family. On-module:
8 MB SDRAM (EM638325BK-6H, 32-bit), 2 MB SPI flash, 2× Gigabit PHY (B50612D).

The Atari core uses ~23 % LUT / 17 % BRAM here (see `synth_util.rpt`), so 1080p +
supervisor + heavier future cores fit with room. The Artix's hard OSERDESE2 + MMCM
do **native 1080p60** comfortably (1.485 Gbps/lane) — which the ECP5 -6 cannot
(its PLL caps ~400 MHz and the serializer needs ~742 MHz; 720p is the ECP5 ceiling).

## HDMI — differential pairs common to BOTH the ECP5 and Artix modules

For a **single base board** that drives clean differential HDMI on *either* module,
the HDMI TMDS lanes must land on SODIMM pins that are a **true differential pair on
both** the Artix (i9+) *and* the ECP5 (i5/i9). There are exactly four such pairs —
enough for HDMI (clock + 3 data) — all in **Artix I/O bank 34**:

| Lane | SODIMM + (P) | SODIMM − (N) | Artix P / N | ECP5 P / N | Ax bank | Polarity |
|------|:---:|:---:|:---:|:---:|:---:|---|
| CLK  | **69**  | **71**  | AA1 / AB1 | U18 / U17 | 34 | matched |
| D0   | **83**  | **79**  | Y3 / AA3  | L18 / M18 | 34 | matched |
| D1   | **95**  | **99**  | Y6 / AA6  | G20 / F20 | 34 | **swapped** — invert on ECP5 |
| D2   | **109** | **111** | AA8 / AB8 | E19 / D20 | 34 | **swapped** — invert on ECP5 |

- **SODIMM pins** are the module edge-connector pins (all on the top/odd row →
  routable as adjacent pairs on the base board).
- **Balls** are the FPGA package balls: `Artix P/N` on the XC7A50T-FGG484, `ECP5 P/N`
  on the LFE5U-\*F-BG381 (i5 25F and i9 45F share the BG381 package/pinout).
- **Polarity**: the "+" trace goes to the SODIMM-P pin above. On CLK/D0 that pin is
  the True(P) side on both chips. On **D1/D2 the ECP5 has P/N swapped** vs the Artix,
  so on the **ECP5 build only**, invert those two lanes (invert the 10-bit TMDS symbol,
  or swap the True/Comp assignment in the `.lpf`). The Artix build needs no inverts.

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

## Provenance (how the pairs were derived)
- Artix diff pairs: Vivado `get_package_pins … DIFF_PAIR_PIN` on `xc7a50tfgg484-1`.
- ECP5 diff pairs: prjtrellis `iodb.json` (CABGA381) — PIO A/B and C/D at the same
  row/col are a pair; A/C = True(P).
- SODIMM↔ball maps: the `DDR2-SODIMM-200P` tables in
  `Colorlight-FPGA-Projects/colorlight_i9plus_v6.1.md` (Artix) and `colorlight_i9_v7.2.md`
  (ECP5), then intersected for pin-pairs that are differential on both.
