# SpinalHDL code structure

The SpinalHDL sources (sbt module `atari`, at `atari/`) are organised into packages under
a machine-neutral root, **`retro`**, so that reusable IP is cleanly separated from
machine-specific cores ahead of adding a second machine (Archimedes).

## Packages

```
atari/src/main/scala/retro/
  common/            reusable across machines — depends on nothing above it
    util/            Synchronizer, SharedEnable, counters, delay lines, RegFile,
                     FileRom, HasBusIo, BlackBoxes, Plls, …
    video/           TMDS/DVI output: TmdsEncoder, TmdsGearboxX2, DvidOut,
                     Ecp5DvidOut*, PllHdmi, DviEncoder
    scaler/          framebuffer scaler + OSD overlay: VideoFbRead/Write,
                     Scandoubler, Hdmi720Scaler, FbPipeline, TextOverlay*, Font8x16
    sdram/           SdramStatemachine, SdramArbiter3, SdramBist, MockSdram
  link/              FPGA side of the RP2040 supervisor link: SioBridge, sims.
                     (Sector-server, ioctl, hps-ext framer land here during the
                     Archimedes port.)
  machines/
    atari/           the Atari 800: Antic, Gtia, Pokey*, Cpu(65xx), Pia, Cart,
                     Freezer, Atari800Core*, Atari*SimTb, RpAtariKeyboard, AtariHidMap
    archimedes/      (future) Amber (blackbox), Memc, Vidc, Ioc, Fdc, Ide, ArchieHpsExt
  boards/            board tops + their `*Sv` generators: Atari800WukongTop,
                     Atari800Rp2040HdmiLgTop, Atari800Ecp5*Top, SdramTestTop, Hdmi*Bars
```

Tests mirror this under `atari/src/test/scala/retro/`.

## The dependency rule

```
boards ──▶ machines/* ──▶ common, link
machines/* ──▶ common, link
link ──▶ common
common ──▶ (nothing above it)      # common must never import a machine
```

`common` and `link` must not import `retro.machines.*`. That is the coupling that would
otherwise quietly make the "reusable" scaler un-reusable. Today this rule is enforced by
convention; promoting `common` (+`link`) to its own sbt sub-project later turns a
violation into a **compile error** — which is the reason to do that split, when the
boundary has proven itself against the second machine.

## Build / run

The sbt module id is still `atari`; only the Scala namespace changed. Entry points are now
fully-qualified under `retro`:

```
sbt "atari/runMain retro.boards.Atari800WukongSv"          # Wukong (Artix-7, 1080p)
sbt "atari/runMain retro.boards.Atari800Rp2040HdmiLgSv"    # 10CL025 + RP2040-STAMP (720p)
sbt "atari/runMain retro.machines.atari.Atari800CoreSimTb" # a simulation
```

## Root name

`retro` was chosen for brevity. It is slightly noisy to grep (a common English word); if a
unique token is wanted later, a rename to `retrofpga` is a one-line change across the
`package`/`import` declarations.
