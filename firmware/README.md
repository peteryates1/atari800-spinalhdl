# Firmware

C firmware for the RP2040 / Pico that supervises the FPGA.

- **[`supervisor/`](supervisor/)** — the real firmware: FPGA config from SD,
  USB-host keyboard, config-driven auto-boot, SIO disk emulation, the Alt-F12
  on-screen menu, and (on a Pico 2 W) a WiFi web UI. **See
  [`supervisor/README.md`](supervisor/README.md) for build & flash instructions.**
- **[`rm2_test/`](rm2_test/)** — a standalone CYW43 (RM2) wireless bring-up
  harness, independent of the supervisor.

Build targets and the board → `-DBOARD` mapping are in the supervisor README.
