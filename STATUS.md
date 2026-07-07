# Project Status — 2026-07-07

Handoff snapshot. Board: **atari-800-rp2040-qmtech-10cl025** (QMTech Cyclone 10 LP
10CL025 + RP2040-STAMP + HDMI). Everything below is committed and hardware-verified.

## TL;DR — current state

The Atari 800 runs **entirely from BRAM** (RAM + OS + cartridge). **SDRAM carries
only the framebuffer.** Star Raiders boots and plays over 720p HDMI. The two
long-standing display bugs — the photon/sprite **smear** and the whole-picture
**jitter** — are gone *by construction*. **Nothing proprietary is in the `.sof`**:
the OS and cart stream from the SD card into blank BRAM at boot.

Resource use: logic **46%**, BRAM **65/66 M9K**, timing clean (slack ~1.6 ns).

## Architecture

**Memory split**
- **BRAM (inside the FPGA):** Atari 48 KB RAM (0000–BFFF), 800 OS ROM
  (D800–FFFF), and the cartridge. Cart shares the top-of-RAM BRAM (A000–BFFF) —
  same address space as RAM — and is read-only while the cart is active.
- **SDRAM (external):** the 720p framebuffer only (triple-buffered), written by
  the video capture, read by the scaler. No CPU/ANTIC/loader traffic.

**Why:** ANTIC's real-time display DMA can't tolerate SDRAM contention/latency.
Sharing SDRAM between the Atari and the framebuffer starved ANTIC → jitter, and
mid-line refresh disrupted sprite DMA → smear. Putting the Atari fully in BRAM
removes the shared resource entirely. See `memory/project_antic_ram_in_bram.md`.

**Load path (supervisor → BRAM):**
- `InternalRomRam` `internal_rom=5`: 800 OS as blank, writable BRAM.
- `Atari800CoreSimpleSdram` LOAD port: muxes supervisor address/data/we into the
  RAM/ROM BRAM write ports while the Atari is halted (post-decoder).
- Loader `ldDest` (`'B'`=0x42 SPI cmd): 0=SDRAM, 1=BRAM-OS, 2=BRAM-RAM.
- Cart region in `AddressDecoder` reads `io.ramData` (RAM BRAM), swallows writes.

## Boot process (currently MANUAL — see next steps)

1. Power-on: FPGA configures (blank BRAM); SDRAM does power-up init (~285 µs);
   Atari held halted until `sdramReady`. RP2040 brings up USB (CDC console +
   PIO-USB keyboard), SPI, SD; enters its main loop. **No auto-boot.**
2. Console `'B'` (via `bootcart.py`, keypress, etc.):
   - mount SD; halt 6502 (`0x10`)
   - `ldDest=1`, load `atarios2.rom`→rom 0x1800, `atariosb.rom`→rom 0x2000
   - `ldDest=2`, load cart→RAM 0xA000; set cart mode 0x01, offset, phase
   - reset Atari (`0x11`→`0x00`, a stretched ~1.1 ms pulse)
3. Atari boots from BRAM: OS vectors → RD5 cart detect → Star Raiders.
4. Capture → SDRAM framebuffer → scaler → 720p HDMI.

Control byte: bit0 reset, bit1 start, bit2 select, bit3 option, bit4 halt.

## Recent commit trail

- `9d3192e` Step 3: sever Atari↔SDRAM path; SDRAM framebuffer-only
- `222c79f` Step 2: cart into shared top-of-RAM BRAM (read-only)
- `853fbcf` Step 1: supervisor loads 800 OS into blank BRAM from SD
- `5f407b2` Fix display jitter: Atari 48 KB RAM in BRAM (the root-cause fix)
- `218c8f4` Cart-from-SDRAM, triple buffer, pixel-clock-locked capture

## Rebuild / flash / boot (quick reference)

```bash
# Regenerate SystemVerilog from SpinalHDL (after Scala edits):
sbt "atari/runMain atari800.Atari800Rp2040HdmiLgSv"
# Build + program the FPGA:
cd boards/atari-800-rp2040-qmtech-10cl025/atari_starraiders && make build && make program
# Build + flash the supervisor firmware (RP2040, via SWD debug probe):
cd firmware/supervisor/build && make -j4
openocd -f interface/cmsis-dap.cfg -c "adapter speed 5000; transport select swd" \
        -f target/rp2040.cfg -c "program supervisor.elf verify reset exit"
# Trigger the boot (sends console 'B'): bootcart.py over /dev/ttyACM1
```

SD card layout: `/os/atarios2.rom` (2K), `/os/atariosb.rom` (8K),
`/cartridge/Star Raiders.rom` (8K).

## Remaining / next steps (nothing blocking)

1. **Auto-boot** at power-on (run the `'B'` sequence automatically), ideally
   reading a `/config` file for which OS/cart to load. Biggest usability gap.
2. **DLI residual**: the star-field/readout boundary shivers slightly — it's a
   DLI (CPU code) whose timing still varies; a small CPU instruction cache would
   remove it. Minor.
3. **Arbiter cleanup**: rewrite the 4-port arbiter as a true 2-port (dead ports
   A/D are Quartus-pruned today, ~200 LE).
4. **Banked carts**: the cart read uses flat `ramData` (fine for 8K/16K
   non-banked); bank-switched carts need `emuCartAddress`.
5. **Refresh simplification**: the elaborate ANTIC-gated/VBLANK refresh built
   during the jitter hunt is now redundant (PMG buffer is in BRAM) — could
   revert to plain free-running refresh for a cleaner baseline.
