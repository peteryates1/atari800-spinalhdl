# Atari 800 on QMTech XC7A100T (Artix-7) + RP2040 — Architecture & Bring-up Notes

*Status as of 2026-07-19: **pinning out** — no RTL port or Vivado project yet. This doc records
the board target and the HDMI/TMDS pin decision. Board: QMTech Artix-7 **XC7A100T core board**
(`xc7a100tfgg676`) + **RP2040-STAMP** supervisor + HDMI out.*

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

- FPGA: **XC7A100T**, `fgg676` package (confirm speed grade `-1`/`-2` from the core board silk).
- On-board **256 MB DDR3** (Micron `MT41K128M16JT-125`) — 100 Ω differential, controlled-impedance
  routing on the core board (the only signals the manual/schematic call out as impedance-matched).
- On-board **N25Q64 SPI flash** (8 MB) for the FPGA configuration image.
- Two **64-pin 2.54 mm female headers** for user IO, silk **U2** and **U4** (all header IOs are
  length-matched fanout per the manual — see `pin-mapping.md`).

## Supervisor (unchanged concept from the 10CL025 board)

The **RP2040-STAMP** is the supervisor: USB HID host (keyboard/joystick), FatFs on SD, the SIO disk
emulator, config-driven boot, the Alt-F12 menu, and the SPI link to the FPGA. The Artix port reuses
that firmware; only the FPGA fabric and the board pinout change. (RP2040-link / SD / RM2 / DDR3 /
clock pinout are **later phases** — this pass only fixes HDMI.)

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
