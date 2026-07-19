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

## Other

- **Gigabit Ethernet (GMII)** — pinout in Test08 (`GMII_ETH.xdc`).
- **Expansion headers** (for adding RP2040 supervisor / SD / RM2 / USB-host / joysticks) — not yet
  mapped; read the connector pinout from the hardware manual + `wukong-top-labelled-connectors.png`.
