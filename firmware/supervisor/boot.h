// main.c services used by the supervisor menu.
#ifndef BOOT_H
#define BOOT_H

#include "config.h"

// Console logger (implemented in main.c, writes to the USB CDC console).
void cdc_printf(const char *fmt, ...);

// Load the OS + cart in `cfg` into BRAM, mount its disks, reset the Atari.
// Assumes the SD card is already mounted. (Implemented in main.c.)
void boot_run(const boot_config_t *cfg);

// Supervisor control bits: bit0 reset, bit1 start, bit2 select, bit3 option,
// bit4 halt. 0x10 = halt (pause), 0x00 = release. (Implemented in main.c.)
void fpga_send_control(uint8_t bits);

#endif // BOOT_H
