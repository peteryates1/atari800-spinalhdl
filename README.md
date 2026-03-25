# atari800-spinalhdl

Atari 800 FPGA core written in SpinalHDL, integrated with the
[JOP](https://github.com/peteryates1/jop-spinalhdl) Java soft-core processor
for SD card, USB, OSD, and configuration management.

## Status

The Atari 800 core boots and runs correctly in simulation and on real hardware:

- **Memo pad** (no cartridge) — displays "ATARI COMPUTER - MEMO PAD"
- **Atari BASIC** (built-in 8K ROM) — boots to READY prompt
- **Star Raiders** (8K cartridge ROM) — boots to title screen

All ANTIC display modes, GTIA colour rendering (including highres mode 2),
DMA pipeline, NMI/IRQ handling, and the 6502 CPU are verified working.
Frame capture produces correct PAL-palette colour output.

**Hardware verified** — memo pad and Star Raiders confirmed running on
QMTECH EP4CGX150 + DB_FPGA daughter board v4 (VGA output, 56.67 MHz).
See `boards/ep4cgx150/`.

### Dual-PLL build (EP4CGX150)

JOP supervisor at 80 MHz + Atari core at 56.67 MHz using two independent PLLs
with clock domain crossing. JOP has its own 32 MB SDRAM (W9825G6JH6) via
BmbSdramCtrl32; Atari runs BRAM-only (48K internal RAM + OS/cartridge ROMs).
JOP handles USB keyboard (CH376S via SPI), serial keyboard relay, joystick
input, console keys, and cold reset.

- **PMOD J10**: Joystick 1 (active low, directly from DB-9 connector)
- **PMOD J11**: CH376S SPI module (USB keyboard + SD card host)
- **UART**: CP2102N on DB_FPGA v4 — JOP serial boot (2 Mbaud) + keyboard relay

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
    AddressDecoder.scala      Memory map, SDRAM/ROM/RAM routing
    InternalRomRam.scala      OS ROM + internal RAM (loads .rom files from roms/)
    FileRom.scala             Generic ROM from binary .rom file (loaded at elaboration)
    Scandoubler.scala         15kHz→31kHz VGA scandoubler
    GtiaPalette.scala         Full PAL/NTSC colour palette (256 entries)
    Atari800CoreSim.scala     Simulation top-level wrapper
    Atari800CoreSimTb.scala   Simulation testbench with frame capture
    Atari800JopTop.scala      FPGA top-level (Atari + JOP + SDRAM arbiter)
    Atari800Ep4cgx150Top.scala  Bare-metal bring-up top (EP4CGX150, no JOP)
    Atari800Ep4cgx150JopTop.scala  Single-PLL JOP top (EP4CGX150)
    Atari800Ep4cgx150DualPllTop.scala  Dual-PLL JOP top (80 MHz JOP + 56.67 MHz Atari)
    JopCoreForAtari.scala     JOP configuration + AtariCtrl I/O device
    JopCoreForAtariDualPll.scala  Dual-PLL JOP variant
    AtariCtrl.scala           JOP I/O device for Atari core control
    GenerateConstJava.scala   Const.java generator (drives jop ConstGenerator from atari config)
    Debounce.scala            Per-bit debounce with configurable stable count
java/apps/AtariSupervisor/   JOP application: Atari core supervisor (USB keyboard, serial relay)
jop-spinalhdl/           JOP soft-core (git submodule, pure library — no Atari-specific code)
boards/
  ep4cgx150/             QMTECH EP4CGX150 + DB_FPGA (Cyclone IV GX, hardware verified)
  AC608/                 Cyclone 10 LP custom board (Quartus 25.1)
  i5-7v0/                Colorlight i5 v7.0 (ECP5 LFE5U-25F, yosys/nextpnr)
  i9-7v2/                Colorlight i9 v7.2 (ECP5 LFE5U-45F, yosys/nextpnr)
  i9plus-6v1/            Colorlight i9+ v6.1 (XC7A50T, Vivado)
generated/               SpinalHDL output (.sv + .bin) — gitignored
unused_scala/            Archived/inactive modules
tools/
  atari_keyboard.py      Serial keyboard/joystick relay (host-side Python)
  ch376s_test.py         CH376S SPI test (Pico MicroPython)
  ch376s_uart_test.py    CH376S UART test (Pico MicroPython)
Makefile                 Build orchestration
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
time by `FileRom.scala`. They are **not** embedded in Scala source. You must
supply your own Atari OS and BASIC ROMs.

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
sbt "atari/runMain atari800.Atari800JopTopSv"
```

### EP4CGX150 builds (QMTECH EP4CGX150 + DB_FPGA)

```sh
cd boards/ep4cgx150

# Bare-metal (no JOP)
make generate   # SpinalHDL → Atari800Ep4cgx150Top.sv
make build      # Quartus compile
make program    # JTAG via USB-Blaster

# Single-PLL JOP (56.67 MHz shared clock)
make generate-jop
make build-jop
make program-jop
make download-jop   # Serial boot AtariSupervisor.jop (500 kbaud)

# Dual-PLL JOP (80 MHz JOP + 56.67 MHz Atari, recommended)
make generate-dualpll
make build-dualpll
make program-dualpll
make download-dualpll  # Serial boot AtariSupervisor.jop (2 Mbaud)
make run-dualpll       # program + download + monitor in one step
```

JOP software build chain (rebuild after Java source changes).
Const.java generation and app compilation are driven entirely from
atari800-spinalhdl — jop-spinalhdl is a pure reusable submodule:

```sh
cd boards/ep4cgx150
make const-java   # Generate Const.java from Atari JOP config (via sbt)
make asm          # Build microcode (serial boot variant)
make jop-app      # Build AtariSupervisor.jop (from java/apps/AtariSupervisor/)
```

Cartridge ROM is set via `cartridge_rom` in the top-level Scala file
(default: `roms/Star Raiders.rom`). Place `.rom` files in the `roms/` directory.

### Full Quartus build (Cyclone 10 LP)

```sh
make all      # generate + map + fit + sta + asm
make quartus  # assumes generated/ already populated
```

### ECP5 synthesis (Colorlight i5 — LFE5U-25F)

```sh
cd boards/i5-7v0
make synth    # yosys synthesis + utilisation report
make pnr      # nextpnr place-and-route + timing
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
  POKEY (sound/keyboard/timers), PIA (I/O ports), MMU, cartridge logic
- **JOP soft-core**: Java bytecode processor with SD SPI, VGA text overlay,
  UART debug, and external I/O bus to control the Atari core
- **SDRAM arbiter**: Atari (priority) + JOP share W9825G6KH via SdramArbiter
- **Video**: Scandoubler (15kHz→31kHz) with JOP OSD overlay, VGA + HDMI output
- **Audio**: POKEY + Covox through sigma-delta PWM DAC

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

### Cyclone IV GX — EP4CGX150DF27I7 (QMTECH EP4CGX150 + DB_FPGA, **hardware verified**)

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

### Cyclone 10 LP — 10CL025YU256C8G (custom board / AC608)

Synthesised with Quartus Prime 25.1 Lite Edition.

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

## License

See individual source files. The Atari 800 core is derived from
[gyurco/Atari800XL](https://github.com/gyurco/Atari800XL) (GPL-2.0).
The JOP soft-core is from [peteryates1/jop-spinalhdl](https://github.com/peteryates1/jop-spinalhdl).
