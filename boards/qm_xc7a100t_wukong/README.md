# QMTech Wukong (XC7A100T) — board notes

Off-the-shelf **QMTech Wukong V3** dev board (`xc7a100tfgg676`, same die as the bare core board).
Purpose here: an **experiment-first platform for porting MiST / MiSTer cores** to this project's
Xilinx + RP2040-supervisor world — because the Wukong carries **both memory types on one board**,
which is exactly the MiSTer split (low-latency SDR for a core's main RAM, DDR3 for framebuffer /
scaler / bulk ROM). No custom baseboard spin needed to start.

Source material (QMTech, read-only): `/srv/git/qmtech/QM_XC7A100T_WUKONG_BOARD/V3/` — schematic,
hardware/experiment manuals, datasheets, and **Vivado reference designs** (see below), which give
authoritative pinouts and working MIG/HDMI/SDRAM examples. Pinouts extracted into `pin-mapping.md`.

## Why the Wukong for MiST/MiSTer (see the memory discussion)

MiST cores run the emulated machine's RAM out of **SDR SDRAM**; MiSTer keeps most cores' main RAM on
its optional **SDR module** and reserves DDR3 for the `ascal` scaler / framebuffer / big-ROM cores.
The Wukong gives that split natively:

| Memory | Part | Role in a port |
|---|---|---|
| **SDR SDRAM** | **W9825G6KH-6** (32 MB, 16-bit) — *same chip we already drive on the 10CL025* | latency-critical **core main RAM**; reuse our `SdramStatemachine` controller |
| **DDR3L** | **MT41K128M16** (256 MB, 16-bit, DDR3L-1333) via **MIG** | framebuffer / scaler / bulk ROM (latency-tolerant, buffered) |

So an Atari-in-SDR (or in BRAM) + framebuffer-in-DDR3 design maps cleanly, and larger cores that
need more than BRAM have the SDR chip waiting.

## Other on-board resources

- **HDMI out** — on-board connector, TMDS_33 on **bank 35** (no baseboard TMDS routing to design).
- **Gigabit Ethernet** (GMII) — opens a wired path for config/file transfer (vs. the RM2 WiFi plan).
- **USB-UART** (CH340N, on-board) — console without extra hardware.
- **N25Q064** config flash, **50 MHz** oscillator, user LEDs + keys, JTAG.

## Bring-up strategy — standalone first

The Wukong is **self-contained**: JTAG-program it, drive the on-board HDMI, use its SDR + DDR3 and
the CH340N UART for control. So cores (ours or a MiST/MiSTer port) can be brought up **without the
RP2040 supervisor at all** initially — lowest barrier to experimenting. The RP2040 supervisor / SD /
RM2 / USB-host / joysticks can be added later via the board's expansion headers (pinout TBD from the
hardware manual) if/when a self-contained SD-boot appliance is wanted.

Caveat unchanged from the general MiST/MiSTer discussion: memory is the *easy* half of a port — the
real work is swapping their I/O framework (MiST ARM / MiSTer HPS `sys`) for our supervisor and
converting **Altera primitives** (`altsyncram`, ALTPLL, `altddio`) to Xilinx (BRAM, MMCM, OSERDES,
MIG). The Wukong just removes the *hardware* unknowns so that port can be tackled on real silicon.

## Programming & supervisor (Pico / Pico W via JTAG)

The Wukong is **Xilinx Artix-7**, so the existing supervisor's Altera USB-Blaster loader
(`firmware/supervisor/jtag.c` hard-codes `IDCODE_10CL025 = 0x020F30DD`; `fpga_config.c` uses
Cyclone config constants) does **not** apply. But the Xilinx JTAG config is already solved in two
local tools — the JTAG *plumbing* (PIO bit-bang, TAP nav) carries over; only the config layer differs:

- **`~/pico-dirtyJtag` — WORKING bench path (verified 2026-07-20).** Pico as a **USB JTAG cable**
  driven by **openFPGALoader**. Built for **`pico2_w`** on a **Pico 2 W**, JTAG wired to the Wukong
  header as **TCK=GP0, TDO=GP1, TMS=GP2, TDI=GP3** (+ GND); `dirtyJtagConfig.h` set accordingly
  (LED=-1 for Pico 2 W, UART0 moved off GP0/1 → GP12/13). `openFPGALoader --cable dirtyJtag --detect`
  reads **`idcode 0x3631093` → xilinx artix a7 100t (xc7a100), irlength 6**. Program with
  `openFPGALoader --cable dirtyJtag <bitstream>.bit` (add `-f` to write the on-board SPI flash).
  Single-core, so it runs fine on RP2350. Host-driven, **not autonomous**.

- **`~/pico-pio-uart-jtag`** — intended base for the **autonomous supervisor**: its
  `jtag/fpga_xilinx.c` already does **on-device Xilinx 7-series config**
  (`JPROGRAM 0x0B` / `CFG_IN 0x05` / `JSTART 0x0C` + `.bit` parsing), IDCODE vendor auto-detect, SVF
  player. **BUT it HANGS on RP2350 (2026-07-20):** it launches **core1** (for per-core JOP UARTs) and
  the RP2350 multicore launch stalls **before USB enumeration** — the flashed image boots (BOOTSEL
  `2e8a:000f` and SWD both fine; CFSR clean) but never enumerates. Deferred for the supervisor until
  the core1 launch is fixed or made single-core. Still the tool of choice for JOP multi-UART on
  RP2040.

**Supervisor plan (Pico 2 W):** reuse `pico-pio-uart-jtag`'s `jtag/` core
(`jtag_tap.c` + `fpga_xilinx.c` + `svf_player.c`) inside the supervisor firmware — but **first
resolve its RP2350 multicore hang** (or start from single-core dirtyJtag's `pio_jtag` + graft the
`fpga_xilinx.c` config on top). Feed the `.bit` from **SD via FatFs** instead of USB — the autonomous
SD-boot analog of the Altera flow. **The supervisor JTAG-configures the FPGA from SD every boot, so
the on-board N25Q064 SPI flash is NOT used for the application bitstream** — nothing proprietary lives
on the board (`.bit`, OS, cart, disks all stay on SD). A **Pico 2 W (RP2350)** is preferred: onboard **CYW43439 = RM2
wireless on a proper PCB** (wireless for free, no jumper-wire SI). Mind reserved **GPIO23/24/25/29**
(CYW43) and that the LED is on the radio chip. USB-keyboard host (PIO-USB → Atari matrix) and
FatFs/config-boot/menu/SIO all carry over from the existing supervisor.

**Flashing the supervisor (Pico 2 W) — Raspberry Pi debug probe over SWD.** Standard RP
debug-connector order **SWCLK / GND / SWDIO**; the probe's **"D"** port ↔ the Pico 2 W's on-board
3-pin debug connector (the **"U"** port is UART — a common mix-up). Test the link with
`openocd -f interface/cmsis-dap.cfg -f <path>/target/rp2350.cfg -c "init; targets; shutdown"` — a
healthy link reads the RP2350 DPIDR; **`Error connecting DP: cannot read IDR`** means the SWD wires
are wrong (swapped SWCLK/SWDIO, cable in the "U" port, or unseated). *Verified 2026-07-20: after
correcting a reversed SWCLK/SWDIO, DPIDR `0x4c013477`, dual Cortex-M33 examined OK.* This openocd needs **rp2350.cfg**
(`/usr/local/share/openocd/.../rp2350.cfg` or `~/raspberrypi-openocd/tcl/target/rp2350.cfg`) — the
distro `/usr/share/openocd` only ships `rp2040.cfg`.

## Reference designs (`Software/XC7A100T/`)

- **Test06_HDMI_OUT** — TMDS_33 HDMI out + video PLL (authoritative HDMI pinout).
- **Test04_DDR3_MIG** — DDR3 MIG example (MT41K128M16, PHY 4:1) + full DDR3 pin XDC.
- **Test10_SDRAM** — W9825 SDR controller + full SDRAM pinout.
- **Test08_GMII_Ethernet**, **Test05_usb_uart_CH340N**, **Test01_led_key** — Ethernet / UART / LEDs+keys.

## Relationship to the other Artix board

`boards/atari-800-rp2040-qmtech-xc7a100t/` is the **custom RP2040 baseboard** around the bare core
board (single DDR3, RP2040 supervisor, our HDMI pin choice). This Wukong board is the **experiment
bench** — dual memory, on-board everything — to prove out cores (esp. MiST/MiSTer) before committing
them to the custom board. See `memory/project_xc7a100t_board`.
