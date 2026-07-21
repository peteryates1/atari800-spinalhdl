# Project Status — 2026-07-21

Handoff snapshot. There are now **two hardware-verified Atari boards**:

- **rp2040-qmtech-10cl025** — QMTech Cyclone 10 LP + RP2040-STAMP + HDMI,
  **720p**. The original; detailed below.
- **wukong-1080** — QMTech Wukong (Xilinx Artix-7 XC7A100T) + Pico 2 W,
  **native 1080p60** and a fully self-contained SD appliance. Summarised in the next
  section; full details in `boards/wukong-1080/README.md`.

Everything below is committed and hardware-verified unless noted.

## TL;DR — current state

The Atari 800 runs **entirely from BRAM** (RAM + OS + cartridge); **SDRAM carries
only the 720p framebuffer**. The RP2040-STAMP is the **supervisor**: at power-on it
**configures the FPGA from the SD card** (SD-side boot), then brings up USB + SD and
**auto-boots the Atari from a JSON config**, emulates **SIO disk drives** (D1:–D4:)
from ATR images, and hosts an **Alt-F12 supervisor menu** (rendered on HDMI) to
pause/live-edit/reboot. **Nothing proprietary is in the FPGA image or firmware** —
core `.rbf`, OS, cart, and disks all live on SD. The 6502 runs at
**real, cycle-accurate 1.79 MHz**, with an optional **runtime turbo toggle** (`[t]`
in the supervisor menu) that unthrottles it to ~18.8×.

Confirmed on hardware: **Star Raiders** (cart) and **Defender** (16K cart, correct
speed) over 720p HDMI, **Jumpman** off an emulated SIO disk in D1:, the **Alt-F12
supervisor menu on HDMI**, and **SD-side FPGA boot** surviving a cold power cycle.

Resource use: logic ~45%, BRAM **66/66 M9K**, timing clean (the −10.7 ns
min-pulse-width warning on the 371 MHz TMDS clock is a known model artifact).

## Second board — QMTech Wukong (native 1080p60 appliance)

`boards/wukong-1080/` (Xilinx Artix-7 XC7A100T + **Pico 2 W** supervisor).
Same core + video pipeline as the 10CL025, retargeted to Artix and **native
1920×1080p60** (Digilent rgb2dvi/OSERDESE2), so the supervisor text is crisp with no
monitor-upscale shimmer. Reuses the memory split (Atari fully in BRAM, W9825 SDRAM =
framebuffer only) and the cycle-accurate 1.79 MHz 6502 with runtime turbo.

**It is a fully self-contained SD appliance.** On a cold power-on the Pico
**JTAG-configures the Artix from `/atari/800/core.bit` on the SD**, then loads the
OS + cart + disks from SD into BRAM and boots the Atari — **no host, no cable,
nothing proprietary in the bitstream**. USB keyboard (incl. F5–F7 = Start/Select/
Option, **F8 = SYSTEM RESET**), SIO disk emulation, and an on-screen Alt-F12 menu all
work. Hardware-verified: Star Raiders / Pole Position (carts), DOS 2.5 off emulated
D1:, and a self-booting cold power cycle from the card alone.

Dev loop is `make atari` (build → push the `.bit` to SD → supervisor configures +
boots it, all over USB). See the board README for architecture, wiring, the deploy
tool (`push_file.py` / `make push-core`), and the supervisor console commands.

## Supervisor UX + network SD manager

Improvements to the supervisor, verified on **both** boards unless noted:
- **Cart/disk pickers are A-Z sorted + type-to-filter** (Alt-F12 → `[c]` / `[1-4]`):
  type letters to narrow the list, a digit picks (`0` = none, `1-9` = filtered item),
  Enter picks the top match, Backspace edits, ESC clears then cancels. Scales past a
  screenful. Same behaviour on the console and the on-screen (USB-keyboard) menu.
- **F8 → SYSTEM RESET** (in `RpAtariKeyboard`), joining F5/F6/F7 = Start/Select/Option.
- Turbo toggle, pause/resume, live cart/disk edit, save/reload config — as before.

**Network SD manager — Pico 2 W only** (uses the on-board CYW43439; compile-gated to
`_w` boards). Toggle WiFi from the menu `[w]` or console `N` — it shows "connecting…"
then the IP. While up, a small **HTTP web UI at `http://<ip>/`** browses the SD
(clickable breadcrumb path), uploads carts/disks (drag-drop, streamed straight to SD),
creates folders, deletes, and filters the current folder. Credentials live in
`/wifi.txt` on the SD (line 1 SSID, line 2 password). Off by default. **Not available
on the RP2040-STAMP** (no radio).

**Cross-board note (2026-07-21):** the shared firmware (sort + type-to-filter) and the
FPGA F8 reset both regression-pass on the original **10CL025 + RP2040-STAMP**. That
STAMP's **native-USB connector physically broke**, so on that board there is no CDC
console / MSC drive / USB-Blaster: flash the firmware over **SWD** (`rp2040.cfg`;
`build/` = BOARD=qmtech, PICO_BOARD=pico) and update the SD with a **card reader**.
Rebuild its Altera FPGA for F8 in
`boards/rp2040-qmtech-10cl025/atari_starraiders` (`make build` →
`quartus_cpf -o bitstream_compression=off *.sof *.rbf` → copy to SD `/atari/800/core.rbf`).

## Architecture

**Memory split**
- **BRAM (inside the FPGA):** Atari 48 KB RAM (0000–BFFF), 800 OS ROM
  (D800–FFFF), and the cartridge (8K at A000–BFFF or 16K at 8000–BFFF, read-only).
- **SDRAM (external):** the 720p framebuffer only (triple-buffered), written by
  the video capture, read by the scaler. No CPU/ANTIC/loader traffic.

**Why:** ANTIC's real-time display DMA can't tolerate SDRAM contention/latency.
Putting the Atari fully in BRAM removes the shared resource. See
`memory/project_antic_ram_in_bram.md`.

**CPU timing — cycle-accurate, with a runtime turbo switch.**
`THROTTLE_COUNT_6502 = 0` runs the 6502 at real 1.79 MHz; the memory arbiter
correctly hands ANTIC-DMA + refresh cycles away from the CPU. (It was `31` = "run as
fast as memory allows" — fine on the old slow-SDRAM design, but ~18.8× **turbo** once
RAM moved to always-ready BRAM, which made CPU-bound games like Defender unplayably
fast while frame-locked games masked it. Sim-proven with `Atari800CpuCycleSimTb`.)
The throttle is now driven live from the supervisor: the top does
`THROTTLE_COUNT_6502 := Mux(ctrlTurbo, 31, 0)`, where `ctrlTurbo` is control-frame
bit 6 (see below). The **`[t]` menu toggle** flips it at runtime — 0 (real) ↔ 31
(turbo) — and it persists across pause/resume but defaults **off** on an RP2040
reset (not saved to config). Turbo changes **game-logic speed only**, not the frame
rate (ANTIC/GTIA stay 60 Hz), so its effect shows in motion, not on a static screen.
See `memory/project_cpu_cycle_stealing.md`.

**Load path (supervisor → BRAM):** supervisor streams OS/cart over the SPI link
into the RAM/ROM BRAM write ports while the Atari is halted. Loader `ldDest`
(`'B'`=0x42 SPI cmd): 0=SDRAM (severed/no-op), 1=BRAM-OS, 2=BRAM-RAM.

## FPGA configuration — SD-side boot

The board's onboard **EPCS/EPCQ64 config flash** holds a safe baseline bitstream
(loads at power-on). The RP2040 then **overrides it from SD**: it stages
`/atari/800/core.rbf` into its own flash (only when the file's size/mtime change —
no wear per boot), then **JTAG-configures the FPGA from it** (bit-banged Altera
config over GPIO0–3, after `tuh_init()` so it doesn't fight PIO-USB for a PIO SM).
So the **SD card is the source of truth for the FPGA image** — drop a new `.rbf`
on SD and power-cycle. A bad/absent `.rbf` is safe: the EPCS baseline stays.
(`firmware/supervisor/fpga_config.c`, `main.c`. See
`memory/project_rp2040_jtag_loader.md`.)

For dev, the RP2040 also emulates an **Altera USB-Blaster** (09fb:6001), so Quartus
programs a `.sof` directly over the same JTAG pins — no Altera Blaster needed.

## Boot process — config-driven, auto at power-on

1. Power-on: EPCS configures the FPGA (baseline); RP2040 boots, mounts SD, stages
   + **auto-configures the FPGA from `/atari/800/core.rbf`**; SDRAM inits; Atari
   held halted until `sdramReady`. USB (CDC console + PIO-USB keyboard), SPI up.
2. **Auto-boot** (`do_boot()`): mount SD → read the config hierarchy → halt 6502
   → stream OS + cart into BRAM → mount disks → reset. Console `'B'` re-runs it.
3. Atari boots from BRAM; SIO disk traffic is serviced live by the supervisor.
4. Capture → SDRAM framebuffer → scaler → 720p HDMI.

**SD config hierarchy** (`firmware/supervisor/config.c`):
`/config.json {default:"/atari/800"}` → machine `/atari/800/config.json`
(`memory-map[]` OS/RAM entries, `cartridge{directory,default}`,
`disks{directory,drives[]}`) → per-cart and per-disk `config.json {file,image,type}`
(cart `type:"8K"` → A000, `type:"16K"` → 8000). `/atari/800/core.rbf` = FPGA image.

## SIO disk emulator (`firmware/supervisor/sio.c`)

The RP2040 SIO disk emulator drives the `SioBridge.scala`
hardware serializer over SPI. `sio_poll()` drains command frames, services
READ_SECTOR / GET_STATUS / GET_SPEED from ATR images via FatFs, streams sectors
back (19200 baud, 8-bit end-around-carry checksums). Writes NAK (read-only).
Console `'D'` prints counters. **Jumpman confirmed booting off emulated D1:.**

## Supervisor menu (`firmware/supervisor/supervisor.c`)

**Alt-F12** (or console `'~'`) pauses the Atari and opens a menu to **live-edit**
the boot selection — cart → none/pick, disk in D1:–D4: — then reboot. Edits are an
**in-memory copy**; the SD JSON is untouched unless "save as default" is invoked.
The menu also carries a **`[t]` turbo toggle** (6502 real ↔ ~18.8×), applied live
without a reboot.

**Rendered on HDMI** by an FPGA-native text overlay (`TextOverlay720.scala`, an
8×16 font generated directly at 1280×720, no SDRAM/scaler; the RP2040 streams a
40×15 char grid over the SPI `'T'` command). **CAVEAT — text shimmers on 1080p
monitors:** they upscale our 720p by 1.5× (non-integer), which crawls sharp text.
This is a monitor artifact, not our signal (proven — Atari white-on-black is solid
on the same monitor), and it is **unfixable on this board**: at the 60 Hz the
monitor needs, the soft TMDS serializer caps output at 720p60; native 1080p60 is
beyond this Cyclone 10 LP and the monitor rejects 1080p30. Crisp text needs an
ECP5/Artix (native 1080p60). See `memory/project_supervisor_menu.md`.

## Control / SPI protocol quick reference

- Control byte (`'C'`): bit0 reset, bit1 start, bit2 select, bit3 option, bit4 halt,
  bit5 supDisplay (show overlay), **bit6 turbo (6502 unthrottled)**. 0x30 = halt +
  supDisplay (menu), 0x00 = release; OR in 0x40 to hold turbo across either.
- Loader `'W'`/`'R'`/`'V'`/`'B'`; SIO `'Q'`/`'S'`; overlay `'T'`; control `'C'`.
  See `firmware/supervisor/fpga_link.h` and `RpAtariKeyboard.scala`.

## Repo cleanup (2026-07-21)

The **legacy Java soft-core supervisor was retired** (replaced by the RP2040/Pico
firmware) and the tree was swept for anything it left behind. Removed, all
recoverable from git history:

- **Historical boards** — the old soft-core submodule, the `java/` tree, and the
  EP4CGX150, LG-V1, AC608, CH376 board dirs + their SpinalHDL tops.
- **Dead board fit-checks** — `i5-7v0/Makefile`, the whole `i9-7v2/` dir, and
  `i9plus-6v1/synth_check.tcl` (fit-checks against deleted tops). The live
  builds — `i5-7v0/oddrx2f_720/` (ECP5 720p) and `i9plus-6v1/hdmi_test/` (Artix 1080p
  timing probe) — were kept.
- **12 unreachable Scala sources (~1,560 lines)** — `Mmu` (folded into
  `AddressDecoder`), the PS/2 keyboard pair (superseded by `RpAtariKeyboard`), the two
  VGA overlay muxes (superseded by `TextOverlay720/1080`), `SdramArbiter`+`BmbToSdramReq`
  (superseded by `SdramArbiter3`), and the unwired `PicoBus`/`I2cSlave`/`HdmiLineBuf`/
  `AudioPwm`/`Bundles`. A reachability audit from the live roots confirmed nothing else
  is dead; `atari/src/main` is now 98 Scala files, all reachable.
- **Firmware** — the unused `font8x16.h` (on-screen text is FPGA-rendered, not RP2040)
  and 2.8 MB of vendored FatFs HTML docs. The supervisor C code audited clean: every
  `.c` compiles into the target, no unused functions.
- **README** refreshed throughout (updated intro, Acknowledgements crediting
  gyurco/Atari800XL + MiST/MiSTer inspiration, current build/sim/resource sections).

## Recent commit trail

- `8df7c84`/`91f1ca9`/`a53892d` cleanup: remove dead firmware header + FatFs docs,
  12 unreachable Scala sources, and dead board fit-check files (see above)
- `8be38c6`/`17af1c3` retire the legacy soft-core supervisor: drop the old submodule,
  Java, and the historical EP4CGX150/AC608/CH376 boards + tops
- `50e1067` turbo mode: runtime 6502 speed toggle from the supervisor menu (`[t]`)
- `13f8612` Config-driven boot, SIO disk emulator, and supervisor menu
- `e15e68c` Add STATUS.md: handoff snapshot (Atari fully in BRAM, SDRAM fb-only)
- `9d3192e`/`222c79f`/`853fbcf` Steps 3/2/1: migrate Atari RAM+cart+OS into BRAM,
  sever the Atari↔SDRAM path (SDRAM becomes framebuffer-only)
- `e5aaf33` Fix CPU turbo: THROTTLE_COUNT_6502 31→0 (real 1.79 MHz, cycle-accurate)
- `ce849d8` sim: measure CPU bus-cycles/frame vs ANTIC DMA (cycle-stealing baseline)
- `929c4d6` supervisor: auto-configure FPGA from SD core.rbf at boot (SD-side boot)
- `db2d790` Supervisor menu on HDMI: FPGA-native text overlay (TextOverlay720)
- earlier: USB-Blaster emulation, MSC SD-over-USB, SD-side FPGA loader, on-screen
  text/framebuffer experiments (superseded by TextOverlay720)

## Rebuild / deploy (quick reference)

```bash
# 1. Regenerate SystemVerilog from SpinalHDL (after Scala edits):
sbt "atari/runMain atari800.Atari800Rp2040HdmiLgSv"
# 2. Build the FPGA bitstream:
cd boards/rp2040-qmtech-10cl025/atari_starraiders
/opt/altera/25.1/quartus/bin/quartus_sh --flow compile atari_starraiders
# 3a. DEV: program the .sof over the RP2040 USB-Blaster (volatile, lost on power-off):
/opt/altera/25.1/quartus/bin/quartus_pgm -c 1 --mode=JTAG -o "P;output_files/atari_starraiders.sof@1"
# 3b. PERSISTENT: convert to .rbf and drop it on the SD, then power-cycle:
/opt/altera/25.1/quartus/bin/quartus_cpf -c -o bitstream_compression=off \
    output_files/atari_starraiders.sof output_files/atari_starraiders.rbf
#     copy the .rbf to SD /atari/800/core.rbf (mount the RP2040 MSC drive, or physically)
# 4. Supervisor firmware (RP2040, via SWD debug probe):
cd firmware/supervisor/build && make -j4
openocd -f interface/cmsis-dap.cfg -f target/rp2040.cfg \
        -c "adapter speed 5000" -c "program build/supervisor.elf verify reset exit"
```

Note: a JTAG-programmed `.sof` (3a) resets FPGA BRAM, so reset the RP2040
afterwards (openocd `reset run`) to reload the Atari. The persistent path (3b) is
applied automatically on the next boot.

## Remaining / next steps

1. **Crisp supervisor text** — needs native 1080p60, i.e. a port to **ECP5**
   (Colorlight i5, top partially exists) or **Artix XC7A50T** (hard OSERDES, most
   headroom). Board scaffolding under `boards/i5-7v0/`, `boards/i9plus-6v1/`.
2. **Per-cell colour** in TextOverlay720 (currently global white-on-black).
3. **SIO write support** (currently read-only / NAK on write).
4. **Banked carts** — flat mapping handles 8K/16K straight carts (Star Raiders,
   Defender, Pole Position); bank-switched types need CartLogic bank plumbing.
5. **Sim polish**: 16K carts hit an `InternalRomRam:84` elaboration bug when
   compiled into the sim (hardware loads carts at runtime, so unaffected).
6. **Boot-loop guard** → drop into the on-screen supervisor on a failed SD config.
