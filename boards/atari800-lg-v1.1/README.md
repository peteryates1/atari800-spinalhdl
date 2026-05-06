# ATARI-800-LG-V1.1 base board

Layout for the second-revision base board that mates the QMTECH core
boards' two 32x2 headers into a single 128-pin connector U9. Verified
against `Netlist_ATARI-800-QMTechCB-LG-V1_1_2026-04-28.enet`.

See [`pin-mapping.md`](pin-mapping.md) for the U9 → FPGA pin
cross-reference for all three supported core boards.

## Layout

```
boards/atari800-lg-v1.1/
├── pin-mapping.md       — U9 connector → FPGA pin cross-reference
├── Netlist_*.enet       — KiCad netlist export (source of truth for U9)
├── hw/                  — gerbers, BOM, 3D model, assembly drawings
├── ep4cgx150/           — QMTECH EP4CGX150 (Cyclone IV GX) setups
│   └── v1_1_pins.tcl    — pin assignment script (source from QSF)
├── 10cl025/             — QMTECH 10CL025 (Cyclone 10 LP) setups
│   └── v1_1_pins.tcl    — includes onboard W9825 SDRAM pinout
└── xc7a100t/            — QMTECH XC7A100T (Artix-7) setups
    └── v1_1_pins.xdc
```

The intent is to add per-setup subdirectories below each FPGA board (e.g.
`ep4cgx150/main/`, `ep4cgx150/vga_test/`) once the corresponding top-level
SystemVerilog wrappers exist; for now only the shared pin assignment file
is committed.

Common entities (clock PLLs per family, etc.) are at `boards/common/`.

## V1.1 fixes versus V1

- SD card DI/DO swap fixed (CH376T_SD pins routed correctly at U9.46–49).
- 10CL025 input-only clock pins (A8/B8/A9/B9) avoided — corresponding U9
  pins (103–106) left unconnected.
- 8 base-board user LEDs (vs 2 core-board LEDs on V1) at U9.53–60.
  Note: LED6/LED7 unavailable on XC7A100T (land on NC pins of that core
  board). Per-FPGA pin files name these `led_base[]` and reserve
  `led_core[]` for the core board's onboard user LEDs.
- CH376T 12 MHz clock at U9.50 is an FPGA **output** — the FPGA's PLL
  drives the CH376T's clock input in place of its crystal.
