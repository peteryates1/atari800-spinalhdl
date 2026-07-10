// Altera USB-Blaster JTAG protocol engine (JTAG-only) for the supervisor.
//
// Ported from ~/pico-usb-blaster (MIT) — the debugged RP2040 Blaster with FTDI
// emulation. This board only wires the 4-wire JTAG TAP (GPIO0-3 = TMS/TCK/TDI/
// TDO, via base header J10 -> QMTech module), so the AS/PS pins (nCE/nCS/
// DATAOUT/nSTATUS) of the full Blaster protocol are dropped.
//
// The device always enumerates as a composite Altera USB-Blaster (09fb:6001):
// interface 0 is the FTDI vendor channel Quartus/jtagd program through, and
// interfaces 1-2 are the supervisor CDC console (see usb_descriptors.c). The USB
// glue (FTDI control-request emulation + bulk task) lives in main.c and runs
// every main-loop iteration; it is cheap when no host is programming.
#ifndef BLASTER_H
#define BLASTER_H

#include <stdint.h>
#include <stdbool.h>

// Reset the protocol state and initialise the JTAG pins (idempotent). Called on
// mode entry and on the FTDI SIO_RESET control request.
void blaster_reset(void);

// Consume `rxCount` Blaster protocol bytes from `rxBuf`, driving JTAG, and
// append any read-back bytes to `txBuf`. Returns the number of bytes written to
// `txBuf`. Byte format: bit7=shift-mode (bits[5:0]=count, bit6=read); otherwise
// bit-bang (bit0=TCK, bit1=TMS, bit4=TDI, bit5=output-enable, bit6=read).
int blaster_process(uint8_t rxBuf[], int rxCount, uint8_t txBuf[]);

#endif // BLASTER_H
