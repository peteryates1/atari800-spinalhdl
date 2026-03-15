# atari800-spinalhdl

Atari 800 FPGA core written in SpinalHDL, integrated with the
[JOP](https://github.com/peteryates1/jop-spinalhdl) Java soft-core processor
for SD card, USB, OSD, and configuration management.

**Target**: Intel Cyclone 10 LP (10CL025YU256C8G) on a custom AC608-based board
with 32MB SDR SDRAM (W9825G6KH).

## Status

The Atari 800 core boots and runs correctly in simulation:

- **Memo pad** (no cartridge) — displays "ATARI COMPUTER - MEMO PAD"
- **Atari BASIC** (built-in 8K ROM) — boots to READY prompt
- **Star Raiders** (8K cartridge ROM loaded from file) — boots to title screen

All ANTIC display modes, GTIA colour rendering (including highres mode 2),
DMA pipeline, NMI/IRQ handling, and the 6502 CPU are verified working.
Frame capture produces correct PAL-palette colour output.

**Not yet tested on hardware** — FPGA synthesis and board bring-up are pending.

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
    InternalRomRam.scala      Embedded ROM (OS, BASIC) + internal RAM
    Scandoubler.scala         15kHz→31kHz VGA scandoubler
    GtiaPalette.scala         Full PAL/NTSC colour palette (256 entries)
    Atari800CoreSim.scala     Simulation top-level wrapper
    Atari800CoreSimTb.scala   Simulation testbench with frame capture
    Atari800JopTop.scala      FPGA top-level (Atari + JOP + SDRAM arbiter)
    Os8.scala, Os2.scala      Atari 800 OS ROMs (8K + 2K math pack)
    Basic.scala               Atari BASIC 8K ROM
jop-spinalhdl/           JOP soft-core (git submodule)
quartus/                 Intel Quartus project files
generated/               SpinalHDL output (.sv + .bin)
unused_scala/            Archived/inactive modules (18 files)
Makefile                 Build orchestration
```

## Building

### Prerequisites

- JDK 11+
- SBT 1.9+
- Intel Quartus Prime 18.1+ (Lite Edition) — for FPGA build only

### Clone with submodule

```sh
git clone --recurse-submodules <url>
# or after clone:
git submodule update --init
```

### Run simulation

```sh
# Boot to BASIC (default — built-in BASIC ROM)
sbt "atari/runMain atari800.Atari800CoreSimTb"

# Boot with a cartridge ROM (e.g. Star Raiders)
# Edit Atari800CoreSimTb.scala: cartridge_rom = "Star Raiders.rom"
# Place the .rom file in the project root directory
sbt "atari/runMain atari800.Atari800CoreSimTb"
```

Frame captures are written as PPM files to `sim_workspace/Atari800_boot_test/`.
Convert to PNG with ImageMagick: `convert frame.ppm frame.png`

### Generate SystemVerilog

```sh
sbt "atari/runMain atari800.Atari800JopTopSv"
```

### Full Quartus build

```sh
make all      # generate + map + fit + sta + asm
make quartus  # assumes generated/ already populated
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
- `internal_rom = 3` — Atari 800 OS (Os8 + Os2)
- `internal_ram = 16384` — 16K internal RAM (48K total with SDRAM model)
- `cartridge_rom` — path to 8K ROM file (empty = built-in BASIC)

## Architecture

- **Atari 800 core**: 6502 CPU, ANTIC (DMA/display), GTIA (graphics/colour),
  POKEY (sound/keyboard/timers), PIA (I/O ports), MMU, cartridge logic
- **JOP soft-core**: Java bytecode processor with SD SPI, VGA text overlay,
  UART debug, and external I/O bus to control the Atari core
- **SDRAM arbiter**: Atari (priority) + JOP share W9825G6KH via SdramArbiter
- **Video**: Scandoubler (15kHz→31kHz) with JOP OSD overlay, VGA + HDMI output
- **Audio**: Stereo POKEY + Covox through sigma-delta PWM DAC

## Bugs Fixed

### GTIA highres colour (mode 2 text invisible)

The VHDL-to-SpinalHDL conversion had an off-by-one bit index in the highres
luminance replacement. VHDL declares colour registers as `std_logic_vector(7
downto 1)` (indices 1-7), while SpinalHDL uses 0-based indexing (0-6). The
VHDL index `(3 downto 1)` was copied directly instead of adjusting to
`(2 downto 0)`, causing the foreground colour to equal the background in
mode 2 (ANTIC's 40-column text mode). Fix: `Gtia.scala` lines 611-612.

## Resource Utilisation (10CL025YU256C8G)

| Resource | Used | Available | % |
|---|---|---|---|
| Logic Elements | 19,183 | 24,624 | 78% |
| Memory bits | 419,406 | 608,256 | 69% |
| DSP 9-bit | 10 | 132 | 8% |
| PLLs | 0 (stub) | 4 | 0% |

Timing: all paths meet 56.67 MHz with positive slack.

## License

See individual source files. The Atari 800 core is derived from
[gyurco/Atari800XL](https://github.com/gyurco/Atari800XL) (GPL-2.0).
The JOP soft-core is from [peteryates1/jop-spinalhdl](https://github.com/peteryates1/jop-spinalhdl).
