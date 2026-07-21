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

**Hardware verified on the two active boards** (see **[STATUS.md](STATUS.md)**):
- **10CL025 + RP2040-STAMP** (720p): Star Raiders / Pole Position carts, DOS 2.5
  off an emulated SIO disk, the Alt-F12 on-screen menu, and SD-side FPGA boot.
- **Wukong (Artix-7) + Pico 2 W** (native 1080p60): a fully self-contained SD
  appliance — the Pico JTAG-configures the FPGA from the card, then loads OS +
  cart + disks from SD; plus an optional WiFi web UI for managing the card.

![Playing Star Raiders](boards/atari-800-rp2040-qmtech-10cl025/20260721_134809.jpg)

## Origins

This is a ground-up SpinalHDL rewrite of the **Atari 800 core from
[gyurco/Atari800XL](https://github.com/gyurco/Atari800XL)**, which targets
multiple FPGA boards with VHDL. The chip implementations (6502 CPU, ANTIC,
GTIA, POKEY, PIA) were rewritten in SpinalHDL based on the original VHDL
and Atari hardware documentation.

## Project Structure

```
build.sbt               Unified SBT build (Scala 2.13.18 / SpinalHDL 1.12.2)
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
  atari-800-rp2040-colorlight/  Unified RP2040 + Colorlight i5/i9 (ECP5) / i9+ (Artix) SODIMM base board
  atari-800-rp2040-qmtech-xc7a100t/  Custom Artix-7 baseboard (HDMI pin decision + docs)
  qm_xc7a100t_wukong/           Wukong board hardware notes (dual SDR + DDR3, pinouts)
  i5-7v0/                       Colorlight i5 v7.0 (ECP5 LFE5U-25F, yosys/nextpnr)
  i9-7v2/                       Colorlight i9 v7.2 (ECP5 LFE5U-45F, yosys/nextpnr)
  i9plus-6v1/                   Colorlight i9+ v6.1 (XC7A50T, Vivado) + hdmi_test/
generated/               SpinalHDL output (.sv + .bin) — gitignored
tools/
  atari_keyboard.py      Serial keyboard/joystick relay (host-side Python)
  atari_peek.py          Peek Atari RAM via the supervisor 'M' command
  atari_peek_raw.py      Raw 'M' peek for protocol debugging
  pico_kbd_test/         Pi Pico USB HID test fixture (TinyUSB-based)
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

### Board builds

Each active board builds from its own directory:

- **Wukong (Artix-7, native 1080p60)** — `boards/atari-800-wukong-1080/`:
  `make atari` (build the `.bit` → push it to the SD → the supervisor configures
  the FPGA and boots the Atari, all over USB). See that board's `README.md`.
- **10CL025 + RP2040-STAMP (720p)** — `boards/atari-800-rp2040-qmtech-10cl025/atari_starraiders/`:
  `make build` (Quartus) → `quartus_cpf -o bitstream_compression=off *.sof *.rbf`,
  then copy the `.rbf` to the SD as `/atari/800/core.rbf` (see `STATUS.md`).

OS, cartridge, and disk images come from the SD card (a JSON config), not baked
into the bitstream.

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

The Atari (RAM + OS + cart) lives entirely in BRAM; the SDRAM carries only the
video framebuffer. OS/cart/disk images load from the SD card at runtime.

### Cyclone 10 LP — 10CL025 (10CL025 + RP2040-STAMP, 720p)

The Atari-in-BRAM + the SDRAM framebuffer scaler + the on-screen text overlay fit
the 10CL025 at **66 / 66 M9K** — the block-RAM budget is the tight constraint on
this part (see the M9K-budget notes in `STATUS.md`). Logic ~45%.

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

**Inspiration / references**
- **[MiST](https://github.com/mist-devel/mist-board)** and
  **[MiSTer](https://github.com/MiSTer-devel/Main_MiSTer)** — the FPGA
  retro-computing platforms whose core-design conventions informed this project.
  MiSTer's memory split in particular — low-latency SDR SDRAM for a core's main
  RAM, DDR for the framebuffer/scaler — is exactly the arrangement the Wukong
  board (W9825 SDR + MT41K DDR3) was chosen to provide, and the reference for
  eventually porting MiST/MiSTer cores to this Xilinx + Pico-supervisor world.

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
