# Colorlight i5 (LFE5U-25F, ECP5) — board notes

The i5 is the ECP5 member of the shared-base-board plan (same DDR2-SODIMM-200 socket as the
i9 and the Artix i9+). On-module: LFE5U-25F, EtronTech EM638325BK-6H 8 MB SDRAM, SPI flash.

Module pinouts, schematics, and the open-source flow come from
[wuxx/Colorlight-FPGA-Projects](https://github.com/wuxx/Colorlight-FPGA-Projects).

## Build & HDMI test

Open-source ECP5 flow — **yosys → nextpnr-ecp5 → ecppack** (plus `sbt` for SpinalHDL→SV),
device **`--25k --package CABGA381 --speed 6`**, programmed with **openFPGALoader
`-b colorlight-i5`**. `nextpnr-ecp5`, `yosys`, `ecppack`, `openFPGALoader` all present.

### 640×480 HDMI test — `build_hdmi_test.sh`
Self-contained (no Atari core): 25 MHz → PLL → fixed supervisor-style text → HDMI. Used to
eyeball jitter/shimmer on sharp text — **hardware-verified "solid as a rock", crisp**. Four
steps:
1. `sbt "hdl/runMain retro.boards.Atari800Ecp5HdmiTestSv"` → generates `Atari800Ecp5HdmiTestTop.sv`
2. `yosys synth_ecp5` over `pll_hdmi_ecp5.sv` + `ecp5_ddr_out.v` + the generated top → `build/hdmitest.json`
3. `nextpnr-ecp5 --25k --package CABGA381 --speed 6 --lpf hdmi_test.lpf`
4. `ecppack --compress` → `build/hdmitest.bit`

Program: `openFPGALoader -b colorlight-i5 build/hdmitest.bit` (volatile SRAM) or `-f` (flash).

**Clocks** (`pll_hdmi_ecp5.sv`, one `EHXPLLL`): 25 MHz in (P3) → **clkout0 = 125 MHz** (TMDS,
5× pixel) + **clkout1 = 25 MHz** (pixel). CLKI_DIV=1, CLKOP_DIV=5, CLKOS_DIV=25, feedback CLKOP.

**Pins** (`hdmi_test.lpf`, from wuxx `src/i5/hdmi_test_pattern` — known-good), **LVCMOS33
pseudo-differential**, DRIVE=4, P and N driven explicitly by `Ecp5DvidOut`/`ecp5_ddr_out.v`
(ODDRX1F):

| Lane | + (`gpdi_dp`) | − (`gpdi_dn`) |
|------|:---:|:---:|
| Blue / D0  | G19 | H20 |
| Green / D1 | E20 | F19 |
| Red / D2   | C20 | D19 |
| Clock      | J19 | K19 |

`clk_25mhz` = P3 (LVCMOS33, 25 MHz); `SYSCONFIG CONFIG_IOVOLTAGE=3.3`.

**Resource use (LFE5U-25F):** ~1382 LUT (**6 %**), 4 ODDRX1F, 1 EHXPLLL — the 125 MHz TMDS
serial clock is well under the ODDRX1F ~195–234 MHz ceiling, so a big timing margin. (720p/
1080p would need faster gearing — ECLKSYNCB/CLKDIVF or ODDRX2F.)

### Atari-core fit-check — `Makefile`
`make generate` (`Atari800Ecp5BramSv`) → `synth` → `pnr` → `bitstream` (top `fit_check_top`,
same `--25k --package CABGA381 --speed 6`). Confirms the **full Atari core fits the 25F**:
**4909 LUT4 (20 %), 34/56 DP16KD BRAM (60 %), 2763 FF (11 %), 1 EHXPLLL** — plenty of room to
fold in the HDMI path.

## JTAG — module header location + base-board placement
Same story as the [i9+](../i9plus-6v1/README.md#fpga-configuration-jtag): JTAG can't run over
the SODIMM, so it comes from the **module's own JTAG header** via a flying lead from the base
board (or a pogo — and here that's actually feasible, see below). The ECP5 header is a **1×4
horizontal row at the top-left** of the module (by U17), silk `J30 J32 J31 J27`:

**J30 = TDO, J32 = TDI, J31 = TMS, J27 = TCK.**

### Measured module-frame pad locations (i5 v6.0, top side, from bottom-left corner)
Horizontal row, **2.54 mm (0.1″)** pitch, at **y = 33.55 mm** (near the top of the module);
J30 at x = 9.52 mm:

| Pad | Sig | Module (x, y) mm |
|-----|-----|:---:|
| J30 | TDO | 9.52, 33.55  |
| J32 | TDI | 12.06, 33.55 |
| J31 | TMS | 14.60, 33.55 |
| J27 | TCK | 17.14, 33.55 |

*(J30 x/y calipered; J32–J27 stepped +2.54 mm in +X — confirm J27's x with calipers.)*

### Board coordinates
Same TE 1473006 seating point `(Cx, Cy)` and same **orientation B** (180°) as the i9+ — see
the [i9+ README](../i9plus-6v1/README.md) for the full derivation and the Cx/Cy definition.
Transform:
```
board X = Cx + 33.80 − mx        board Y = Cy − my
```
Worked example — Cx = −11.8, Cy = −0.7 (shared base-board placement):

| Pad | Sig | Board (x, y) mm |
|-----|-----|:---:|
| J30 | TDO | **12.48, −34.25** |
| J32 | TDI | **9.94, −34.25**  |
| J31 | TMS | **7.40, −34.25**  |
| J27 | TCK | **4.86, −34.25**  |

Horizontal row at **Y = −34.25**, stepping in X; the 180° flip **reverses left↔right** so J30
is rightmost (X = 12.48) and J27 leftmost (X = 4.86).

Notes:
- **Clears the connector body.** Unlike the Artix (JTAG 8–16 mm off the slot, in the connector
  zone), the ECP5 JTAG sits ~34 mm off the slot line — so a base-board pad/pogo here is
  feasible, not just a flying lead.
- Substitute the i5 base board's own `Cx, Cy` if its socket isn't at the shared (−11.8, −0.7).
- **JTAG headers differ per family**, so a fixed pogo nest can't serve both the ECP5 (this
  top-left row) and the Artix (right-edge column) — the flying lead is the portable path.
