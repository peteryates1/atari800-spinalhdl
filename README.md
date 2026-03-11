# atari800-spinalhdl

Atari 800 FPGA core written in SpinalHDL, integrated with the
[JOP](https://github.com/peteryates1/jop-spinalhdl) Java soft-core processor
for SD card, USB, OSD, and configuration management.

**Target**: Intel Cyclone 10 LP (10CL025YU256C8G) on a custom AC608-based board
with 32MB SDR SDRAM (W9825G6KH).

## Origins

This is a ground-up SpinalHDL rewrite of the Atari 800 core from
[gyurco/Atari800XL](https://github.com/gyurco/Atari800XL), which targets
multiple FPGA boards with VHDL. The chip implementations (6502 CPU, ANTIC,
GTIA, POKEY, PIA) were rewritten in SpinalHDL based on the original VHDL
and Atari hardware documentation.

## Project Structure

```
build.sbt               SBT multi-project build
atari/                   Atari 800 core (Scala 2.12 / SpinalHDL 1.10.2a)
  src/main/scala/atari800/
jop-bridge/              JOP generation wrapper (Scala 2.13 / SpinalHDL 1.12.2)
  src/main/scala/jop/system/
jop-spinalhdl/           JOP soft-core (git submodule)
quartus/                 Intel Quartus project files
generated/               SpinalHDL output (.sv + .bin)
unused_scala/            Archived/inactive modules
Makefile                 Build orchestration
```

The Atari core and JOP use different SpinalHDL versions, so they are separate
SBT subprojects that generate SystemVerilog independently. The `JopCoreForAtari`
hardware wrapper lives in jop-spinalhdl; `jop-bridge/` contains only a thin
generator that controls the output directory.

## Building

### Prerequisites

- JDK 8+
- SBT 1.9+
- Intel Quartus Prime 18.1+ (Lite Edition)

### Generate SystemVerilog

```sh
# Generate JOP core
sbt "jopBridge/runMain jop.system.GenerateJopForAtari"

# Generate Atari top-level
sbt "atari/runMain atari800.Atari800JopTopSv"
```

### Full Quartus build

```sh
make all      # generate + map + fit + sta + asm
make quartus  # assumes generated/ already populated
```

### Clone with submodule

```sh
git clone --recurse-submodules <url>
# or after clone:
git submodule update --init
```

## Architecture

- **Atari 800 core**: 6502 CPU, ANTIC (DMA/display), GTIA (graphics/color),
  POKEY (sound/keyboard/timers), PIA (I/O ports), MMU, cartridge logic
- **JOP soft-core**: Java bytecode processor with SD SPI, VGA text overlay,
  UART debug, and external I/O bus to control the Atari core
- **SDRAM arbiter**: Atari (priority) + JOP share W9825G6KH via SdramArbiter
- **Video**: Scandoubler (15kHz→31kHz) with JOP OSD overlay, VGA + HDMI output
- **Audio**: Stereo POKEY + Covox through sigma-delta PWM DAC

## Resource Utilization (10CL025YU256C8G)

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
