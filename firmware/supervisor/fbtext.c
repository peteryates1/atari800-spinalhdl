#include "fbtext.h"
#include "boot.h"     // fpga_text_write

// Off-screen mirror of the FPGA's 40x15 char grid. fbtext_flush streams it to
// TextOverlay720, which rasterises the 8x16 font to 720p in logic (no SDRAM).
static uint8_t s_ch[FBT_ROWS][FBT_COLS];
static uint8_t s_cfg[FBT_ROWS][FBT_COLS];   // per-cell fg (stored; FPGA global for now)
static uint8_t s_fg = 0x0F, s_bg = 0x00;

void fbtext_colors(uint8_t fg, uint8_t bg) { s_fg = fg; s_bg = bg; }

void fbtext_clear(void) {
    for (int r = 0; r < FBT_ROWS; r++)
        for (int c = 0; c < FBT_COLS; c++) { s_ch[r][c] = ' '; s_cfg[r][c] = s_fg; }
}

void fbtext_puts(int row, int col, const char *s) {
    if (row < 0 || row >= FBT_ROWS) return;
    for (; *s && col < FBT_COLS; s++, col++) {
        if (col < 0) continue;
        s_ch[row][col]  = (uint8_t)*s;
        s_cfg[row][col] = s_fg;
    }
}

void fbtext_flush(void) {
    // One 'T' frame per row; the FPGA grid pointer auto-increments across cells.
    for (int r = 0; r < FBT_ROWS; r++)
        fpga_text_write((uint16_t)(r * FBT_COLS), s_ch[r], FBT_COLS);
}
