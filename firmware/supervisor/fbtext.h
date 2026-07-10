// On-screen text for the supervisor: a character grid rasterised into the SDRAM
// framebuffer (buffer 2 @ 0x180000) that the FPGA scales to HDMI while the
// supDisplay control bit is set. 8x16 font over a 384x288 buffer -> 48x18 cells.
#ifndef FBTEXT_H
#define FBTEXT_H

#include <stdint.h>

#define FBT_COLS 48
#define FBT_ROWS 18

// Set the foreground/background GTIA colour indices used by subsequent puts
// (fg is stored per-cell; bg is global for the flush). fg 0x0F = white, 0 = black.
void fbtext_colors(uint8_t fg, uint8_t bg);

// Clear the grid to spaces (in the current fg).
void fbtext_clear(void);

// Write a string into the grid at (row, col), clipped to the grid.
void fbtext_puts(int row, int col, const char *s);

// Rasterise the whole grid into the SDRAM supervisor framebuffer via the loader.
void fbtext_flush(void);

#endif // FBTEXT_H
