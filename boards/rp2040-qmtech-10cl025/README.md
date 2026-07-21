# Atari 800 on QMTech Cyclone 10 LP (10CL025) + RP2040-STAMP — 720p

**The original board.** An Atari 800 core on an off-the-shelf **QMTech Cyclone 10
LP `10CL025` core board**, with an **RP2040-STAMP** as the supervisor, over **720p
HDMI**. Everything the machine needs — the FPGA `.rbf`, the 800 OS, the cartridge,
and disk images — lives on the **SD card**; nothing proprietary is in the bitstream.

**Cold power-on → the RP2040 configures the FPGA from the SD `.rbf` → loads the
OS + cart + disks from SD into BRAM → the Atari boots.** No host, no cable.

Committed and hardware-verified: Star Raiders / Defender / Pole Position (carts),
DOS 2.5 and Jumpman off an emulated SIO disk in D1:, the Alt-F12 on-screen menu,
and a self-booting cold power cycle from the card alone.

## Hardware

- **FPGA:** QMTech Cyclone 10 LP `10CL025YU256`. On-board SDRAM (used **only** as
  the 720p video framebuffer), EPCS/EPCQ config flash (safe baseline bitstream).
- **Supervisor:** RP2040-STAMP. It configures the FPGA over JTAG (GPIO0–3), hosts
  the USB keyboard (PIO-USB), links to the FPGA over SPI (keyboard / control /
  loader / SIO / text overlay), and drives the SD card.
- **Video:** 1280×720p60 HDMI via a soft TMDS serializer (`dvi_encoder` +
  371.25 MHz TMDS clock).

Pinout and connector layout: [`pin-mapping.md`](pin-mapping.md). Board-level
architecture and rationale: [`ARCHITECTURE.md`](ARCHITECTURE.md).

### Known quirks

- **RP2040-STAMP native USB connector is physically broken** on this board — there
  is **no CDC console / MSC drive / USB-Blaster** here. Flash the firmware over
  **SWD** and update the SD with a **card reader**.
- **SWD header (U6) has SWDIO/SWCLK swapped** vs the RP spec — use a crossover when
  wiring the debug probe. (See `memory/project_rp2040_qmtech_swd_swap_bug.md`.)

## Architecture (why BRAM, not SDRAM)

The Atari runs **entirely from BRAM** — 48 KB RAM + the SD-loaded OS + cart
(`internal_ram=0`, `internal_rom=5` blank-loadable, `cartridge_rom=""`). ANTIC's
real-time display DMA can't tolerate SDRAM contention, so the **SDRAM carries only
the 720p framebuffer** (triple-buffered): `VideoFbWrite` → `SdramArbiter3` →
`SdramStatemachine` → `VideoFbRead2` (DDA scaler) → `GtiaPalette` → HDMI. The 6502
runs at cycle-accurate 1.79 MHz with a runtime turbo toggle. See
`../../STATUS.md` and `memory/project_antic_ram_in_bram.md`.

## Build

### 1. FPGA bitstream (Quartus)

The Quartus project is [`atari_starraiders/`](atari_starraiders/). It instantiates
the SpinalHDL top `Atari800Rp2040HdmiLgTop` (generated to `../../generated/`).

```sh
cd atari_starraiders
make generate          # only if the Scala changed: regenerate the core SV
make build             # quartus_sh --flow compile → output_files/atari_starraiders.sof
```

Turn the `.sof` into an SD-loadable `.rbf` and copy it to the card:

```sh
/opt/altera/25.1/quartus/bin/quartus_cpf -c -o bitstream_compression=off \
    output_files/atari_starraiders.sof output_files/atari_starraiders.rbf
# copy atari_starraiders.rbf → SD  /atari/800/core.rbf
```

Drop a new `core.rbf` on the SD and power-cycle — the RP2040 stages it into its own
flash (only when size/mtime change) and JTAG-configures the FPGA from it. A
bad/absent `.rbf` is safe: the EPCS baseline stays.

### 2. Supervisor firmware

Build with `-DBOARD=qmtech -DPICO_BOARD=pico` and flash over SWD (`target/rp2040.cfg`).
See [`../../firmware/supervisor/README.md`](../../firmware/supervisor/README.md).

## SD card layout

```
/config.json                     {"default": "/atari/800"}
/atari/800/core.rbf              FPGA bitstream (loaded at boot)
/atari/800/config.json           memory-map (OS/RAM), cartridge{}, disks{}
/atari/800/carts/…               cart ROMs + per-cart config.json {file,type:"8K"|"16K"}
/atari/800/disks/…               ATR disk images + per-disk config.json
```

Config schema details are in [`../../firmware/supervisor/config.c`](../../firmware/supervisor/config.c).

## Directory contents

- `atari_starraiders/` — **the board project** (Atari core → 720p HDMI).
- `hdmi_ref/`, `hdmi_ref_480/`, `hdmi_test/`, `led_test/`, `sdram_test*/` — Quartus
  bring-up fixtures (HDMI colour bars, LED blink, SDRAM BIST) used during board
  bring-up; not part of the shipping build.
- `ARCHITECTURE.md`, `pin-mapping.md`, `pins.tcl` — board docs + pin constraints.
- `*.enet`, `*.jpg` — netlist export and board photos.
