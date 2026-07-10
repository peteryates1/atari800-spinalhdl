#include "fbtext.h"
#include "font8x16.h"
#include "boot.h"     // fpga_fb_begin / fpga_fb_write

// Supervisor framebuffer: buffer 2, byte-addressed, 1 GTIA colour index/pixel.
#define FB_SUP_BASE  0x180000u
#define FB_STRIDE    512
#define FB_WIDTH     (FBT_COLS * 8)   // 384

static uint8_t s_ch[FBT_ROWS][FBT_COLS];
static uint8_t s_cfg[FBT_ROWS][FBT_COLS];
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
    static uint8_t line[FB_WIDTH];
    fpga_fb_begin();
    for (int r = 0; r < FBT_ROWS; r++) {
        for (int ly = 0; ly < 16; ly++) {
            for (int c = 0; c < FBT_COLS; c++) {
                uint8_t bits = font8x16[s_ch[r][c] & 0x7F][ly];
                uint8_t fg   = s_cfg[r][c];
                uint8_t *p   = &line[c * 8];
                for (int b = 0; b < 8; b++)
                    p[b] = (bits & (0x80 >> b)) ? fg : s_bg;
            }
            uint32_t y = (uint32_t)r * 16 + ly;
            fpga_fb_write(FB_SUP_BASE + y * FB_STRIDE, line, FB_WIDTH);
        }
    }
}
