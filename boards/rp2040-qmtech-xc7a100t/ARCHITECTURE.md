# Atari 800 on QMTech XC7A100T (Artix-7) + RP2040 — Architecture & Bring-up Notes

*Status as of 2026-07-22: **baseboard netlist complete** (schematic + placement done; RTL port and
Vivado project are the next phase). Board: QMTech Artix-7 **XC7A100T core board**
(`xc7a100tfgg676-2`) + **RP2040-STAMP** supervisor + HDMI out. This is the productised equivalent of
the [Wukong bench board](../wukong-1080/) — see "Wukong equivalence" below.*

## Why this board

The Cyclone 10 LP 10CL025 board tops out at **720p60** over its soft TMDS serializer, which is why
supervisor text shimmers on 1080p monitors (non-integer upscale). The Artix-7 has hard `OSERDESE2`
serializers and can drive **native 1080p60 TMDS** — the fix for crisp on-screen text. It is also
the natural home for:
- **Reliable RM2 wireless** (CYW43439 WiFi/BT) on a *proper PCB* rather than jumper wires (the
  jumper-wire signal-integrity/power problem is what blocks WiFi/BT firmware download on the LG
  bench today). See `memory/project_rm2_wireless.md`.
- an **optional FPGA crypto coprocessor** (SHA-256/AES) — abundant Artix fabric makes it free on
  resources, though it only pays off if the SD/radio data is FPGA-resident or the MCU↔FPGA link is
  fast (data-locality caveat — discussed, not committed).

## Core board summary (from the QMTech hardware manual)

- FPGA: **XC7A100T**, `fgg676` package, **speed grade -2** (confirmed: the core board's own MIG
  reference project targets `xc7a100tfgg676-2` — same grade as the Wukong).
- **50 MHz** on-board oscillator on ball **U22** (confirmed from the core board LED + MIG reference
  constraints, `CLKIN1_PERIOD 20.000`; U22 already drives an MMCM there, so it is MMCM-reachable).
- On-board **256 MB DDR3** (Micron `MT41K128M16JT-125`) — 100 Ω differential, controlled-impedance
  routing on the core board (the only signals the manual/schematic call out as impedance-matched).
- On-board **N25Q64 SPI flash** (8 MB) for the FPGA configuration image.
- Two **64-pin 2.54 mm female headers** for user IO, silk **U2** and **U4** (all header IOs are
  length-matched fanout per the manual — see `pin-mapping.md`).

## Wukong equivalence (verified 2026-07-22)

This board is intended to be the **product** counterpart of the `../wukong-1080/` **bench** board:
the same Artix silicon, wrapped with real Atari I/O. The three factors that decide whether the
Wukong-proven 1080p design ports without a hardware rethink all match:

| Factor | Wukong (proven) | This board's core module | Match |
|---|---|---|---|
| FPGA part **+ speed grade** | `xc7a100tfgg676-2` | `xc7a100tfgg676-2` | ✅ identical |
| Reference clock | 50 MHz @ `M21` | 50 MHz @ **`U22`** | ✅ same freq, diff ball |
| Latency-critical RAM | `W9825G6KH` SDR (`SdramStatemachine`) | **`W9825G6KH` added on the baseboard** | ✅ identical part |
| Framebuffer / bulk RAM | `MT41K128M16` DDR3 (MIG) | `MT41K128M16` DDR3 **on-module** (MIG) | ✅ same part |
| HDMI drive | TMDS_33 / `OSERDESE2` | TMDS_33, Bank 35 | ✅ same |

The **-2** grade is the decisive one: 1080p60 TMDS (371.25 MHz SERDES) that closes on -2 is *not*
guaranteed on -1 — and the core board is -2. The board also carries the **same memory complement**
(module DDR3 + baseboard-added W9825 SDR), so the Wukong's "SDR = main RAM, DDR3 = framebuffer/
scaler" split ports unchanged. On top of the silicon it adds what the bare Wukong lacks: the
RP2040-STAMP supervisor, 2× DB9 joysticks, the four console keys, and PWM audio — a strict superset
for the Atari core. So the Wukong stays the proving bench; this becomes the product, with the
RTL/XDC retarget (below) the only remaining work.

**Open items still to prove (not board defects):**
1. **HDMI 100 nF AC-coupling caps** (C1–C8, series on all four TMDS pairs) — the single electrical
   delta from the Wukong path. Defensible for TMDS_33 out of a 3.3 V bank, but confirm against the
   Wukong's HDMI coupling before fab; each 0402 is an impedance discontinuity on the 100 Ω pair.
2. **DDR3 MIG + reference clock not yet in the XDC** — mechanical (pinout fixed by the module), but
   the MIG instance + framebuffer retarget is the "later phase" work.
3. **Ref-clock ball is U22, not M21** — one `PACKAGE_PIN` line; the MMCM recipe (50 → 148.4375 MHz)
   ports verbatim.

## Baseboard netlist — status (v2, `Netlist_…_1_2026-07-22.enet`)

Full base board: core-board header U2, RP2040-STAMP (U3), added **W9825G6KH SDR SDRAM (U7)** centred
between the two headers with local decoupling, HDMI jack (J1) + AZ1045 ESD (D1/D3), microSD (J3),
2× USB-A host (J4) + native micro-USB (J6), RM2 wireless (U4) on its own buck (U5), 2× DB9 joystick,
four console keys, PWM audio. Reviewed clean; **the v1 export's missing FPGA↔RP2040 link is fixed**
in v2: `SPI0_CLK/COPI/CIPO` = FPGA `K26/K25/J21` ↔ RP2040 `GPIO18/19/20`, plus 6 spare GPIO taps
(`GPIO17/21/22/23/24/25`). Link pins sit on the hardwired-3V3 header banks, so no VCCO dependency;
only HDMI (bank 35) needs the `VCCO_34_35` = 3.3 V feed, which is supplied.

## Supervisor (unchanged concept from the 10CL025 board)

The **RP2040-STAMP** is the supervisor: USB HID host (keyboard/joystick), FatFs on SD, the SIO disk
emulator, config-driven boot, the Alt-F12 menu, and the SPI link to the FPGA. The Artix port reuses
that firmware; only the FPGA fabric and the board pinout change. (The RP2040-link / SD / RM2 wiring
is now settled in the v2 netlist; the **DDR3 MIG + clock XDC and the RTL retarget remain** — see the
roadmap.)

## HDMI / TMDS output — connector U4 (banks 34/35)

HDMI sits by header **U4**, which exposes Artix **banks 34 and 35** (both HR, 3.3 V-capable). The
four TMDS pairs are placed in **Bank 35**, clustered at the U4 pin-1 end for the shortest runs,
with the clock nearest pin 1. Full table, rationale, and the `VCCO_34_35` = 3.3 V requirement are
in **[`pin-mapping.md`](pin-mapping.md)**; the Vivado seed constraints are in
**[`hdmi.xdc`](hdmi.xdc)**.

Drive with `IOSTANDARD TMDS_33` on `OBUFDS`, bit-serialized by `OSERDESE2` (standard QMTech-Artix
recipe; no external resistor network). This reaches native 1080p60 and does 720p trivially.

## Roadmap (subsequent phases)

1. Vivado project + full XDC (clock, DDR3 via MIG, RP2040 SPI link, SD, RM2, switches/LEDs).
2. Retarget the SpinalHDL Atari core + video pipeline to Artix (TMDS via `OSERDESE2`/`OBUFDS`;
   framebuffer in DDR3 via MIG instead of the W9825 SDRAM controller).
3. Native 1080p60 supervisor overlay (the payoff: crisp text).
4. RM2 wireless on the PCB; optional FPGA crypto coprocessor.
