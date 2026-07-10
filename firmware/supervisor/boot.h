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
// bit4 halt, bit5 supDisplay (show the RP2040 framebuffer on HDMI).
// 0x10 = halt (pause), 0x20 = supDisplay, 0x00 = release. (Implemented in main.c.)
void fpga_send_control(uint8_t bits);

// On-screen framebuffer write path (SDRAM loader, port D). Used by fbtext.
void fpga_fb_begin(void);                                           // select SDRAM dest
void fpga_fb_write(uint32_t sdram_addr, const uint8_t *data, uint32_t len);
bool fpga_fb_verify(void);   // true if the loader got every byte (no drops)

#endif // BOOT_H
