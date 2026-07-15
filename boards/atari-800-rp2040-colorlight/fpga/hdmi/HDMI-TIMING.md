# HDMI output timing — verified limits (before board spin)

Vivado/nextpnr timing verification of the HDMI serializer on the actual board pins, so the
resolution target is grounded before committing copper.

## i9+ (Artix XC7A50T-**2**) — standard 1080p60 is **NOT** achievable
Verified by placing+routing a real 1-lane OSERDESE2 10:1 DDR serializer (`oserdes10.v`) on the
board's HDMI pin (Y3/AA3), MMCM 25→148.75/743.75 MHz, `xc7a50tfgg484-2`:

| Result | |
|---|---|
| Setup / Hold | **0 failing** (WNS +5.3 ns) ✅ |
| OSERDES + TMDS_33 I/O data rate (1.485 Gb/s) | **OK — no HR-bank-rate/DRC violation** ✅ |
| Place & route (MMCM floorplanned into the HDMI clock region) | clean ✅ |
| **OSERDESE2/CLK min period** | **1.471 ns → max 679.8 MHz** ❌ |
| 1080p60 serial clock needed | 742.5 MHz → **misses by ~9 % (−0.126 ns pulse width)** |

**The binding limit is the OSERDESE2 serializer clock max (~680 MHz on the −2 grade)** — a hard
primitive spec you can't optimise past. Everything else (data path, I/O rate, setup/hold,
clocking placement) passed.

**What the i9+ −2 can do** — max serial ≈ 680 MHz → **max pixel ≈ 136 MHz**:
- **720p60 (74.25 MHz)** — huge margin ✅
- 1600×900@60 (108 MHz), 1280×1024@60 ✅
- 1080p at reduced blanking / lower refresh (~133 MHz RB) — right at the edge, case-by-case
- **standard 1080p60 (148.5 MHz) — no.** A **−3** part would make it; the Colorlight module is −2.

Clocking notes for the real design (all confirmed working):
- All 4 HDMI board pins are in **clock region X1Y0**, which has its own **MMCME2_ADV_X1Y0** →
  `set_property LOC MMCME2_ADV_X1Y0 [get_cells <mmcm>]` so the MMCM→BUFIO uses the dedicated path.
- Serial clock (5× pixel) → **BUFIO** (not BUFG — BUFG maxes ~464/575 MHz); CLKDIV → **BUFR**.
- The 25 MHz input (K4) is in a different region → demote its route:
  `set_property CLOCK_DEDICATED_ROUTE FALSE [get_nets -of [get_pins <mmcm>/CLKIN1]]`.

## Consequence
With 1080p out on the −2, the i9+'s only edge over the i5 (its reason to exist) is gone — so
**both chips target 720p**, and the cheaper/smaller **i5 (ECP5) becomes the primary**. `oserdes10.v`
still serves the i9+ at 720p if that module is used.

## i5 (ECP5 LFE5U-25F-6) — 720p
720p = 74.25 MHz pixel, 371.25 MHz DDR serial. Beyond the proven 640×480 (ODDRX1F @125 MHz);
needs ODDRX2F/ECLK gearing. Verification: see the ECP5 720p test (`../../i5-7v0/`). [in progress]
