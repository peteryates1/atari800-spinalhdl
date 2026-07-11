// On-screen text for the supervisor: a 40x15 character grid streamed to the
// FPGA's TextOverlay720, which generates the text pixels DIRECTLY at 1280x720
// (8x16 font, integer-scaled x4/x3 in logic) while supDisplay is set. No SDRAM,
// no upscaler -> crisp, wobble-free text. The grid geometry here MUST match
// TextOverlay720 (cols=40, rows=15).
#ifndef FBTEXT_H
#define FBTEXT_H

#include <stdint.h>

#define FBT_COLS 40
#define FBT_ROWS 15

// Set the foreground/background GTIA colour indices used by subsequent puts.
// NOTE: TextOverlay720 currently uses a global fg/bg (white on black); per-cell
// colour is stored but not yet honoured by the FPGA. fg 0x0F = white, 0 = black.
void fbtext_colors(uint8_t fg, uint8_t bg);

// Clear the grid to spaces (in the current fg).
void fbtext_clear(void);

// Write a string into the grid at (row, col), clipped to the grid.
void fbtext_puts(int row, int col, const char *s);

// Push the whole grid to the FPGA text overlay ('T' frames). Cheap enough to
// call on every menu change (600 bytes over SPI).
void fbtext_flush(void);

#endif // FBTEXT_H
