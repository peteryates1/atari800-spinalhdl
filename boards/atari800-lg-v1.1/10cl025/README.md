# 10CL025 setups for ATARI-800-LG-V1.1

QMTECH 10CL025 (Cyclone 10 LP) core board plugged into the V1.1 Atari
800 base board. See the
[QMTECH 10CL025 board doc](https://github.com/peteryates1/jop-spinalhdl/blob/main/docs/boards/qmtech-10cl025-board.md)
for FPGA spec, pinout, gotchas, and onboard SDRAM details.

Shared pin mappings for the V1.1 base board (CH376T, switches,
joysticks, VGA, audio, ADC, U9 connector to the Atari side) are in
[`v1_1_pins.tcl`](v1_1_pins.tcl). Every project sources it via
`SOURCE_TCL_SCRIPT_FILE`.

Common QSF preamble for a 10CL025 project:

```tcl
set_global_assignment -name DEVICE 10CL025YU256I7G
set_global_assignment -name FAMILY "Cyclone 10 LP"
set_global_assignment -name STRATIX_DEVICE_IO_STANDARD "3.3-V LVCMOS"
set_global_assignment -name RESERVE_ALL_UNUSED_PINS_WEAK_PULLUP \
    "AS INPUT TRI-STATED WITH WEAK PULL-UP"
set_global_assignment -name CYCLONEII_RESERVE_NCEO_AFTER_CONFIGURATION \
    "USE AS REGULAR IO"
```

## Projects

Per-subsystem bring-up projects (cloned from the EP4CGX150 tree and
adapted for Cyclone 10 LP) plus the full Atari 800 build:

| Project | Description |
|---------|-------------|
| `led_test/` | Core LED blink (power/clock/JTAG smoke test) |
| `uart_test/` | UART loopback to FTDI |
| `ch376_test/` | CH376T USB host (keyboard / SD) |
| `input_test/` | 4 console switches + 2 joysticks |
| `vga_test/` | 640x480@60Hz, 8 colour bars |
| `audio_test/` | 440 Hz / 880 Hz L/R |
| `adc_test/` | MCP3208 4-channel paddle ADC |
| `sram_test/` | IS63LV1024L external SRAM on base board |
| `sdram_test/` | Onboard W9825 SDRAM smoke test |
| `atari_starraiders/` | Full Atari 800 + Star Raiders cartridge, BRAM-only |

The full Atari 800 + Star Raiders BRAM-only build already consumes 58 of
the 66 M9K blocks (48K user RAM + 8K cart ROM + 10K OS). Adding JOP on
the 10CL025 alongside the Atari core requires moving the Atari core into
the onboard W9825 SDRAM to free up M9K — see the SDRAM-sharing plan in
`memory/project_10cl025_sdram_plan.md`.

Each project has its own `Makefile` with the usual targets:

| Target | Description |
|--------|-------------|
| `make` | `quartus_sh --flow compile <project>` |
| `make program` | Program the SOF via USB-Blaster |
| `make clean` | Remove `db/`, `incremental_db/`, `output_files/` |
