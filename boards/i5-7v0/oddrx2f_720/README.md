# ECP5 720p ODDRX2F serializer — feasibility PROVEN

720p (74.25 MHz pixel, 371.25 MHz DDR serial) exceeds the current `Ecp5DvidOut` ODDRX1F
serializer (caps ~234 MHz). This proves the **ODDRX2F + ECLK** path reaches it on the
LFE5U-25F-6, on the **i5-ext board HDMI pins**, so it's HW-testable on the existing setup.

## Result (nextpnr-ecp5 --25k --package CABGA381 --speed 6)
`oddrx2f_clocking_test.v` — PLL(25→370) → **ECLKSYNCB** → ECLK(370) → **CLKDIVF /2** →
SCLK(185); 8× **ODDRX2F** driving all 4 i5-ext HDMI lanes (G19/H20 E20/F19 C20/D19 J19/K19):
- **All 8 HDMI pins promoted to bank 2 ECLK0** — one shared edge clock drives all lanes ✅
- **Placed 21 cells, 0 errors** — the i5-ext HDMI pins are all ECLK-capable ✅
- **SCLK (185 MHz) fabric domain: 387 MHz max** (2× margin); ECLK(370) on the dedicated
  edge-clock path (no fabric timing path to fail) ✅

## Clocking pattern (the reusable part)
```
PLL clkout0 = 5×pixel (371.25)  →  ECLKSYNCB.ECLKI  →  ECLK
ECLK  →  CLKDIVF #(.DIV("2.0"))  →  SCLK (= ECLK/2, fabric + ODDRX2F.SCLK)
ODDRX2F(.ECLK, .SCLK, .D0..D3)   →  HDMI pin (LVCMOS33D pseudo-diff)
```

## Still to build (integration, not feasibility)
- Replace `ecp5_ddr_out.v` (ODDRX1F) with this ODDRX2F path.
- Restructure the 10:1 gearbox to feed ODDRX2F's **4 bits/SCLK** (10 bits/pixel over 2.5
  SCLK cycles → handle on a 2-pixel/20-bit boundary, or 5 SCLK per 2 pixels).
- Fold into the real 720p top (Atari video → line-scaler → TmdsEncoder → ODDRX2F).
