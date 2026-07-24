# Archimedes core → RP2040 supervisor: HPS-shim spec

*Planning artifact for porting `Archie_MiSTer` (Stephen Leary's Acorn Archimedes core)
to this project's Xilinx + RP2040-supervisor platform. It defines the message contract
the RP2040 firmware must implement to stand in for the MiSTer **HPS** (the DE10-Nano's
hard ARM/Linux). Nothing here is built yet — this is the boundary between "reuse" and
"rebuild" for the port.*

Upstream reference clones (read-only): `/srv/git/Archie_MiSTer` (base this port on it)
and `/srv/git/mist-archimedes` (MiST original — reference for the SPI-io-controller side,
which is closer to our RP2040 than HPS).

## Background: what an HPS is, and why a shim is needed

MiSTer runs on a **Cyclone V SoC** whose supervisor is a hard **ARM Cortex-A9 running
Linux (the "HPS")**, fused to the FPGA fabric by wide AXI bridges. Our supervisor is an
**RP2040 (Cortex-M0+) on a SPI wire** — a *MiST-class* io-controller, not an HPS. So a
MiSTer core, written against the HPS/`emu` contract, needs a **shim**: the RP2040 (for
control/file/disk/RTC) plus our own scaler + SDRAM controller must present themselves to
the core as if they were a minimal HPS.

The Archie core splits its supervisor needs into two channels:
- **`hps_io`** — the *generic* MiSTer interface (controls, config, file load, disk sectors).
- **`hps_ext`** — a *per-core* extension bus for Archie-specific transactions
  (bidirectional keyboard/mouse, IDE registers, CMOS).

Video, audio and main RAM do **not** cross the supervisor link (see §D).

## Transport

Reuse the existing RP2040↔FPGA SPI link (the one already serving the Atari core). Each
message is an **opcode byte + payload**. On the FPGA side, an `hps_io`-equivalent block
plus a small `hps_ext` framer de/frame these onto the core's expected signals.

---

## A. Standard transactions (`hps_io` — generic; build once, reuse for every MiSTer core)

Core instantiation parameters observed: `WIDE=1` (16-bit ioctl/sd buffers), `VDNUM=2`
(two virtual disk drives = the two ADF floppies).

| Msg | Dir | Trigger / payload | FPGA-side signals | RP2040 owner | Status |
|---|---|---|---|---|---|
| `STATUS` | RP→FPGA | 32-bit config word on OSD change | `status[31:0]` (Archie uses `[0]` reset, `[3:2]`, `[4]`, `[5]`, `[7:6]`, `[9:8]`) | OSD/menu | **reuse** (we already emit config bits) |
| `BUTTONS` | RP→FPGA | 2-bit | `buttons[1:0]` | menu | reuse |
| `JOY` | RP→FPGA | per-pad byte on change | `joystick_0/1[…]` | HID mapper | reuse |
| `IOCTL_DL` | RP→FPGA | `index`, then `addr`+`data` stream; honor `wait` | `ioctl_index/download/addr/dout[15:0]/wr/wait` | SD→RAM loader | **reuse** (= our ROM boot loader; `index` tags `riscos.rom` vs other files) |
| `IOCTL_UL` | FPGA→RP | readback stream | `ioctl_din` | loader | new-standard |
| `DISK_MOUNT` | RP→FPGA | `slot`, `size`, `readonly` when an image is mounted | `img_mounted[1:0]/img_size/img_readonly` | disk manager | **new-standard** |
| `SECTOR_RD/WR` | both | core asserts `sd_rd`/`sd_wr` + `sd_lba`; RP streams a **512-byte** sector via the buffer; ack | `sd_lba/sd_rd/sd_wr/sd_ack`, `sd_buff_addr/dout/din[15:0]/wr` | **sector server** | **new-standard (generic, reusable)** |

> The **sector server** is the single most reusable thing to build: every MiSTer core
> uses this "mount an image, serve 512-byte sectors" protocol. Once the RP2040 speaks it,
> it's reusable far beyond the Archimedes.
>
> `new_vmode` and `gamma_bus` from `hps_io` are **discarded** — they drive the MiSTer
> `ascal` scaler, which we replace with our own.

---

## B. Extension transactions (`hps_ext` — Archie-specific; command-byte protocol on `EXT_BUS`)

| Msg | Cmd | Dir | Payload / semantics | FPGA-side signals | RP2040 owner | Status |
|---|---|---|---|---|---|---|
| `KBD_POLL` | `0x04` | RP↔FPGA | status: is a host→kbd byte available? | `kbd_out_data_available` | kbd driver | **new-Archie** |
| `KBD_XFER` | `0x05` | both | **bidirectional** byte exchange with the Archimedes serial keyboard handshake (host acks scancodes, requests mouse-data mode, drives LEDs) | `kbd_out_data/strobe` (kbd→host), `kbd_in_data/strobe` (host→kbd) | kbd + mouse driver | **new-Archie** (real protocol depth; **mouse rides this channel**) |
| `IDE_REG` | `0x61/0x62` | both | register-level IDE access | `ide_addr[4:0]/rd/wr/dout[15:0]/din[15:0]/req[5:0]` | HDD (`.hdf`) backer | **new-Archie** |
| `CMOS` | — | both | persist CMOS to `cmos.dat`; provide a clock | `cmos_cnt` (+ RTC/time) | SD + RTC | reuse-ish (we own SD + a time source) |

> The hard disk is **not** on the standard sector path — it is register-level IDE relayed
> through `hps_ext`. The floppies **are** on the standard sector path (§A).

---

## C. Direction / ownership summary

- **RP2040 = initiator** for `STATUS`, `BUTTONS`, `JOY`, `IOCTL_DL`, `DISK_MOUNT`, and
  host→kbd bytes.
- **Core = initiator** for `SECTOR_RD/WR`, `IOCTL_UL`, kbd→host bytes, and `IDE_REG`
  (the core reads/writes its own disk).
- The `hps_ext` responder is a **small SpinalHDL state machine** that de/frames command
  bytes onto `EXT_BUS`; the heavy logic (disk files, HID) lives in RP2040 firmware.

## D. Explicitly *not* on the supervisor link (retarget to our own IP)

| Concern | Core drives | We provide |
|---|---|---|
| Video | `VGA_R/G/B/HS/VS/DE`, `CLK_VIDEO/CE_PIXEL` | our framebuffer **scaler** (replaces `ascal`); drop `new_vmode`/`gamma_bus` |
| Audio | `AUDIO_L/R` | our audio path |
| Main RAM (4 MiB) | core's own `sdram.v` | retarget to **`SdramStatemachine`** / W9825 SDR (no DDR3 needed) |

---

## Rebuild scope, named

Everything is either **reuse** (already done for the Atari), **discard** (MiSTer `sys/`),
or one of three bounded new pieces:

1. **Sector-server** — generic, build once, reusable for all future MiSTer ports.
2. **Bidirectional keyboard/mouse** — the one input task with real protocol depth.
3. **IDE-over-`hps_ext`** — bespoke to this core.

The *big* code — Amber CPU (~7.7k lines), MEMC/VIDC/IOC, and the SDRAM retarget — is
untouched by this supervisor question. The genuine port risks remain elsewhere:
blackboxing Amber, rebuilding the VIDC dynamic pixel-clock (Altera ALTPLL reconfig →
Xilinx MMCM/DRP), and the upstream core's immaturity ("expect issues").
