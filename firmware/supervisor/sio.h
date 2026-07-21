// SIO disk-drive emulator (D1:..D4:) for the RP2040 supervisor.
//
// The FPGA SioBridge is the hardware serializer for the Atari SIO bus: it
// deserializes command frames into an RX FIFO and serializes our responses
// from a TX FIFO. This module polls the bridge over SPI (via fpga_sio_read/
// write), parses SIO command frames, and streams sector data back from ATR
// images on the SD card.
//
// Only read-side commands are implemented (READ_SECTOR, GET_STATUS,
// GET_SPEED) — enough to boot and run from disk. Writes NAK.
#ifndef SIO_H
#define SIO_H

#include <stdbool.h>

#define SIO_MAX_DRIVES 4

// Mount an ATR image on drive `drive` (0 = D1:). Returns false on open/format
// error. Keeps the file open for sector reads.
bool sio_mount(int drive, const char *path);

// Unmount all drives (closes files). Call before (re)mounting at boot.
void sio_unmount_all(void);

// Poll the SioBridge for a pending command frame and service it. Call
// frequently from the main loop. Cheap when idle (one SPI status read).
void sio_poll(void);

// True if any drive is mounted (main loop can skip polling otherwise).
bool sio_any_mounted(void);

// Print SIO activity counters to the console (debug: 'D' command).
void sio_stats_print(void);

#endif // SIO_H
