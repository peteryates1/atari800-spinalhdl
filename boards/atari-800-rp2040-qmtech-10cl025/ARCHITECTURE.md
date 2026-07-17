# Atari 800 on QMTech 10CL025 + RP2040 — Architecture & Bring-up Notes

*Status as of 2026-07-05. Board: ATARI-800-RP2040-STAMP-HDMI-LG V1.0
(QMTech Cyclone 10 LP 10CL025 core board, RP2040-STAMP supervisor, HDMI out).*

> **⚠ Superseded on key points — see [../../STATUS.md](../../STATUS.md) for current
> state.** This doc is the early SDRAM-based bring-up (kept for its debugging
> history). Since then: the **Atari runs entirely from BRAM** (RAM+OS+cart);
> **SDRAM carries only the framebuffer** (the ANTIC-jitter fix — `internal_rom=3`,
> `internal_ram=49152`, not the `0/0` below). Video is **720p** (74.25/371.25 MHz),
> not the 480p clock plan below. The RP2040 now **configures the FPGA from SD at
> power-on** and hosts an **on-screen Alt-F12 menu**. The 6502 runs at
> **cycle-accurate 1.79 MHz** (`THROTTLE_COUNT_6502=0`). The SPI loader/protocol,
> refresh-timing, and pixel-sampling notes below are still accurate.

## The vision

**Nothing proprietary in the bitstream.** The `.sof` contains only logic:
the Atari core, the video pipeline, the SDRAM controller, and a supervisor
bridge. At power-up the RP2040 reads OS ROMs, cartridges, and disk images
from a FAT SD card and loads them into SDRAM over SPI; the Atari boots and
runs entirely from SDRAM. This works today: the machine on the bench boots
the 800 OS to a solid memo pad, from SD, with a USB keyboard, with every
load content-verified.

The same architecture is sized for what comes next: larger cores
(Archimedes/Amiga class), a JOP coprocessor sharing the SDRAM, and a
supervisor overlay UI for selecting ROMs/carts/disks.

## System architecture

```
                 50 MHz osc
                     │
              ┌──────┴──────┐
              │  atari_pll  │ c0 = 57.69 MHz  "sys"    (core, arbiter, clients)
              │             │ c1 = 115.38 MHz "ctrl"   (SDRAM controller fast side)
              │             │ c2 = 115.38 MHz @ -2400ps → SDRAM chip clock pin
              └─────────────┘ (hdmiPll separately: 25 MHz pixel / 125 MHz TMDS)

  RP2040 supervisor                        FPGA (all SpinalHDL → SystemVerilog)
 ┌───────────────────┐   SPI 1 MHz   ┌──────────────────────────────────────────┐
 │ TinyUSB host:     │◄─────────────►│ RpAtariKeyboard (SPI bridge)             │
 │  USB HID keyboard │  K C W Z R V  │  · HID → Atari matrix (AtariHidMap)      │
 │ FatFs on SD card  │               │  · control bits: reset/start/…/HALT      │
 │ CDC console       │               │  · loader: (addr,data) FIFO ×512 ──► D   │
 │ BIST/meter client │               │  · V: content checksum readback   ◄── D  │
 └───────────────────┘               │                                          │
                                     │ Atari800 core (MiST port, 800 mode,      │
                                     │  internal_rom=0, internal_ram=0)         │
                                     │   6502+ANTIC+GTIA+POKEY ──────────► A    │
                                     │   AddressDecoder maps 64K bus to SDRAM   │
                                     │                                          │
                                     │ VideoFbWrite (capture, quad-pack,        │
                                     │   8-quad wide batching, clear sweep) ► B │
                                     │ VideoFbRead2 (4-bank ring line cache,    │
                                     │   256-bit fetches, 720p DDA scale)  ◄─ C │
                                     │        │ pixel domain                    │
                                     │        ▼                                 │
                                     │ GtiaPalette → disp timing → dvi_encoder  │──► HDMI 720p50
                                     │                                          │
                                     │ SdramArbiter3:  A > C > B > D            │
                                     │        │ (A = combinational passthrough) │
                                     │        ▼                                 │
                                     │ SdramStatemachine (dual clock domain)    │
                                     └────────────────────┬─────────────────────┘
                                                          ▼
                                          W9825G6KH-6 SDRAM · 32 MB · 16-bit
                                          13 row / 9 col / 4 bank · CL3 · BL2
```

Only two Verilog blackboxes exist (the PLLs and `dvi_encoder`); everything
else is generated from SpinalHDL, per the all-SpinalHDL principle.

### SDRAM memory map (byte addresses)

| Region | Contents |
|---|---|
| `0x000000-0x00FFFF` | Atari 64 KB RAM (CPU/ANTIC, byte access) |
| `0x100000-0x123FFF` | Framebuffer: 384×288, 1 byte/px, 512-byte stride |
| `0x13C000` | BASIC ROM window *(diagnostic location)* |
| `0x140000-0x143FFF` | OS ROM window, 16 KB *(diagnostic location — to be restored to `0x704000`, BASIC to `0x700000`, cart region `0x500000`)* |

ROM windows reject 6502 writes in the decoder. The supervisor loads
`atarios2.rom` at window+`0x1800` (maps to `$D800`) and `atariosb.rom` at
window+`0x2000` (maps to `$E000-$FFFF`).

### The SDRAM controller and the wide path

The controller is a dual-clock-domain port of the MiST `sdram_statemachine`
(client side at 57.69 MHz, SDRAM side at 115.38 MHz, toggle handshake with
snapshot registers across the domains). Bring-up added, beyond bug fixes:

**256-bit WIDE access.** The key architectural insight of this phase:
the chip provides 230 MB/s but the system was dying at a demand of
~15 MB/s, because bandwidth is not the resource — **transaction slots
are**. Every single-beat access costs ~200 ns of ceremony (ACTIVATE, CAS
latency, precharge, plus the cross-domain handshake round trip) whether it
moves 1 byte or 4. The Atari's byte accesses and the framebuffer's
longwords together demanded more slots than the ~5M/s available.

`WIDE_ACCESS` moves 8 longwords (32 bytes, aligned) as *one* transaction
through the *existing* toggle handshake: one ACTIVATE, eight back-to-back
BL2 CAS bursts in the open row, auto-precharge on the last, the 256-bit
payload crossing the clock domains as a single snapshot. A framebuffer
line is exactly 12 wide transactions instead of 96 singles; the two video
ports dropped from ~2.7M to ~0.35M transactions/s and the starvation
meters went from saturated-in-2-seconds to zero.

| Access | Cost | Payload | Effective |
|---|---|---|---|
| single-beat longword | ~200 ns | 4 B | ~20 MB/s per stream |
| wide | ~240 ns | 32 B | ~130 MB/s per stream |

### The supervisor link

SPI mode 0, 1 MHz, RP2040 SPI0 ↔ dedicated FPGA pins. One command byte per
CS frame:

| Cmd | Function |
|---|---|
| `K` | 8-byte USB HID boot report → Atari keyboard matrix (atomic commit) |
| `C` | control bits: reset (stretched pulse), start/select/option, **bit 4 = 6502 HALT**, bit 5 = supDisplay (overlay), **bit 6 = 6502 turbo (unthrottled)** |
| `W` | SDRAM load: 3-byte address + data stream; every byte queued as (addr, data) into a 512-deep FIFO, drained through arbiter port D as byte writes |
| `Z` | zero the stream counters |
| `V` | FPGA-side content checksum: reads N bytes back through the loader port (the CPU's own access mode) and reports the 16-bit sum — immune to SPI pacing, the ground truth for "did the bytes land" |
| `R` | (legacy) direct byte readback on MISO; superseded by V for verification |

MISO status stream: magic, frame count, stream count/sum, verify sum/busy,
FIFO-overflow sticky, drain-write counter, fb late/drop meter counts.

Boot flow (`B` on the console): HALT the 6502 → for each ROM: stream from
SD (chunked W frames), compare stream checksums, then **content-verify
with V and reload up to 3× on mismatch** → release HALT via reset. The
stream checksum proves the SPI link; only the content checksum proves the
memory. That distinction cost two days to learn.

## Bring-up instruments (the real heroes)

Everything that cracked this phase was a purpose-built instrument, all of
which remain in the tree for future work:

- **Full-range SDRAM BIST** (`SdramBistEngine` + `BistSpiReporter`,
  `sdram_test`/`sdram_test_115`/`sdram_test_arb` Quartus projects):
  address-bit walk (catches aliasing with a named address), two full-range
  sweeps with address-unique data, a refresh-retention pass. Variants:
  direct-controller at 100 MHz and at the main clock tree, through the
  arbiter, byte mode (the loader's mix), wide mode, with continuous
  refresh, with ANTIC-cadence port-A traffic. Reports state/first-failure
  over SPI; supervisor `M` runs it, ~30 s for 16-32 MB.
- **Content checksum (`V`) and byte peeks (`y`)**: independent readback
  through the CPU's own port/access mode.
- **Drain accounting**: pushes vs completed writes vs content sum — a
  three-way audit that localizes any loss.
- **fb starvation meters**: late-line and dropped-quad event counters,
  readable live (`g`).
- **Simulation suite** (~27 ScalaTests): toggle-faithful `MockSdram` (now
  wide-capable), full-topology boot sims (core + arbiter + mock), scaler
  specs under latency/contention/reset-skew, loader specs under starvation,
  and — hard-won — a **deferred-sampling write mock** that samples
  flags/address/data cycles *after* the request pulse, exactly as the real
  arbiter+controller do.

## Problems encountered and fixed (this phase)

In dependency order — each hid behind the previous:

1. **The Stage-0 SDRAM test covered only 32 KB.** Weeks of "SDRAM works"
   confidence rested on the first 4 of 8192 rows. *Lesson: qualification
   tests must cover the full address space; power-of-two walks catch
   address faults with a named bit.*
2. **Column-width geometry.** The QMTech Test04 reference says 10-bit
   columns; the actual chip (W9825G6KH-6, per its datasheet and the
   schematic's "256 Mbits") has 9. The BIST walk caught the 10-bit config
   in one second: writing `0x400` clobbered `0x0`. *Vendor reference code
   describes some board, not necessarily yours.*
3. **Refresh 2.3× too slow.** The MiST-era constant (2048 cycles) gave
   17.7-20.5 µs between refreshes against the chip's required 7.8 µs.
   Rows that were re-accessed (RAM, framebuffer) survived on activation
   refresh; rows written once and read later — every ROM load — decayed.
   Now 750 cycles. This single bug explains most of the "black screen"
   phase, including why an OS placed *inside* the framebuffer booted (the
   display's reads refreshed it).
4. **Mode-register reserved bit violation.** `addr_next` defaults to
   all-ones; the MRS state only cleared bits 10-11, so `ROW_WIDTH = 13`
   silently drove A12 high during mode-register-set (datasheet: reserved
   bits must be zero). Now cleared through `ROW_WIDTH-1`.
5. **Power-up pause below spec** (71 µs vs required 200 µs). Now 284 µs.
6. **Loads never halted the machine.** The 6502 crash-looped through every
   load (and ANTIC DMA *never* stops — `HALT` gates only the CPU). The
   supervisor now holds HALT for the whole load+verify window.
7. **Loader single-buffer clobber.** The SPI loader held one pending write;
   port D is lowest priority, and an arbitration stall longer than one SPI
   byte-time silently dropped bytes. Stream checksums cannot see this —
   they count what arrived, not what landed. Fixed with the (addr, data)
   FIFO; proven by drain accounting and content verification; made
   self-healing with verify-and-reload.
8. **Transaction-slot exhaustion.** With ROM in SDRAM, ANTIC's character
   set fetches (~40/scanline) joined an already-full schedule; both fb
   meters saturated within 2 s and the memo pad drowned in dynamic
   corruption. Fixed with the wide path (see above).
9. **Auto-precharge on every CAS of a wide burst.** The all-ones
   `addr_next` default meant every CAS in the new wide states carried AP —
   a JEDEC-illegal interruption of an auto-precharge burst. The chip
   tolerated it in isolated testbeds and misbehaved in the real traffic
   mix: undefined behavior in its natural habitat. AP is now explicit on
   the final CAS only.
10. **Pulse-shaped access qualifier.** `VideoFbWrite` asserted `wrWide`
    only during its one-cycle request pulse; the arbiter serves port B
    cycles later, and the controller snapshots the flags at *serve* time.
    Result: single-beat writes of word 0 only — the memo pad behind
    vertical stripes of stale BIST patterns, diagnosed byte-exactly with
    `y` dumps. Fixed by holding the qualifier for the whole transaction;
    the deferred-sampling spec mock now makes this class unpassable.

Diagnostic dead-ends worth remembering: a probe that latched port A's
`dataOut` on `complete` read stale bytes for hours (port A's complete
idles **high**); the `R` readback lied under load (no flow control);
self-consistency traps abound — any fault symmetric between the write and
read path (aliasing, geometry) passes its own round-trip test, which is
why independent readback channels and address-unique test data matter.

## Current state

**Working, verified**: SD → SDRAM OS boot (content-checksummed, auto-retry,
11+ consecutive clean), solid stable memo pad over HDMI 720p, USB keyboard
end-to-end, SD browsing, full BIST suite, fb meters at zero, audio,
console switches.

**Known items**:
- Borders/centering uneven (next session).
- OS window still at the diagnostic `0x140000`; restore the `0x704000`
  map, then cartridge-from-SDRAM (`0x500000`,
  `emulated_cartridge_select`), then disk images.
- Tightest timing path ~0.3-0.5 ns setup (256-bit datapath at
  115.38 MHz) — met, but first candidate for pipelining if a fit misses.
- Arbiter client ports are 24-bit (16 MB reach, bank 0/1 only) — fine
  below 16 MB, widen before mapping anything higher.
- Pinned video polish: 50↔60 Hz tear beat, boot-after-JTAG quirk, reset
  transient, LA debug tap removal, num-lock LED.

**Roadmap**: supervisor overlay/menu UI (it can already take the screen),
disk images via SIO, RP2040→FPGA configuration from SD (the last step to
a fully self-contained SD-boot board), JOP integration for the 10CL025.
