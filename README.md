# atari800-spinalhdl

An **Atari 800 core written in SpinalHDL**, driven by an **RP2040 / Pico
supervisor** (C firmware) that configures the FPGA from an SD card at power-on,
hosts the USB keyboard, auto-boots from a JSON config, emulates SIO disk drives
(D1:–D4: from ATR images), and provides an on-screen (HDMI) Alt-F12 menu. The
6502 runs at real, **cycle-accurate 1.79 MHz** (with a runtime turbo toggle).

> **Active boards** — see **[STATUS.md](STATUS.md)** for the live snapshot:
> - **atari-800-rp2040-qmtech-10cl025** — Cyclone 10 LP 10CL025 + RP2040-STAMP,
>   **720p** HDMI. The original.
> - **atari-800-wukong-1080** — QMTech Wukong (Xilinx Artix-7 XC7A100T) + Pico 2 W,
>   **native 1080p60**, a fully self-contained SD appliance (FPGA bitstream + OS +
>   cart + disks all loaded from the card), with an optional **WiFi web UI** for
>   managing the SD over the network.
>
> Nothing proprietary lives in the FPGA image or firmware — the core `.rbf`/`.bit`,
> OS, cart, and disks all come from the SD card. *Earlier EP4CGX150 boards used a
> JOP Java soft-core as the supervisor; that has been removed in favour of the
> RP2040/Pico firmware, so the JOP build paths below no longer apply.*

## Status

The Atari 800 core boots and runs correctly in simulation and on real hardware:

- **Memo pad** (no cartridge) — displays "ATARI COMPUTER - MEMO PAD"
- **Atari BASIC** (built-in 8K ROM) — boots to READY prompt
- **Star Raiders** (8K cartridge ROM) — boots to title screen

All ANTIC display modes, GTIA colour rendering (including highres mode 2),
DMA pipeline, NMI/IRQ handling, and the 6502 CPU are verified working.
Frame capture produces correct PAL-palette colour output.

**Hardware verified** — memo pad and Star Raiders confirmed running on
QMTECH EP4CGX150 + DB_FPGA daughter board v4 (VGA output, 56.67 MHz). See
`boards/db_fpga_v4/qmtech-ep4cgx150/` for the DB_FPGA build path and
`boards/atari800-lg-v1/` for the in-progress ATARI-800-LG-V1 base-board path.

### Dual-PLL build

JOP supervisor at 80 MHz + Atari core at 56.67 MHz using two independent PLLs
with clock domain crossing. JOP has its own 32 MB SDRAM (W9825G6JH6) via
BmbSdramCtrl32; Atari runs BRAM-only (48K user space, with cart ROM
auto-replacing upper RAM). JOP handles USB keyboard (CH376S via SPI),
serial keyboard relay, joystick input, console keys, SIO disk emulation
(in progress), and cold reset.

DB_FPGA v4 wiring (`boards/db_fpga_v4/qmtech-ep4cgx150/atari_ep4cgx150_dualpll/`):
- **PMOD J10**: Joystick 1 (active low, DB-9)
- **PMOD J11**: CH376S SPI module (USB keyboard + SD card host)
- **UART**: CP2102N on DB_FPGA v4 — JOP serial boot (2 Mbaud) + keyboard relay

ATARI-800-LG-V1 base board wiring (`boards/atari800-lg-v1/qmtech-ep4cgx150/atari800_lg_v1/`):
- **DB-9 J3/J4**: Joysticks 1 and 2 directly on the base board
- **CH340 USB-serial**: onboard, JOP serial boot
- **CH376T x2**: keyboard CH376T + SD-card CH376T, both on base-board pins
- **VGA, audio, console keys**: 5-bit RGB DAC, sigma-delta audio, 4 console buttons

## Origins

This is a ground-up SpinalHDL rewrite of the **Atari 800 core from
[gyurco/Atari800XL](https://github.com/gyurco/Atari800XL)**, which targets
multiple FPGA boards with VHDL. The chip implementations (6502 CPU, ANTIC,
GTIA, POKEY, PIA) were rewritten in SpinalHDL based on the original VHDL
and Atari hardware documentation.

## Project Structure

```
build.sbt               Unified SBT build (Scala 2.13.18 / SpinalHDL 1.14.0)
atari/                   Atari 800 core
  src/main/scala/atari800/
    Atari800Core.scala        Top-level Atari 800 (CPU, ANTIC, GTIA, POKEY, PIA, MMU)
    Cpu65xx.scala             MOS 6502 CPU core
    Antic.scala               ANTIC (display list DMA, character/bitmap modes)
    Gtia.scala                GTIA (playfield/player-missile graphics, colours)
    Pokey.scala               POKEY (sound, keyboard, serial I/O, timers)
    Pia.scala                 PIA (parallel I/O, port B memory control)
    Mmu.scala                 Memory management unit
    CartLogic.scala           Cartridge slot logic (8K/16K, RD4/RD5, OSS/XEGS variants)
    AddressDecoder.scala      Memory map, SDRAM/ROM/RAM routing
    InternalRomRam.scala      OS ROM + internal RAM (cart auto-replaces upper RAM)
    FileRom.scala             Generic ROM from binary .rom file (loaded at elaboration)
    Scandoubler.scala         15kHz→31kHz VGA scandoubler
    GtiaPalette.scala         Full PAL/NTSC colour palette (256 entries)
    Atari800CoreSim.scala     Simulation top-level wrapper
    Atari800CoreSimTb.scala   Simulation testbench with frame capture
    Atari800DiskSimTb.scala   SIO disk simulation with ATR image loader
    Atari800CoreSimTb.scala   (+ Atari800DiskSimTb) sim testbenches with frame capture
    Atari800CoreSimpleSdram.scala  Core + SDRAM-framebuffer video wrapper (active boards)
    Atari800Rp2040HdmiLgTop.scala  10CL025 + RP2040-STAMP top (720p HDMI)
    Atari800WukongTop.scala        Wukong (Artix-7 XC7A100T) top (native 1080p60)
    Atari800Wukong1080Top.scala    Wukong Phase-0 1080p colour-bar bring-up
    Atari800Ecp5Hdmi720Top.scala   Colorlight i5 (ECP5) top (720p)
    RpAtariKeyboard.scala     RP2040/Pico SPI link: keyboard, control, loader, SIO, text overlay
    SioBridge.scala           Hardware SIO UART bridge (disk emulation)
    HasBusIo.scala            Peripheral register-bus trait (mixed into SioBridge)
    VideoFbWrite / VideoFbRead2 / SdramArbiter3 / SdramStatemachine  SDRAM framebuffer + DDA scaler
    TextOverlay720.scala / TextOverlay1080.scala  FPGA-native on-screen menu text
    GtiaPalette.scala         Full PAL/NTSC colour palette; Debounce.scala; Font8x16.scala
firmware/supervisor/     RP2040/Pico supervisor C firmware (config-boot, USB kbd, SIO,
                         menu, SD-side FPGA config; Pico 2 W: WiFi + HTTP SD manager).
                         Build via CMake: -DBOARD=qmtech (STAMP) | wukong | colorlight
boards/
  atari-800-rp2040-qmtech-10cl025/  ACTIVE: Cyclone 10 LP 10CL025 + RP2040-STAMP, 720p
    atari_starraiders/                Quartus project (make build → .sof → .rbf for SD)
  atari-800-wukong-1080/            ACTIVE: QMTech Wukong (Artix-7) + Pico 2 W, 1080p60
    vivado/                           Vivado project (rgb2dvi HDMI, W9825 SDRAM); Makefile
    README.md                         board doc (architecture, wiring, make atari / push-core)
  common/                       Shared per-FPGA-family entities (PLLs, etc.)
    cyclone4/ cyclone10/          PLL primitives
  db_fpga_v4/qmtech-ep4cgx150/  HISTORICAL: QMTECH EP4CGX150 (Cyclone IV GX) — JOP-era,
                                tops removed; hardware/pin reference only
  atari800-lg-v1/               ATARI-800-LG-V1 base board (CH340 UART, CH376T USB/SD, VGA, joysticks)
    qmtech-ep4cgx150/             EP4CGX150 core builds for V1 base board
      atari800_lg_v1/                Dual-PLL Atari + JOP (uses Atari800Ep4cgx150DualPllTop)
      atari800_lg_v1_bram/           BRAM-only build (no JOP, no SDRAM)
      ch376_test/                    CH376T standalone bring-up test
      vga_test/                      VGA test pattern (no SpinalHDL)
    qmtech-10cl025/               10CL025 core build for V1 base board
      atari800_lg_v1_10cl025/        BRAM-only build (40K RAM + cart + OS = 58 of 66 M9K)
  atari800-lg-v1.1/             ATARI-800-LG-V1.1 base board layout (next-rev hardware)
    pin-mapping.md                  U9 connector → FPGA pin cross-reference for all 3 cores
    {ep4cgx150,10cl025}/            v1_1_pins.tcl — Quartus pin assignment scripts
    xc7a100t/                       v1_1_pins.xdc — Vivado pin constraints
    hw/                             Gerbers, BOM, 3D model, assembly drawings
    Netlist_*.enet                  KiCad-exported netlist (source of truth)
  AC608/                        Cyclone 10 LP custom board (Quartus 25.1)
  i5-7v0/                       Colorlight i5 v7.0 (ECP5 LFE5U-25F, yosys/nextpnr)
  i9-7v2/                       Colorlight i9 v7.2 (ECP5 LFE5U-45F, yosys/nextpnr)
  i9plus-6v1/                   Colorlight i9+ v6.1 (XC7A50T, Vivado)
generated/               SpinalHDL output (.sv + .bin) — gitignored
unused_scala/            Archived/inactive modules
tools/
  atari_keyboard.py      Serial keyboard/joystick relay (host-side Python)
  atari_peek.py          Peek Atari RAM via the supervisor 'M' command
  atari_peek_raw.py      Raw 'M' peek for protocol debugging
  ch376_keyboard.py      CH376T USB-host keyboard helper (host-side)
  ch376_sdcard_test.py   CH376T SD-card protocol test
  ch376_spi_test.py      CH376T SPI mode test
  ch376s_test.py         CH376S SPI test (Pico MicroPython)
  ch376s_uart_test.py    CH376S UART test (Pico MicroPython)
  pico_kbd_test/         Pi Pico USB HID test fixture (TinyUSB-based)
Makefile                 Top-level build orchestration (legacy AC608 path)
```

## Building

### Prerequisites

- JDK 11+
- SBT 1.9+
- Intel Quartus Prime 25.1+ (Lite Edition) — for Cyclone 10 LP build
- yosys + nextpnr-ecp5 + ecppack — for ECP5 build (distro packages sufficient)
- Vivado 2025.2 — for Artix-7 build

### Clone with submodule

```sh
git clone --recurse-submodules <url>
# or after clone:
git submodule update --init
```

### ROM files

Binary ROM files live in the `roms/` directory and are loaded at elaboration
time by `FileRom.scala`. They are **not** embedded in Scala source.

`roms/` is gitignored (along with `*.rom` and `*.atr` everywhere) — Atari
OS, BASIC, and game ROMs/disk images are copyrighted, you must supply
your own. Expected filenames (under `roms/`):

- `atariosb.rom`, `atarios2.rom` — 800 OS ROMs (D000-DFFF, E000-FFFF)
- `atarixl.rom` — XL OS (alternate)
- `ataribas.rom` — Atari BASIC
- `Star Raiders.rom`, `*.rom` — 8K/16K cartridge images
- `*.atr` — disk images for SIO disk emulation

### Run simulation

```sh
# Boot with Star Raiders cartridge (default configuration)
sbt "atari/runMain atari800.Atari800CoreSimTb"

# To change the cartridge, edit Atari800CoreSimTb.scala:
#   cartridge_rom = "roms/YourGame.rom"
# Place the .rom file in the roms/ directory
```

Frame captures are written as PPM files to `sim_workspace/Atari800_boot_test/`.
Convert to PNG with ImageMagick: `convert frame.ppm frame.png`

### Generate SystemVerilog

```sh
sbt "atari/runMain atari800.Atari800WukongSv"          # Wukong (Artix-7, 1080p)
sbt "atari/runMain atari800.Atari800Rp2040HdmiLgSv"    # 10CL025 + RP2040-STAMP (720p)
```

Output lands in `generated/` (gitignored). The active boards drive it through
their own build (`boards/atari-800-wukong-1080/Makefile`,
`boards/atari-800-rp2040-qmtech-10cl025/atari_starraiders/Makefile`).

### EP4CGX150 + DB_FPGA v4 builds

Each project lives in its own directory under
`boards/db_fpga_v4/qmtech-ep4cgx150/`:

```sh
# Bare-metal (no JOP) — single PLL, BRAM-only
cd boards/db_fpga_v4/qmtech-ep4cgx150/atari_ep4cgx150
make generate   # SpinalHDL → generated/Atari800Ep4cgx150Top.sv
make build      # Quartus compile
make program    # JTAG via USB-Blaster

# Single-PLL JOP (56.67 MHz shared clock)
cd boards/db_fpga_v4/qmtech-ep4cgx150/atari_ep4cgx150_jop
make generate
make build
make program
make download   # Serial boot AtariSupervisor.jop (500 kbaud)

# Dual-PLL JOP (80 MHz JOP + 56.67 MHz Atari, recommended)
cd boards/db_fpga_v4/qmtech-ep4cgx150/atari_ep4cgx150_dualpll
make generate
make build
make program
make download   # Serial boot AtariSupervisor.jop
make run        # program + download + monitor in one step
```

All Quartus build artifacts go to `output_files/` per project. *(These EP4CGX150
"JOP" projects are historical — their SpinalHDL tops were removed with the JOP
soft-core; kept for the hardware/pin reference only.)*

Cartridge ROM is set via `cartridge_rom` in the top-level Scala file
(default: `roms/Star Raiders.rom`). Place `.rom` files in the `roms/` directory.

### ATARI-800-LG-V1 base-board builds

The LG-V1 base board houses one of two QMTECH core boards. Each core has
its own per-project subtree under `boards/atari800-lg-v1/`:

```sh
# QMTECH EP4CGX150 — main dual-PLL Atari + JOP build
cd boards/atari800-lg-v1/qmtech-ep4cgx150/atari800_lg_v1
make generate        # → generated/Atari800Ep4cgx150DualPllTop.sv
make build && make program

# QMTECH EP4CGX150 — BRAM-only (no JOP, no SDRAM)
cd boards/atari800-lg-v1/qmtech-ep4cgx150/atari800_lg_v1_bram
make generate        # → generated/Atari800LgV1Top.sv
make build && make program

# QMTECH EP4CGX150 — CH376T standalone bring-up test
cd boards/atari800-lg-v1/qmtech-ep4cgx150/ch376_test
make generate && make build && make program

# QMTECH EP4CGX150 — VGA test pattern (no SpinalHDL step)
cd boards/atari800-lg-v1/qmtech-ep4cgx150/vga_test
make build && make program

# QMTECH 10CL025 — BRAM-only fit (40K RAM + cart + OS = 58 of 66 M9K)
cd boards/atari800-lg-v1/qmtech-10cl025/atari800_lg_v1_10cl025
make generate && make build && make program
```

### ATARI-800-LG-V1.1 pin assignments

The next-rev base board uses a single 128-pin connector U9. There are no
build projects yet — instead, `boards/atari800-lg-v1.1/` ships the
schematic netlist, gerbers, and per-FPGA pin assignment scripts ready to
source from a Quartus QSF or Vivado XDC:

- `pin-mapping.md` — full U9 → FPGA pin cross-reference (EP4CGX150, 10CL025, XC7A100T)
- `ep4cgx150/v1_1_pins.tcl`, `10cl025/v1_1_pins.tcl` — Quartus pin assignment scripts
- `xc7a100t/v1_1_pins.xdc` — Vivado pin constraints

### ECP5 synthesis (Colorlight i5 — LFE5U-25F)

```sh
cd boards/i5-7v0
make generate   # SpinalHDL → generated/Atari800Ecp5BramTop.sv
make synth      # yosys synthesis + utilisation report
make pnr        # nextpnr place-and-route + timing
make bitstream
```

### ECP5 synthesis (Colorlight i9 module — LFE5U-45F)

```sh
cd boards/i9-7v2
make synth    # synthesis (device-agnostic; 39% LUT utilisation)
make pnr      # requires colorlight_i9.lpf pin assignments
```

### Vivado synthesis (Colorlight i9+ — XC7A50T)

```sh
cd boards/i9plus-6v1
/opt/xilinx/2025.2/Vivado/bin/vivado -mode batch -source synth_check.tcl
# Reports: synth_util.rpt, synth_timing.rpt
```

### Legacy top-level build (AC608 / Cyclone 10 LP)

The repo root retains a `Makefile` with the original AC608 build flow
(`make generate`, `make quartus`, etc. — drives `boards/AC608/`). All
other boards have moved to the per-project layout above.

## Simulation

The testbench (`Atari800CoreSimTb`) provides:

- **Behavioral SDRAM model** — instant response, no controller needed
- **Raw Atari video frame capture** — samples VIDEO_B on colour clock,
  applies full 256-entry PAL palette, outputs PPM images
- **Diagnostic tracing** — DMA pipeline, ANTIC/GTIA register writes,
  NMI/IRQ events, CPU state snapshots, display shift register contents
- **Cartridge ROM loading** — any 8K `.rom` file loaded at elaboration time
  into the $A000-$BFFF cartridge slot

Configuration in `Atari800CoreSim.scala`:
- `cycle_length = 32` — simulation speed (32 main clocks per colour clock)
- `internal_rom = 3` — Atari 800 OS (atariosb.rom + atarios2.rom)
- `internal_ram = 16384` — 16K internal RAM (48K total with SDRAM model)
- `cartridge_rom` — path to 8K/16K ROM file (empty = no cartridge)

## Architecture

- **Atari 800 core**: 6502 CPU, ANTIC (DMA/display), GTIA (graphics/colour),
  POKEY (sound/keyboard/timers), PIA (I/O ports), MMU, cartridge logic — RAM + OS
  + cart all in **BRAM**, so ANTIC display DMA is never starved by contended SDRAM.
- **RP2040/Pico supervisor** (C firmware, `firmware/supervisor/`): SD-side FPGA
  config, USB-host keyboard, config-driven auto-boot, SIO disk emulation
  (`SioBridge`), an Alt-F12 on-screen menu, and (Pico 2 W) an optional WiFi web UI.
  Talks to the FPGA over a dedicated SPI link (`RpAtariKeyboard`).
- **Video**: the raw GTIA frame is captured to an **SDRAM framebuffer**, DDA-scaled
  to the output resolution (720p or 1080p) by `VideoFbRead2`, then `GtiaPalette` →
  HDMI (Digilent `rgb2dvi` on Artix; an ECP5/Cyclone TMDS serializer elsewhere).
- **Audio**: POKEY through a sigma-delta PWM DAC.

## Bugs Fixed

### GTIA highres colour (mode 2 text invisible)

The VHDL-to-SpinalHDL conversion had an off-by-one bit index in the highres
luminance replacement. VHDL declares colour registers as `std_logic_vector(7
downto 1)` (indices 1-7), while SpinalHDL uses 0-based indexing (0-6). The
VHDL index `(3 downto 1)` was copied directly instead of adjusting to
`(2 downto 0)`, causing the foreground colour to equal the background in
mode 2 (ANTIC's 40-column text mode). Fix: `Gtia.scala` lines 611-612.

## Resource Utilisation

OS and cartridge ROMs are loaded from binary `.rom` files at elaboration time
into block RAM (bare-metal builds). In JOP supervisor builds, cartridge and
BASIC ROMs can be loaded at runtime from SD card into internal RAM.
All targets meet timing at their respective clock frequencies.

### Cyclone IV GX — EP4CGX150DF27I7 (QMTECH EP4CGX150, **hardware verified**)

Bare-metal bring-up top (no JOP). Atari 800 OS + 16K internal RAM + Star Raiders ROM.

| Resource | Used | Available | % |
|---|---|---|---|
| Logic Elements | 3,604 | 149,760 | 2% |
| Memory bits | 313,678 | 6,635,520 | 5% |
| PLLs | 1 | 8 | 13% |

Dual-PLL build (Atari + JOP + SDRAM, **hardware verified**).
JOP at 80 MHz with 32 MB SDRAM, Atari at 56.67 MHz BRAM-only.

| Resource | Used | Available | % |
|---|---|---|---|
| Logic Elements | 13,697 | 149,760 | 9% |
| Memory bits | 659,600 | 6,635,520 | 10% |
| DSP 9-bit | 8 | 720 | 1% |
| PLLs | 2 | 8 | 25% |

### Cyclone 10 LP — 10CL025YU256 (QMTECH 10CL025 + LG-V1, BRAM-only target)

Atari 800 BRAM-only fit (no JOP, no SDRAM): 40K RAM + 8K cart + 10K OS
ROM. Cart ROM auto-replaces upper RAM so total BRAM stays constant —
~58 of 66 M9K used, leaving headroom for the I/O wrapper. Same Verilog
as the EP4CGX150 LG-V1 BRAM build.

### Cyclone 10 LP — 10CL025YU256C8G (custom board / AC608)

Earlier custom-board fit, synthesised with Quartus Prime 25.1 Lite Edition.

| Resource | Used | Available | % |
|---|---|---|---|
| Logic Elements | 15,411 | 24,624 | 63% |
| Memory bits | 309,198 | 608,256 | 51% |
| DSP 9-bit | 10 | 132 | 8% |
| PLLs | 0 (stub) | 4 | 0% |

### ECP5 — LFE5U-25F (Colorlight i5)

| Resource | Used | Available | % |
|---|---|---|---|
| LUT4 | 17,221 | 24,000 | 72% |
| DP16KD BRAM | 19 | 56 | 34% |
| TRELLIS_FF | 7,619 | — | — |

### ECP5 — LFE5U-45F (Colorlight i9 module)

Same synthesis result as i5 (synth_ecp5 is device-agnostic); more headroom:

| Resource | Used | Available | % |
|---|---|---|---|
| LUT4 | 17,221 | 44,000 | 39% |
| DP16KD BRAM | 19 | 108 | 18% |
| TRELLIS_FF | 7,619 | — | — |

Note: i9 has 32-bit wide SDRAM (M12L64322A). SdramStatemachine configured for
16-bit; full 32-bit width support is pending.

### Artix-7 — XC7A50T (Colorlight i9+)

| Resource | Used | Available | % |
|---|---|---|---|
| Slice LUTs | 7,649 | 32,600 | 23% |
| BRAM Tiles | ~20 | 75 | ~27% |
| DSP48E1 | 5 | 120 | 4% |

## Acknowledgements

This project builds on a number of open-source works:

**FPGA / HDL**
- **[gyurco/Atari800XL](https://github.com/gyurco/Atari800XL)** (GPL-2.0) — the
  VHDL Atari 800 core this project is a ground-up SpinalHDL rewrite of.
- **[SpinalHDL](https://github.com/SpinalHDL/SpinalHDL)** — the hardware
  description language and toolchain the entire core is written in.
- **[Digilent rgb2dvi](https://github.com/Digilent/vivado-library)** — the
  TMDS / `OSERDESE2` encoder used for native 1080p60 HDMI on the Wukong (Artix-7)
  board (bundled under `boards/atari-800-wukong-1080/vivado/src/rgb2dvi/`).

**RP2040 / Pico supervisor firmware** (`firmware/supervisor/`)
- **[Raspberry Pi Pico SDK](https://github.com/raspberrypi/pico-sdk)**
  (BSD-3-Clause) — the RP2040 / RP2350 platform, build system, and drivers.
- **[TinyUSB](https://github.com/hathach/tinyusb)** (MIT, Ha Thach) — the USB
  device stack (CDC console, MSC drive, USB-Blaster vendor iface) and host stack.
- **[Pico-PIO-USB](https://github.com/sekigon-gonnoc/Pico-PIO-USB)** (MIT,
  sekigon-gonnoc) — bit-banged USB host over the RP2040/RP2350 PIO, for the USB
  keyboard.
- **[FatFs](http://elm-chan.org/fsw/ff/)** (1-clause BSD, ChaN) — FAT filesystem
  for reading OS/cart/disk images and JSON config from the SD card.
- **[lwIP](https://savannah.nongnu.org/projects/lwip/)** + the **CYW43 driver**
  (both via the Pico SDK) — TCP/IP and WiFi behind the Pico 2 W's on-network
  HTTP SD manager.

**FPGA programming**
- **[dirtyJTAG](https://github.com/jeanthom/DirtyJTAG)** (GPL-2.0, Jean THOMAS) —
  reference for the on-Pico JTAG loader (host **USB-Blaster emulation** so tools
  program directly, plus **SD-side FPGA config at power-on**).
- **[openFPGALoader](https://github.com/trabucayre/openFPGALoader)** — host-side
  FPGA programming (`dirtyJtag` / `usb-blaster` cables); its Xilinx `MEM_MODE`
  bit-ordering guided the Pico's stream-a-`.bit`-over-JTAG config on the Wukong.

**Historical**
- **[JOP](https://github.com/jop-devel/jop)** — the Java Optimized Processor
  soft-core (Martin Schoeberl) served as the earlier EP4CGX150 supervisor; it has
  been removed in favour of the RP2040/Pico firmware.

## License

See individual source files. The Atari 800 core is derived from
[gyurco/Atari800XL](https://github.com/gyurco/Atari800XL) (GPL-2.0). Bundled
third-party firmware libraries retain their own licenses (see
`firmware/supervisor/lib/*/LICENSE` and the Acknowledgements above).
