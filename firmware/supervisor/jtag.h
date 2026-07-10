// Bit-banged JTAG master: RP2040 -> FPGA JTAG TAP.
//
// The base board breaks the TAP out on header J10 (current schematic rev),
// jumpered to the QMTech 10CL025 module's programming header. Pin map
// (Peter, 2026-07-10):
//   GPIO0 = TMS, GPIO1 = TCK, GPIO2 = TDI, GPIO3 = TDO   (contiguous)
// These are free GPIOs (SPI link = 16-19, PIO-USB = 6-9, FPGA bus = 4/5/10-15/
// 20-25, ADC = 26-29). GPIO0/1 are the default UART0 pins but the console is
// USB-CDC, so nothing else claims them.
//
// Scope of this file: bring-up only — initialise the pins and read the FPGA
// IDCODE. Proving the wiring + TAP here de-risks the later programming
// personality (Quartus-facing USB-Blaster emulation), which will drive this
// same 4-wire backend (ideally re-hosted on PIO for speed).
#ifndef JTAG_H
#define JTAG_H

#include <stdint.h>

// Initialise the JTAG pins (idempotent): TMS/TCK/TDI outputs, TDO input.
void jtag_init(void);

// Reset the TAP (Test-Logic-Reset) then shift out the 32-bit IDCODE register,
// which the TAP loads by default after reset. Returns the IDCODE (bit0 = 1 for
// a valid device).
uint32_t jtag_read_idcode(void);

// Console 'J': read the IDCODE and print it against the expected 10CL025 value.
void jtag_idcode_print(void);

#endif // JTAG_H
