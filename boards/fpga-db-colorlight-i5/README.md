# fpga-db daughterboard — Colorlight i5 / i9 / i9+ (rev 2026-08-12)

Replacement for the stock Colorlight **i5 ext board**, sized to fit the physical **QM stack**
(stacked QMTech daughterboards + Wukong). Derived from the retro-800 / Atari-800 base board
rather than purpose-built for it — but it carries the Atari peripheral set, so it doubles as the
Atari-800 test ground. Takes **any** of the three DDR2-SODIMM-200 modules in socket U5 —
**i5** (LFE5U-25F), **i9** (LFE5U-45F), or **i9+** (Artix XC7A50T) — so a second board can be
populated for the i9/i9+ and swapped in.

Design source: `Netlist_fpga-db-colorlight-i5_2026-08-12.enet` (EasyEDA export, PCB side — it
carries the differential-pair declarations that the schematic-side export does not). The
`2026-08-11` export is kept alongside it as the pre-review revision.
Module pinouts from [wuxx/Colorlight-FPGA-Projects](https://github.com/wuxx/Colorlight-FPGA-Projects).

Predecessor: [`../rp2040-colorlight/`](../rp2040-colorlight/) (Atari 800 Lite, 2026-07-15).
Per-chip TMDS inversion and JTAG pad coordinates live in the
[i5](../i5-7v0/README.md) and [i9+](../i9plus-6v1/README.md) board notes.

## What's on it

| Block | Parts |
|---|---|
| Module socket | U5 — DDR2-SODIMM-200P (TE 1473006), 146 pins in the symbol, **82 signal** |
| Supervisor | U2 — RP2040-Stamp (8 MB flash, ME6211 3V3 500 mA LDO, WS2812 on GPIO21) |
| Video | HDMI1 HDMI-A + D1/D2 AZ1045-04F ESD + C1–C7/C10 100 nF AC coupling |
| Ethernet | RJ1 HR911130A gigabit jack w/ magnetics on **ETH1** (ETH2 not fitted) |
| RAM | U3 — IS63LV1024L-10TLI, 128 K × 8 async SRAM → FPGA, `CE#` pulled up by R23 (10 k) |
| Storage | J8 — DM3AT microSD, SPI to RP2040 |
| USB | J3 dual USB-A (2× PIO host), J4 micro-B (native RP2040 USB) |
| Controls | J6/J7 DB9 joysticks (**POTs to the FPGA**, C20–C23 22 nF), SW4–SW7, D4–D11 LED0–7 |
| Audio | J5 3.5 mm, 2-pole RC per channel (560 Ω/10 nF ×2) + 100 µF |
| JTAG | J2 5-pin JST + `i5_*` / `i9+_*` contact pads, all in parallel on TMS/TCK/TDI/TDO |
| Power | J1 barrel → SW1 → U1 DSN-MINI-360 buck → 5V; 3V3 from the Stamp's LDO |

**vs. the July `rp2040-colorlight` board:** adds the RJ45 + 2 link LEDs, 4 more user LEDs
(LED4–7), 6× SMD tact switches replacing the 6×6 keys, and a fully remapped RP2040.

## Pin map

`pinmap.csv` — SODIMM pin ↔ net ↔ **i5 ECP5 ball** ↔ **i9 ECP5 ball** ↔ **i9+ Artix ball** ↔
status. All 90 signal rows are `netlist` (as drawn) in this revision; the 8 ETH1 rows have no
ball on any module because they land on the on-module PHY, not the FPGA. Regenerate from the
netlist rather than editing by hand.

**RP2040 pin map — changed again from the 08-11 revision, firmware re-pin needed:**

| Function | GPIO |
|---|---|
| FPGA JTAG (bit-bang) | 0–3 = **TCK/TMS**/TDI/TDO → J2 + module pads |
| RP2040 ↔ FPGA link | 4–17, 24, 25 (16 lines) → SODIMM 155…129, 127, 125 |
| microSD (SPI) | CS=18, CD=19, CIPO=20, CLK=22, COPI=23 |
| *(spare)* | **21 — unconnected** (the Stamp's onboard NeoPixel) |
| USB host ×2 (PIO) | 26/27 = port 2 (D+/D−), 28/29 = port 1 (**D−/D+**) → J3 |

GPIO0/GPIO1 are now TCK/TMS respectively (swapped vs. the 08-11 revision — bit-banged either
way, but the firmware map must match). Paddle POTs no longer touch the RP2040 at all; they go
to the FPGA (below). 29 of 30 GPIOs are allocated, GPIO21 is the only spare.

## Verified

Checked against the `DDR2-SODIMM-200P` tables in `colorlight_i9plus_v6.1.md` (Artix),
`colorlight_i9_v7.2.md` (ECP5 i9) and the i5-v7.0 section of the upstream `README.md`.

- ✅ **U5 symbol matches the i9+ edge table exactly** — all 146 pin names, **0 mismatches**.
  Power/GND (1–12), ETH1 pairs (15–35) and every used signal pin are identical across i5, i9
  and i9+, so the socket wiring is module-agnostic.
- ✅ **Zero i5/i9 ball differences on any used pin.** Pin 41 — the *only* pin where the two
  tables disagree — is now unused (see item 9 below), so this revision is genuinely
  build-once-run-anywhere across the ECP5 pair.
- ✅ **Every ball is placeable I/O on the i5.** `fit/` reproduces it:
  `yosys synth_ecp5` + `nextpnr-ecp5 --25k --package CABGA381 --speed 6 --lpf fit/fit_i5.lpf`
  → **83/197 TRELLIS_IO (42 %), routing complete, 0 errors**, 82 pins, no duplicate balls.
- ✅ **24 free general-purpose SODIMM pins** remain on the i5: 41, 57, 59, 61, 63, 65, 67, 68,
  73, 75, 77, 81, 85, 87, 89, 91, 93, 97, 101, 103, 113, 119, 121, 123.
- ✅ **No collision with on-module peripherals** (SDRAM, SPI flash, both PHYs, the 25 MHz clock
  on P3, the module LED) on any of the three modules. *(The `J4`/`J5` hits an automated scan
  throws on the i9+ are silk designators of the module's JTAG pad row — the i5 equivalents are
  J27/J30/J31/J32 — not FPGA balls.)*
- ✅ **HDMI matches the documented plan exactly** — CLK 71/69, TX0 83/79, TX1 99/95,
  TX2 109/111, correct polarity on all four; ESD arrays on the **connector** side of the AC caps;
  flow-through pin pairing on D1/D2 correct.
- ✅ **15 differential pairs now declared** in the export: 4 TMDS connector-side, 4 TMDS
  module-side, 3 USB, 4 ETH1. Nothing left undeclared.
- ✅ **Atari DB9 pinout correct** on both ports (1 up, 2 down, 3 left, 4 right, 5 POT1, 6 trig,
  7 +V, 8 GND, 9 POT0); pin 7 on **3V3**.
- ✅ **SRAM block complete** — all 17 address + 8 data + CE#/OE#/WE#, 100 nF at each VDD pin,
  and R23 10 k from 3V3 to `SRAM_CE#`.

## Fixed since the 08-11 review

**1. Paddle POTs → FPGA.** ✅ `JS*_POT0/1` now leave the RP2040 ADC entirely and land on the
SODIMM, each with a 22 nF cap to GND (C20–C23) — the RC dump-and-time scheme. The pins chosen
differ from the ones proposed on 08-11, and sit next to their own joystick groups:

| Net | SODIMM | i5/i9 ball | i9+ ball |
|---|---|---|---|
| JS1_POT0 | 86 | H18 | M3 |
| JS1_POT1 | 84 | J17 | R3 |
| JS2_POT0 | 72 | M4 | K3 |
| JS2_POT1 | 70 | N4 | K2 |

All four are in the fit check and clash with nothing on any module. **The core side is already
built**: `Pokey.scala` exposes `potIn` (8 bits) and `potReset`, and runs the authentic 228-count
(`0xE4`) scan, latching the counter while `potIn(n)` is low. The change needed in
`Atari800CoreSimpleSdram.scala` is to drop the synthetic `PotFromSigned` model and drive the pad
`0` while `POT_RESET` is asserted, Hi-Z otherwise, feeding the pad back into `potIn(n)`.

Two things to carry into the HDL/bring-up:
- Set **`HYSTERESIS=LARGE`** on those four inputs. A 14 ms ramp through an unspecified LVCMOS33
  trip point will otherwise chatter, and the latch takes the *last* low sample.
- 22 nF is sized for a **1 MΩ CX30 paddle** (full scale ≈ counts 96 / 209 / 322 for a trip point
  of 0.8 / 1.5 / 2.0 V — only VIL 0.8 / VIH 2.0 are guaranteed). A 500 kΩ 5200 stick wants 47 nF.
  Expect to trim C on the first board, or carry a scale constant in the core. **Note the ADC path
  is gone**, so that trimming is now done by reading counts out of the FPGA, not by probing with
  the RP2040 — worth a debug register.
- Fast-pot mode (SKCTL bit 2, 1.79 MHz) saturates at 228; real hardware does the same.

**2. GPIO21 freed.** ✅ The SD block moved off the Stamp's NeoPixel pin — `SD_CS` is now
**GPIO18** (the fix landed on the SD group rather than on GPIO16, which stayed an FPGA link
line), and GPIO21 is left unconnected. The NeoPixel is usable as a status LED again.

**4. 5V bulk capacitance added.** ✅ 5×10 µF + 4×100 nF = **50.4 µF** on 5V, plus 10 µF on VIN.
That covers the socket and decoupling. It is still thin as *bulk* for two USB-A ports hot-
plugging off a 1.8 A buck — one 100–220 µF electrolytic near the socket would be the cheap
insurance — but the rail is no longer bare, so this is a judgement call, not a defect.

**9. ETH1 link LEDs moved off SODIMM 41.** ✅ `ETH1_LED-L+` → pin 49 (K18 / K18 / T3),
`ETH1_LED-R+` → pin 51 (C18 / C18 / T4), both free and clean on all three modules. Pin 41 is now
unused, which also disposes of the toolchain trap: 41 is the module's own LED D2 on **both**
ECP5 parts (i5 = U16, i9 = L2 — the i9 v7.2 changelog says so explicitly) and the only pin where
the i5 and i9 tables differ.
- **Still true for the generator:** `../rp2040-colorlight/fpga/pinmap.csv` was built from the i9
  table, so any `i5.lpf` produced that way is wrong at pin 41. This board no longer trips over
  it, but the generator should still take pin 41 from the i5 table.

**SRAM_CE# pull-up.** ✅ R23, 10 k to 3V3. And yes — **`SRAM_WE#` does not need one**. `CE#` high
puts the IS63LV1024L in standby with outputs Hi-Z and inhibits writes regardless of `WE#`, so
holding `CE#` high through the FPGA's configuration window is sufficient on its own.

**Differential pairs declared.** ✅ Confirmed: they are a PCB-side property in EasyEDA, not
settable in the schematic editor, which is why the 08-11 schematic export showed none. The
PCB-side export carries all 15. Length-matching and 100 Ω still need to be applied at layout.

## 🚩 Open

**8. Remove audio R7 (1 kΩ).** You asked whether it should go given the FPGA can duplicate the
output on both channels — **yes, remove it**. It bridges the two first-stage nodes
(L: `AUDIO_L`–R8–node–R9–out, R: `AUDIO_R`–R5–node–R6–out; R7 sits node-to-node). If both
channels carry the same mono POKEY signal, both ends of R7 are at the same potential and it
carries no current — it does nothing, so duplicating in the FPGA does not need it. If the
channels ever differ — dual-POKEY stereo, or one side muted — it is a fixed −9 dB L↔R crosstalk
path that cannot be switched out. So it is either a no-op or a defect, and never a benefit.
Delete it, or leave the footprint as DNP.

Separately, the output impedance is 560 + 560 = **1.12 kΩ**, which is **line-out only** —
headphones plugged straight in will be inaudible. That is unchanged and is fine if intended.

**11. RJ45 centre taps are asymmetric** — P1 has 100 nF to GND, P10 a 0 Ω short. Noted as based
on the QMTech FPGA DB / Wukong reference rather than the Colorlight family (the stock ext board
doesn't jack Ethernet at all). Leaving as-is; recorded here as the first thing to look at if
Ethernet doesn't link on power-up, since making both pads configurable (0 Ω / 100 nF / DNP) is
the cheap hedge.

**12. BOM gaps have grown, not shrunk.** Flagged as not-fixing, but for the record the count is
now larger than at 08-11: R1 and R12–R19 (300 Ω) **lost** their `C17617` part number in this
revision when their device changed, and R23, C20–C23, R10/R11/R20, D3–D11 and H1 have none.
That is 9 more line items for the assembler to resolve than last time — worth one pass before
the order rather than a redesign.

## Accepted as-is (recorded, not re-raised)

These were raised on 08-11 and closed by decision:

- **USB1 D+/D− reversed relative to USB2** (GPIO28 = D−, 29 = D+; port 2 is D+/D−). Deliberate,
  to avoid vias; Pico-PIO-USB handles it with `PIO_USB_PINOUT_DMDP`, as on prior setups.
- **No TVS / fuse / reverse-polarity protection** on VIN or the adjustable buck's output.
- **No current limit or ESD on HDMI +5 V and USB-A VBUS.**
- **J4 (micro-USB) VBUS unconnected** — the barrel jack must be live to power or BOOTSEL the Stamp.
- **No series resistors or ESD on the DB9 / SD / USB signal lines**, and the DB9 shells and SD
  shield pins stay floating.
- **DDC/HPD not routed to the FPGA** — no EDID read, no hot-plug sense; fixed-resolution output.
- **SW1's mounting lugs (pins 4/5) floating** — the part has solderable pads, so it does not need
  GND for mechanical strength.

## Worth doing, lower stakes

- **3V3 is solely the Stamp's ME6211 (3.3 V, 500 mA).** Load: Stamp + NeoPixel + SRAM (~40–70 mA
  active) + SD (~100 mA peak) + two joystick ports. It fits, but the SOT-23-5 dissipates ~340 mW
  at 200 mA. Add a 10 µF local to U3 — the whole 3V3 net still carries only 10.3 µF.
- **Keep the TMDS AC caps swappable for 0 Ω links.** *(Corrected 2026-10-10, see
  [HDMI on hardware](#hdmi-on-hardware-2026-10-10).)* This note said 100 nF "is right for the
  Artix (`OBUFDS`/TMDS_33)". It is not: TMDS_33 gives **no signal** through the caps. Fitted as
  built, both families drive pseudo-differential LVCMOS33. 0 Ω links would allow TMDS_33 on the
  Artix, but would DC-couple the ECP5's 3.3 V swing into the sink's AVcc. Same 0402 footprint
  either way, so it stays a free hedge.
- **Silk note: this board carries the only HDMI output.** *(Correction to the 08-11 note, which
  claimed the i5 module has its own HDMI connector — it does not; there is no HDMI block in the
  i5 documentation, and G19/H20 are ordinary SODIMM signal pins 93/97. The HDMI connector lives
  on the stock ext board, which this board replaces.)*

## HDMI on hardware (2026-10-10)

i9+ module, this board with all connectors fitted, a monitor on HDMI1. Test bitstreams are in
[`hdmi_bars/`](hdmi_bars/).

- **TMDS_33 (`OBUFDS`) gives NO SIGNAL.** Every lane is FPGA ball → 100 nF → connector with no
  pull-ups. TMDS_33 is a current-steering output that only swings when the sink's 50 Ω
  terminations pull it to 3.3 V over a DC path, and the caps remove that path. The FPGA side was
  healthy (MMCM locked, frames counted on the LEDs). The build was
  `hdmi_bars/hdmi_bars_top.v`: 720p with Digilent rgb2dvi.
- **Pseudo-differential LVCMOS33 WORKS:** two single-ended outputs per lane, one the complement
  of the other, as the ECP5's `LVCMOS33D` does. The build is `hdmi_bars/hdmi_se_top.v`: 640×480,
  a 10-bit shift register at 125 MHz feeding one `ODDR` per pin. The monitor shows the border,
  the eight bars in the right order (so all three lanes are right) and the moving box.
- **Polarity is free with pseudo-diff.** The ports are named by CONNECTOR polarity: + on AB1
  (CLK), Y3 (TX0), AA6 (TX1), AB8 (TX2), confirmed against the netlist (`DVI_*_P` nets). The
  Artix P/N inversion table in [`../i9plus-6v1/README.md`](../i9plus-6v1/README.md) only applies
  to `OBUFDS`.
- **720p needs a gearbox.** A 10:1 `OSERDESE2` is a master/slave pair that borrows the partner
  ball's OLOGIC, which the complement output needs. Each pin needs its own 8:1 DDR `OSERDESE2`
  fed by a 10→8 gearbox.
- **Board decision (Peter, 2026-10-10): this board is an i9+ board**, because a board is tied to one
  module family once its pogo pins are fitted. So C1–C7 and C10 are being replaced by **0 Ω links**,
  and the Artix drives `TMDS_33` DC-coupled, as the Wukong does (verified at 1080p).
  `hdmi_bars/hdmi_bars_top.v` (720p, CLK/TX1/TX2 inverted) is the build for that.
  **With the links fitted, NEVER load the `hdmi_se*` / `hdmi720_se*` (pseudo-diff LVCMOS33)
  builds:** a push-pull 3.3 V output DC-coupled into the sink's 50 Ω to 3.3 V sinks about
  66 mA per pin. Keep the HDMI cable out at power-up until our bitstream is loaded, because the
  module's factory flash image drives pins on this board.
- **Pseudo-diff, as fitted with caps:** DRIVE 16 and DRIVE 8 blanked more and more over minutes.
  DRIVE 4 (higher output impedance, so less swing into 50 Ω) held a clean 640×480 picture. 720p
  pseudo-diff at DRIVE 16 was noisy, then blanked: 742 Mb/s is too much for this path.
- **Superseded — the picture blanks intermittently.** The whole screen goes for about a second, more
  often and for longer as the board runs. It was first seen at `DRIVE 16 SLEW FAST`, which
  drives far more swing into 50 Ω than TMDS needs. Lower-drive variants are under test
  (`build_se.tcl -tclargs <part> <drive> <slew>`).
- `hdmi_bars/continuity/`: a conductor-by-conductor test, i9+ → HDMI cable → Wukong (a distinct
  square wave per conductor, edge counts on the Wukong's console). Built, not needed once the
  picture came up.

## Files

| File | What |
|---|---|
| `Netlist_fpga-db-colorlight-i5_2026-10-10.enet` | current export (R7 removed; RP2040↔FPGA link lines GPIO14-17/24/25 permuted on SODIMM 125-135) — use this one |
| `Netlist_fpga-db-colorlight-i5_2026-08-12.enet` | EasyEDA PCB-side export — the design source of the review above |
| `Netlist_fpga-db-colorlight-i5_2026-08-11.enet` | previous revision, kept for diffing |
| `pinmap.csv` | SODIMM ↔ net ↔ i5 / i9 / i9+ ball ↔ status; 90 signal rows |
| `fit/boardpins_fit.sv`, `fit/fit_i5.lpf` | i5 pin-validity fit-check, 82 pins |
| `BOM_fpga-db-colorlight-i5_fpga-db-colorlight-i5_2026-08-14.csv` | bill of materials |
| `hdmi_bars/` | i9+ HDMI test bitstreams (Vivado, `vivado -mode batch -source build_se.tcl`); see [HDMI on hardware](#hdmi-on-hardware-2026-10-10) |

Not generated yet: `i5.lpf` / `i9plus.xdc` constraint files (needs per-net direction) — take the
[`../rp2040-colorlight/fpga/`](../rp2040-colorlight/fpga/) generator and re-point it at this
netlist.
