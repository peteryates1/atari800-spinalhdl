# Supervisor firmware (RP2040 / Pico)

The **supervisor** is the C firmware that runs on the RP2040 / RP2350 sitting next
to the FPGA. At power-on it configures the FPGA from the SD card, brings up the
USB-host keyboard, auto-boots the Atari from a JSON config, emulates SIO disk
drives (D1:–D4: from ATR images), and draws the **Alt-F12** on-screen menu. On a
Pico 2 W it also serves an on-demand **WiFi web UI** for managing the SD card.

Runtime behaviour and the SPI/SD protocols are described in the repo-root
[`STATUS.md`](../../STATUS.md); **this file is the build & flash guide.**

## Prerequisites

- **Raspberry Pi Pico SDK** (2.x). Set `PICO_SDK_PATH`, or let
  `pico_sdk_import.cmake` locate it. Pico 2 / Pico 2 W targets need an SDK with
  RP2350 support.
- **`arm-none-eabi-gcc`** toolchain, **CMake ≥ 3.13**, and a build tool (make/ninja).
- **Pico-PIO-USB submodule** (the USB-host keyboard stack):
  ```sh
  git submodule update --init firmware/supervisor/lib/Pico-PIO-USB
  ```
- **`pyserial`** for the host deploy tool (`tools/push_file.py`).

## Build

Two CMake options select the target:

| Option | Values | Selects |
|---|---|---|
| `-DBOARD=` | `qmtech` \| `colorlight` \| `wukong` | the RP2040↔FPGA pin map (SD, SPI link, JTAG config) |
| `-DPICO_BOARD=` | `pico` \| `pico_w` \| `pico2` \| `pico2_w` | the Pico variant; any `_w` value compiles in WiFi |

Board → build matrix:

| Target board | `-DBOARD` | `-DPICO_BOARD` | WiFi |
|---|---|---|---|
| `rp2040-qmtech-10cl025` (RP2040-STAMP) | `qmtech` | `pico` | — |
| `rp2040-colorlight` (i5 / i9+) | `colorlight` | `pico` / `pico_w` | opt |
| `wukong-1080` (Pico 2 W) | `wukong` | `pico2_w` | yes |

`qmtech` is the fallback pin map (the `#else` branch in `sd_spi.c` / `main.c` /
`jtag.c` / `blaster.c`); `colorlight` and `wukong` are explicit `#ifdef`s.

```sh
cd firmware/supervisor
cmake -B build -DBOARD=qmtech -DPICO_BOARD=pico
cmake --build build -j
# → build/supervisor.uf2  (+ .elf / .bin)
```

Use a distinct build dir per target (e.g. `build-wukong`) so the CMake caches
don't clash.

## Flash

**Preferred — SWD** (a Raspberry Pi Debug Probe, or a second Pico running
picoprobe):

```sh
# RP2040 boards (STAMP, colorlight): target/rp2040.cfg
openocd -f interface/cmsis-dap.cfg -f target/rp2040.cfg \
        -c "adapter speed 5000" \
        -c "program build/supervisor.elf verify reset exit"
# RP2350 boards (Pico 2 / Pico 2 W): use target/rp2350.cfg
```

> The RP2040-STAMP on the 10CL025 board has a **physically broken native-USB
> connector** — SWD is the only way to flash it, and SD updates go via a card
> reader. See [`../../boards/rp2040-qmtech-10cl025/README.md`](../../boards/rp2040-qmtech-10cl025/README.md).

**BOOTSEL / UF2** (boards with working native USB): hold BOOTSEL while plugging in,
then copy `build/supervisor.uf2` to the `RPI-RP2` / `RP2350` mass-storage drive.

## Deploy host tool

Where native USB works, the supervisor presents a **CDC console** and an **MSC
drive** (the SD card). `tools/push_file.py` streams a file to the running
supervisor over the CDC console — e.g. to refresh the SD `core.rbf`/`core.bit` or a
config without pulling the card. Boards wrap this in `make push-core` /
`make deploy-core` targets (see the per-board README).

## WiFi (Pico 2 W)

A `_w` `PICO_BOARD` compiles in `wifi.c` + `httpsrv.c` and links
`pico_cyw43_arch_lwip_poll` (lwIP config in `lwipopts.h`). Credentials live in
`/wifi.txt` on the SD card (line 1 = SSID, line 2 = password). Toggle from the
Alt-F12 menu (`[w]`) or the console (`N`); off by default.

## Layout

| File(s) | Role |
|---|---|
| `main.c` | boot, USB host/device, console command loop |
| `config.c` | SD JSON config hierarchy → boot plan |
| `fpga_config.c`, `jtag.c`, `blaster.c`, `blaster_jtag.pio` | FPGA config from SD + USB-Blaster emulation |
| `sio.c` | SIO disk-drive emulator (D1:–D4:) |
| `supervisor.c`, `fbtext.c` | Alt-F12 menu + on-screen text overlay |
| `sd_spi.c`, `sd_diskio.c`, `lib/fatfs/` | SD card (SPI) + FatFs |
| `wifi.c`, `httpsrv.c` | WiFi + HTTP SD manager (Pico 2 W) |
| `rm2.c` | RM2 (CYW43) wireless-module glue |
| `lib/Pico-PIO-USB/` | vendored USB-host PIO stack (submodule) |

A separate [`../rm2_test/`](../rm2_test/) project is a standalone CYW43 wireless
bring-up harness (`rm2_test` + `wifi_test`), independent of the supervisor.
