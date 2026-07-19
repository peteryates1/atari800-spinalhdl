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
