# Project Status — 2026-07-10

Handoff snapshot. Board: **atari-800-rp2040-qmtech-10cl025** (QMTech Cyclone 10 LP
10CL025 + RP2040-STAMP + HDMI). Everything below is committed and hardware-verified
unless noted.

## TL;DR — current state

The Atari 800 runs **entirely from BRAM** (RAM + OS + cartridge); **SDRAM carries
only the 720p framebuffer**. The RP2040-STAMP is the **supervisor**: it brings up
USB + SD, then **auto-boots the Atari from a JSON config on the SD card**, emulates
**SIO disk drives** (D1:–D4:) from ATR images, and hosts an **Alt-F12 supervisor
menu** to pause/live-edit/reboot. **Nothing proprietary is in the `.sof`** — OS,
cart, and disks all stream from SD.

Confirmed on hardware: **Star Raiders** boots from cartridge over 720p HDMI, and
**Jumpman** boots and runs off an emulated SIO disk in D1:. The old display bugs
(sprite smear, whole-picture jitter) are gone by construction.

Resource use: logic ~49%, BRAM **65/66 M9K**, timing clean.

## Architecture

**Memory split**
- **BRAM (inside the FPGA):** Atari 48 KB RAM (0000–BFFF), 800 OS ROM
  (D800–FFFF), and the cartridge (shares top-of-RAM A000–BFFF, read-only while
  the cart is active).
- **SDRAM (external):** the 720p framebuffer only (triple-buffered), written by
  the video capture, read by the scaler. No CPU/ANTIC/loader traffic.

**Why:** ANTIC's real-time display DMA can't tolerate SDRAM contention/latency.
Putting the Atari fully in BRAM removes the shared resource. See
`memory/project_antic_ram_in_bram.md`.

**Load path (supervisor → BRAM):** supervisor streams OS/cart over the SPI link
into the RAM/ROM BRAM write ports while the Atari is halted. Loader `ldDest`
(`'B'`=0x42 SPI cmd): 0=SDRAM (severed/no-op), 1=BRAM-OS, 2=BRAM-RAM.

## Boot process — config-driven, auto at power-on

1. Power-on: FPGA configures (blank BRAM); SDRAM inits; Atari held halted until
   `sdramReady`. RP2040 brings up USB (CDC console + PIO-USB keyboard), SPI, SD.
2. **Auto-boot** (`do_boot()` after `tud_connect()`): mount SD → read the config
   hierarchy → halt 6502 → stream OS + cart into BRAM → mount disks → reset.
   Console `'B'` re-runs it.
3. Atari boots from BRAM; SIO disk traffic is serviced live by the supervisor.
4. Capture → SDRAM framebuffer → scaler → 720p HDMI.

**SD config hierarchy** (`firmware/supervisor/config.c`):
`/config.json {default:"/atari/800"}` → machine `/atari/800/config.json`
(`memory-map[]` OS/RAM entries, `cartridge{directory,default}`,
`disks{directory,drives[]}`) → per-cart and per-disk `config.json {file,image,type}`.

## SIO disk emulator (`firmware/supervisor/sio.c`)

Port of the JOP `SioDiskEmu.java` to the RP2040, driving the existing
`SioBridge.scala` hardware serializer over SPI. `sio_poll()` drains command
frames from the bridge RX FIFO, services READ_SECTOR / GET_STATUS / GET_SPEED
from ATR images via FatFs, and streams sectors back through the TX FIFO (19200
baud, 8-bit end-around-carry checksums). Writes NAK (read-only for now).
Console `'D'` prints counters. **Jumpman confirmed booting off emulated D1:.**

## Supervisor menu (`firmware/supervisor/supervisor.c`)

**Alt-F12** (or console `'~'`) pauses the Atari and opens a menu to **live-edit**
the boot selection — cart → none/pick, disk in D1:–D4: — then reboot. Edits are
an **in-memory copy**; the SD JSON is untouched unless the explicit "save as
default" action is invoked (`config_save`). Rendering is over the **serial
console** today; an on-screen HDMI renderer (RP2040 rasterizes into the SDRAM
framebuffer) is designed but not built. Alt-F12 hotkey is coded but untested (no
physical keyboard connected yet).

## Control / SPI protocol quick reference

- Control byte: bit0 reset, bit1 start, bit2 select, bit3 option, bit4 halt
  (0x10=halt/pause, 0x11=halt+reset pulse, 0x00=release).
- SIO SPI cmds: `'Q'`(0x51)=SIO reg write {addr,lo,hi}; `'S'`(0x53)=SIO reg read
  (latched, returned in a status frame). See `fpga_link.h`.

## Recent commit trail

- `13f8612` Config-driven boot, SIO disk emulator, and supervisor menu
- `e15e68c` STATUS.md handoff snapshot (Atari in BRAM, SDRAM framebuffer-only)
- `9d3192e` Step 3: sever Atari↔SDRAM path; SDRAM framebuffer-only
- `222c79f` Step 2: cart into shared top-of-RAM BRAM (read-only)
- `853fbcf` Step 1: supervisor loads 800 OS into blank BRAM from SD

## Rebuild / flash / boot (quick reference)

```bash
# Regenerate SystemVerilog from SpinalHDL (after Scala edits):
sbt "atari/runMain atari800.Atari800Rp2040HdmiLgSv"
# Build + program the FPGA (JTAG via Altera Blaster — see next steps):
cd boards/atari-800-rp2040-qmtech-10cl025/atari_starraiders && make build && make program
# Build + flash the supervisor firmware (RP2040, via SWD debug probe):
cd firmware/supervisor/build && make -j4
openocd -f interface/cmsis-dap.cfg -c "adapter speed 5000; transport select swd" \
        -f target/rp2040.cfg -c "program supervisor.elf verify reset exit"
```

**Caveat:** the running SioBridge `.sof` is volatile JTAG SRAM; the config flash
still holds the pre-SioBridge bitstream, so a power-cycle reverts. Re-program over
the Blaster (or regenerate the `.jic`) after a power-cycle until the RP2040 JTAG
loader (below) lands.

## Remaining / next steps

1. **RP2040 JTAG loader** (next milestone): let the RP2040 configure the FPGA
   with a `.sof`/`.rbf` — both **from the host for dev** (removing the Altera
   Blaster dependency) and **from the SD card** at power-on. Based on
   **dirtyJTAG** (RP2040 as an OpenOCD-compatible JTAG probe). First step: audit
   which FPGA config/JTAG pins are wired to the RP2040. See
   `memory/project_sdcard_boot_vision.md`.
2. **On-screen supervisor menu** on HDMI (RP2040 → SDRAM framebuffer + small FPGA
   hw assist). Designed, not built.
3. **SIO write support** (currently read-only / NAK on write).
4. **Banked carts**: cart read uses flat `ramData` (fine for 8K/16K non-banked);
   bank-switched carts need `emuCartAddress`.
