# Colorlight i5 (LFE5U-25F, ECP5) — board notes

The i5 is the ECP5 member of the shared-base-board plan (same DDR2-SODIMM-200 socket as the
i9 and the Artix i9+). On-module: LFE5U-25F, EtronTech EM638325BK-6H 8 MB SDRAM, SPI flash.

## HDMI bring-up test (this folder)
Self-contained 640×480 HDMI test (no Atari core) — hardware-verified "solid as a rock":
`build_hdmi_test.sh` → yosys/nextpnr-ecp5/ecppack; sources `ecp5_ddr_out.v`,
`pll_hdmi_ecp5.sv`, `hdmi_test.lpf`; generated from `Atari800Ecp5HdmiTestTop`. Programmed via
`openFPGALoader -b colorlight-i5`. See the ECP5 HDMI notes for the full pipeline and the
720p/1080p ladder.

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
