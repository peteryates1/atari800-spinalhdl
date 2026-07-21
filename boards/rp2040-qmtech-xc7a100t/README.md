# Atari 800 on QMTech XC7A100T (Artix-7) + RP2040-STAMP

**Status: design / pinning out — no RTL port or Vivado project yet.** This
directory records the board target and the HDMI/TMDS pin decision; the RTL
retarget, the Vivado project, and the DDR3/clock/RP2040-link pinout are later
phases.

A custom baseboard pairing the **QMTech Artix-7 XC7A100T core board**
(`xc7a100tfgg676`, on-board DDR3 + SPI flash) with an **RP2040-STAMP** supervisor
and HDMI out. The Artix's hard `OSERDESE2` serializers can drive **native
1080p60 TMDS** — the fix for the 720p supervisor-text shimmer on the Cyclone 10 LP
board — and give a proper-PCB home for reliable RM2 wireless and an optional FPGA
crypto coprocessor.

> The **Wukong** board (`../wukong-1080/`) is the *proven* Artix 1080p60
> path (same `xc7a100tfgg676`, hardware-verified). This board is the custom-PCB
> variant of that idea; reuse its RTL/video pipeline when the port begins.

## What's here

| File | Purpose |
|---|---|
| [`ARCHITECTURE.md`](ARCHITECTURE.md) | board target, rationale, core-board summary, bring-up notes |
| [`pin-mapping.md`](pin-mapping.md) | the full HDMI/TMDS pin decision + `VCCO_34_35` gotcha + length-match findings |
| [`hdmi.xdc`](hdmi.xdc) | Vivado constraints **seed** for the four TMDS pairs (fold into the project XDC later) |
| `*.enet` | KiCad netlist export |

## HDMI pin decision (summary)

Connector **U4 = banks 34/35**. `VCCO_34_35` (U4 pins 3 & 4) is **user-supplied** —
feed it **3.3 V** or the bank is dead; that 3.3 V is also what enables `TMDS_33`.
Four pairs, all Bank 35, `OBUFDS` + `TMDS_33` serialized by `OSERDESE2`:

| HDMI signal | FPGA P | FPGA N |
|---|---|---|
| TMDS Clock  | B5 | A5 |
| TMDS Data 0 | B4 | A4 |
| TMDS Data 1 | A3 | A2 |
| TMDS Data 2 | D4 | C4 |

Data-channel order is free in HDL (reorder for layout); only the clock pair is
fixed, and keep P→HDMI+, N→HDMI−. The **baseboard** must route header→jack as 100 Ω
differential, tight intra-pair. Full rationale and sources in
[`pin-mapping.md`](pin-mapping.md).

## Next phases

1. Vivado project + top-level (retarget the Atari core + video pipeline to Artix,
   `TMDS_33` OSERDES — reuse the Wukong's `rgb2dvi`/1080p pipeline).
2. Complete pinout: DDR3, clocks, and the RP2040-STAMP SPI/JTAG link.
3. RM2 wireless on-PCB; optional FPGA crypto coprocessor.

The supervisor firmware is shared — it will build with a board-specific
`-DBOARD=` pin map once the link pinout is fixed (see
[`../../firmware/supervisor/README.md`](../../firmware/supervisor/README.md)).
