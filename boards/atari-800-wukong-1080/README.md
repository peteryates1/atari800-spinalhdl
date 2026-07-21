# Atari 800 on the QMTech Wukong (XC7A100T) — native 1080p60 appliance

An Atari 800 running at **native 1920×1080p60** over HDMI on the off-the-shelf
**QMTech Wukong** board (Xilinx Artix-7 `xc7a100tfgg676`), with a **Raspberry Pi
Pico 2 W** as the supervisor. Everything the machine needs — the FPGA bitstream,
the 800 OS, the cartridge, and disk images — lives on the **SD card**; nothing
proprietary is baked into the bitstream or the firmware.

**Cold power-on → the Pico JTAG-configures the Artix from the SD `.bit` → loads the
OS + cart + disks from SD into the FPGA → Atari boots.** No host, no cable.

All phases below are **committed and hardware-verified**.

## Status

| Phase | What | State |
|---|---|---|
| 0  | SpinalHDL → Vivado → rgb2dvi → 1080p60 colour bars | ✅ |
| 1  | Atari 800 at 1080p60 (standalone, baked ROM) | ✅ |
| 2a | USB keyboard + on-screen 1080p supervisor menu (Alt-F12) via the Pico | ✅ |
| 2c | SD config-boot (OS/cart from SD) + SIO disk emulation | ✅ |
| 2b | Autonomous FPGA config from the SD `.bit` over JTAG at power-on | ✅ |

Confirmed on hardware: Star Raiders & Pole Position (carts), DOS 2.5 booting off an
emulated D1: disk (SioBridge), no-cart → Memo Pad, USB keyboard incl.
**F5/F6/F7 = Start/Select/Option, F8 = SYSTEM RESET**, the Alt-F12 menu on-screen,
and a fully self-booting cold power cycle from the card alone.

## Hardware

- **FPGA:** QMTech Wukong V3, XC7A100T-fgg676-2. On-board HDMI (bank 35, TMDS_33),
  **W9825G6KH SDR SDRAM** (used as the video framebuffer), 50 MHz osc (M21),
  reset (H7). (The board also has MT41K128M16 DDR3, unused here.)
- **Supervisor:** Raspberry Pi Pico 2 W (RP2350), single-core. Wiring:
  - **JTAG → FPGA config header:** `GP0=TCK, GP1=TDO, GP2=TMS, GP3=TDI` (+GND)
  - **USB-host keyboard (PIO-USB):** `GP4=D-, GP5=D+`
  - **SPI link → FPGA J11 (bank 35):** SPI1 `GP12=MISO→H4, GP13=CSn→F4, GP14=SCK→A4, GP15=MOSI→A5`
  - **SD card:** SPI0 `GP16=RX, GP17=CS, GP18=SCK, GP19=TX`
  - **Native USB** presents a composite device: an FTDI USB-Blaster (09fb:6001, for
    `openFPGALoader --cable usb-blaster`), a **CDC console**, and an **MSC drive**
    (the SD card, for browsing/editing small files).
  - Flashed over SWD with a Raspberry Pi Debug Probe (rp2350.cfg).

FPGA pinout is in `vivado/constraints/wukong_atari.xdc`; board-level hardware notes
and reference-design pinouts are in `../qm_xc7a100t_wukong/`.

## Architecture

**Memory split** (the ANTIC-jitter lesson from the 10CL025 board): the Atari lives
**entirely in BRAM** — 48 KB RAM + the SD-loaded OS + cart (`internal_rom=5`, blank
loadable) — so ANTIC display DMA is never starved on contended SDRAM. The **W9825
SDRAM holds only the video framebuffer**.

**Video pipeline** (reused unchanged from the 10CL025 "LG" board, re-parameterized
to 1080p): `Atari800CoreSimpleSdram` → `VideoFbWrite` (captures the 384×288 GTIA
frame) → `SdramArbiter3` → `SdramStatemachine` (W9825) → `VideoFbRead2` (DDA scaler
to 1920×1080) → `GtiaPalette` → **Digilent rgb2dvi** (TMDS/OSERDESE2) → HDMI.
Board specifics: `vid_pData` byte order is **{R, B, G}** and the sync is **inverted**
into rgb2dvi. Clocking is two `MMCME2_BASE` blocks: 50→148.4375 MHz pixel, and
50→sys 56.25 + sdram 112.5 (with a −90° phase chip clock).

**Supervisor SPI link** (`RpAtariKeyboard` on the FPGA, SPI1 from the Pico): carries
the USB keyboard, console keys, control bits (reset/halt/supDisplay/turbo), the BRAM
**loader** (OS/cart/RAM), the **SIO bridge** (disk emulation), and the on-screen
text-overlay character grid.

**On-screen menu:** `TextOverlay1080` renders the supervisor's 40×15 char grid
natively at 1080p (no SDRAM, no upscaler — crisp), muxed over the Atari scaler while
`supDisplay` is set (Alt-F12).

**Config-boot (2c):** the supervisor reads `/config.json` on SD, streams the OS
blocks + cart into BRAM via the core LOAD port, mounts ATR disk images for the SIO
emulator, and pulses a stretched supervisor reset to start the 6502 in the loaded OS.

**Autonomous FPGA config (2b):** at power-on the supervisor **streams
`/atari/800/core.bit` from SD and shifts it into the Artix over JTAG**
(JPROGRAM/CFG_IN/JSTART, each config byte bit-reversed — Xilinx is MSB-first, the
blaster shift is LSB-first, matching `openFPGALoader`'s `reverse=true` for MEM_MODE).
~10 s at 6 MHz; the screen is blank until the FPGA is configured.

## Build & deploy

Requires Vivado 2025.2 (`/opt/xilinx/2025.2`), the SpinalHDL toolchain (`sbt`), and
`pyserial` for the deploy tool.

```bash
make atari         # build .bit -> push to SD (/atari/800/core.bit) -> supervisor
                   #   configures the Artix from it and boots the Atari. All over USB.
make deploy-core   # build-atari + push-core (update the SD core, don't activate)
make push-core     # push an already-built .bit to the SD core slot
make build-atari   # up to the bitstream only
make program-atari # direct SRAM load over a JTAG cable (CABLE=dirtyJtag; volatile)
```

`make atari` is the normal loop — no JTAG cable needed. A cold power-cycle then
reproduces the same result autonomously from the card. Direct `program-atari` (via
dirtyJtag or `--cable usb-blaster` through the live supervisor) stays available for
quick volatile testing.

**Deploying files without pulling the card:** the MSC drive works for browsing, but
host FAT write-back caching makes drag-and-drop + power-off unreliable (an
un-ejected copy can land 0 bytes). The reliable path is `make push-core` /
`tools/push_file.py`, which streams over the CDC to the firmware's `U` command and
writes straight to SD (self-throttled by USB flow control, ~160 KB/s, with progress).

### Supervisor console (the `09fb:6001` CDC, e.g. `/dev/ttyACM1`)

Access with a terminal that asserts DTR (raw `cat`/`head` hang on flow control).

| Cmd | Action |
|---|---|
| `F` | Configure the Artix from the SD `/atari/800/core.bit` on demand |
| `B` | Config-boot the Atari (load OS/cart/disks from SD) |
| `U` | Receive a file over USB and write it to SD (used by `push_file.py`) |
| `N` | Toggle WiFi (join / disconnect) and show the IP |
| `T` | SD write self-test (proves the write path) |
| `i` / `d` | SD init/info / mount + list |
| `J` | Read the FPGA JTAG IDCODE |
| `~` | Open the supervisor menu over the console |

## Network SD manager (WiFi + web UI)

The Pico 2 W's **on-board CYW43439** hosts an optional web UI for managing the SD over
WiFi — no card-pulling, no MSC/eject. **Off by default**; bring it up from the menu
`[w]` or console `N` (shows "connecting…", then the IP). It uses the standard
`pico_cyw43_arch_lwip_poll` and is compile-gated to `_w` boards (`HAVE_WIFI`).

- **Credentials:** `/wifi.txt` on the SD — line 1 SSID, line 2 password (empty = open).
- **Web UI** at `http://<ip>/` (raw-lwIP-TCP server, `httpsrv.c`): browse the card with a
  clickable breadcrumb path + per-folder filter, **drag-drop upload** carts/disks
  (streamed straight to FatFs), **create folders**, and delete files.
- **API:** `GET /api/list?dir=`, `POST /api/upload?path=` (raw body), `POST
  /api/delete?path=`, `POST /api/mkdir?path=`.
- The **type-to-filter cart/disk picker** (Alt-F12 menu, A-Z sorted) is separate and
  works on all boards — see the top-level `STATUS.md`.

## Key files

- `vivado/src/wukong_atari_mmcm.v`, `wukong_hdmi_mmcm.v` — MMCM clocking
- `vivado/src/rgb2dvi_wrapper.vhd` + `rgb2dvi/` — Digilent TMDS encoder (VHDL-2008)
- `vivado/constraints/wukong_atari.xdc` — pinout (clk, HDMI, SPI link, W9825 SDRAM)
- `vivado/tcl/create_atari.tcl`, `build_atari.tcl` — Vivado project + build
- RTL: `atari/src/main/scala/atari800/Atari800WukongTop.scala` (top),
  `TextOverlay1080.scala`, `RpAtariKeyboard.scala`, and the shared LG video pipeline
- Firmware: `firmware/supervisor/` (`-DBOARD=wukong`), notably `fpga_config.c`
  (`fpga_config_from_sd`), `main.c` (config-boot, `U`/`F`/`T`/`N`), `sd_spi.c`,
  `wifi.c` + `httpsrv.c` + `lwipopts.h` (WiFi + web UI, `_w` boards only),
  `supervisor.c` (menu + type-to-filter picker)
- Deploy tool: `firmware/supervisor/tools/push_file.py`

## Known notes

- Timing "not met" is a **benign** `VideoFbRead2` clkPixel→clkSys `BufferCC` CDC
  false-path only (to be cleared with a `set_false_path`); the design runs stable.
- The FPGA re-configures from SD on every boot (~10 s); a future optimization could
  skip it when the FPGA is already up (warm Pico-reset).
- See `memory/project_wukong_board` for the full running history and gotchas.
