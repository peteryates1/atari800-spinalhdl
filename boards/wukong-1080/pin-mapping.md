# QMTech Wukong V3 (XC7A100T) — pin mapping

FPGA **`xc7a100tfgg676`** (confirm speed grade `-1`/`-2` from the board). All pins below are from
QMTech's own Vivado reference designs (authoritative) under
`/srv/git/qmtech/QM_XC7A100T_WUKONG_BOARD/V3/Software/XC7A100T/`.

## System

| Signal | Ball | I/O std | Source |
|---|---|---|---|
| `sys_clk` (50 MHz) | **M21** | LVCMOS33 | Test01/06/10 |
| `sys_rst_n`        | **H7**  | LVCMOS33 | Test01/10 |
| LED 0 / LED 1      | **G21 / G20** | LVCMOS33 | Test01 (`led_1[0]/[1]`) |
| UART RX / TX (CH340N) | **F3 / E3** | LVCMOS33 | Test05 |

The MIG reference clocks DDR3 from an internal 166.666 MHz (MMCM from the 50 MHz), PHY ratio 4:1.

## HDMI out — TMDS_33, Bank 35 (Test06)

On-board HDMI connector; drive `OBUFDS` + `OSERDESE2`, `IOSTANDARD TMDS_33`.

> **Hardware-verified 2026-07-20:** QMTech's `Test06_HDMI_OUT/top.bit` loaded via
> `openFPGALoader --cable dirtyJtag` (Pico 2 W on GP0–3) configured with `done 1` and produced
> **solid colour bars at native 1080p60** on a monitor — the design's `video_define.v` selects
> `VIDEO_1920_1080` and the MMCM makes a ~148.4 MHz pixel clock (1920×1080@60; TMDS ~1.485 Gbps/ch).
> This confirms the bank-35 TMDS_33 output path on real silicon **and that this FPGA/board does
> native 1080p60** — the whole reason for the Artix move (the 10CL025 capped at 720p60). Also
> de-risks the same bank-35 choice on the custom baseboard.

| HDMI signal | **P** ball | **N** ball |
|---|---|---|
| TMDS Clock  | **D4** | **C4** |
| TMDS Data 0 | **E1** | **D1** |
| TMDS Data 1 | **F2** | **E2** |
| TMDS Data 2 | **G2** | **G1** |

(Note: this is QMTech's on-board choice — different data-pair assignment than our custom
baseboard's `hdmi.xdc`, but the same bank 35 / same TMDS_33 recipe. Use *these* balls on the Wukong.)

## SDR SDRAM — W9825G6KH-6 (32 MB, 16-bit), LVCMOS33 (Test10)

Reuse the project's `SdramStatemachine` controller; map its ports to these balls.

| Signal | Ball | | Signal | Ball |
|---|---|---|---|---|
| SDCLK  | G22 | | RAS    | K26 |
| SDCKE  | H22 | | CAS    | K25 |
| SDCS   | L25 | | SDWE   | J26 |
| BA0 / BA1 | M25 / M26 | | DQM0 / DQM1 | J25 / K23 |

Address `A[0..12]`: R26, P25, P26, N26, M24, M22, L24, L23, L22, K21, R25, K22, J21.
Data `D[0..15]`: D25, D26, E25, E26, F25, G25, G26, H26, J24, J23, H24, H23, G24, F24, F23, E23.

## DDR3L — MT41K128M16 (256 MB, 16-bit, DDR3L-1333) via MIG (Test04)

Do **not** hand-constrain — use the **MIG** IP with QMTech's config. Full 40-pin XDC + MIG `.prj` are
in `Test04_DDR3_MIG/.../mig_7series_0/user_design/constraints/mig_7series_0.xdc`. Anchors:
`ddr3_reset_n = H17`, `ddr3_ck_p/n = F18/F19`; MemoryDevice `MT41K128M16XX-15E`, DataWidth 16,
PHYRatio 4:1, input 166.666 MHz. Role = framebuffer / scaler / bulk (latency-tolerant).

## Pico 2 W supervisor wiring (Phase 2)

The Pico 2 W (RP2350) is the supervisor, wired to the Wukong via the JTAG header and
an expansion header (J11). RP2350 hardware-function pins:

| Function | Pico 2 W GPIO | → Wukong |
|---|---|---|
| **FPGA JTAG** (dirtyJtag) | GP0=TCK, GP1=TDO, GP2=TMS, GP3=TDI | JTAG header (see Programming section) |
| **USB keyboard** (PIO-USB host) | GP4 = D−, GP5 = D+ | USB-A socket |
| **FPGA SPI link** (SPI1) | GP12=RX/CIPO, GP13=CSn, GP14=SCK, GP15=TX/COPI | J11: D5, G5, G7, G8 |
| **SD card** (SPI0) | GP16=RX, GP17=CS, GP18=SCK, GP19=TX | microSD (no card yet) |
| CYW43 wireless (on-board) | GP23/24/25/29 | — (reserved) |

FPGA-SPI mapping detail: GP12(SPI1 RX/CIPO)=FPGA_DO→D5, GP13(CSn)→G5, GP14(SCK)→G7,
GP15(TX/COPI)=FPGA_DI→G8. This is the `RpAtariKeyboard` link (keyboard matrix + control
+ loader), to be re-added to the Wukong top for Phase 2 (Phase 1 baked the ROM instead).

**Phase 2 firmware plan** (`firmware/supervisor/`, add a `BOARD_WUKONG` branch):
supervisor is **single-core** → safe on RP2350; reuse SD/FatFs, USB-HID host, config-boot,
menu, SIO. New work: (1) the `BOARD_WUKONG` pin map above; (2) build for **`pico2_w`**;
(3) **Xilinx FPGA config** — the Altera `jtag.c`/`blaster.c`/`fpga_config.c` don't apply;
graft `pico-pio-uart-jtag`'s `jtag/fpga_xilinx.c` (JPROGRAM/CFG_IN/JSTART + `.bit`) fed from
SD. HDL side: re-add `RpAtariKeyboard` on SPI1 + switch the top to `internal_rom=5` (blank
loadable) so the supervisor loads OS/cart/disks from SD instead of baking them.

## Other

- **Gigabit Ethernet (GMII)** — pinout in Test08 (`GMII_ETH.xdc`).
- **Expansion headers** (for adding RP2040 supervisor / SD / RM2 / USB-host / joysticks) — not yet
  mapped; read the connector pinout from the hardware manual + `wukong-top-labelled-connectors.png`.
