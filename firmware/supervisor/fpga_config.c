#include "fpga_config.h"
#include "blaster.h"
#include "boot.h"     // cdc_printf
#include "pico/stdlib.h"
#include "hardware/flash.h"
#include "hardware/sync.h"
#include "lib/fatfs/source/ff.h"
#include <string.h>

// Reserved flash region for the staged .rbf. Firmware is ~140 KB, so 1 MB in is
// well clear of it (and SWD reflash always recovers if anything goes wrong).
#define STAGE_OFFSET   (1u * 1024 * 1024)          // 0x100000
#define STAGE_XIP      (XIP_BASE + STAGE_OFFSET)
#define STAGE_HDR_LEN  FLASH_SECTOR_SIZE           // header in its own 4 KB sector
#define STAGE_MAX_RBF  (1u * 1024 * 1024 - STAGE_HDR_LEN)
#define STAGE_MAGIC    0x31464252u                 // "RBF1"

// `mtime` packs the SD file's FatFs date/time so we can detect a changed file
// (size + mtime) without re-reading it — avoids needless flash wear.
typedef struct { uint32_t magic, len, sum, mtime; } stage_header_t;

static uint32_t filinfo_mtime(const FILINFO *fi) {
    return ((uint32_t)fi->fdate << 16) | fi->ftime;
}

// --- Cyclone IV / 10 LP JTAG configuration constants (from program_fpga.c) ---
#define IR_LEN          10
#define IR_CONFIG       0x002
#define IR_CHECK_STATUS 0x004
#define IR_STARTUP      0x003
#define IR_BYPASS       0x3FF

// ---------------------------------------------------------------- staging ---

uint32_t fpga_stage_rbf(const char *path) {
    FILINFO fi;
    if (f_stat(path, &fi) != FR_OK) return 0;
    uint32_t mtime = filinfo_mtime(&fi);

    FIL f;
    if (f_open(&f, path, FA_READ) != FR_OK) return 0;
    uint32_t len = f_size(&f);
    if (len == 0 || len > STAGE_MAX_RBF) { f_close(&f); return 0; }

    // Erase header + data (rounded up to whole sectors). Header written last so
    // a valid header always implies fully-written data.
    uint32_t total = STAGE_HDR_LEN + len;
    uint32_t erase = (total + FLASH_SECTOR_SIZE - 1) & ~(FLASH_SECTOR_SIZE - 1);
    uint32_t ints = save_and_disable_interrupts();
    flash_range_erase(STAGE_OFFSET, erase);
    restore_interrupts(ints);

    static uint8_t sect[FLASH_SECTOR_SIZE];
    uint32_t written = 0, sum = 0;
    while (written < len) {
        UINT rd = 0;
        uint32_t want = (len - written) < FLASH_SECTOR_SIZE ? (len - written) : FLASH_SECTOR_SIZE;
        if (f_read(&f, sect, want, &rd) != FR_OK || rd == 0) break;
        for (UINT i = 0; i < rd; i++) sum += sect[i];
        // flash_range_program needs a whole number of pages; pad tail with 0xFF.
        uint32_t prog = (rd + FLASH_PAGE_SIZE - 1) & ~(FLASH_PAGE_SIZE - 1);
        for (uint32_t i = rd; i < prog; i++) sect[i] = 0xFF;
        ints = save_and_disable_interrupts();
        flash_range_program(STAGE_OFFSET + STAGE_HDR_LEN + written, sect, prog);
        restore_interrupts(ints);
        written += rd;
    }
    f_close(&f);
    if (written != len) return 0;

    static uint8_t hdr[FLASH_PAGE_SIZE];
    memset(hdr, 0xFF, sizeof hdr);
    stage_header_t *h = (stage_header_t *)hdr;
    h->magic = STAGE_MAGIC; h->len = len; h->sum = sum; h->mtime = mtime;
    ints = save_and_disable_interrupts();
    flash_range_program(STAGE_OFFSET, hdr, FLASH_PAGE_SIZE);
    restore_interrupts(ints);
    return len;
}

// Stage the .rbf only if it differs from what's already in flash — compares SD
// size + mtime (cheap, no full read) against the staged header. Returns the
// staged length (existing or freshly written), or 0 if there's nothing to use.
uint32_t fpga_stage_if_changed(const char *path) {
    FILINFO fi;
    if (f_stat(path, &fi) != FR_OK) return fpga_staged_len();  // no SD file: keep staged
    const stage_header_t *h = (const stage_header_t *)STAGE_XIP;
    if (h->magic == STAGE_MAGIC && h->len == fi.fsize &&
        h->mtime == filinfo_mtime(&fi) && fpga_staged_len() == fi.fsize) {
        return fi.fsize;                 // unchanged & intact -> no flash write
    }
    return fpga_stage_rbf(path);         // new/changed -> (re)stage
}

uint32_t fpga_staged_len(void) {
    const stage_header_t *h = (const stage_header_t *)STAGE_XIP;
    if (h->magic != STAGE_MAGIC || h->len == 0 || h->len > STAGE_MAX_RBF) return 0;
    const uint8_t *d = (const uint8_t *)(STAGE_XIP + STAGE_HDR_LEN);
    uint32_t sum = 0;
    for (uint32_t i = 0; i < h->len; i++) sum += d[i];
    return (sum == h->sum) ? h->len : 0;
}

// ------------------------------------------------------ JTAG config flow ---
// All JTAG is driven by feeding Blaster protocol bytes to blaster_process()
// (the same engine Quartus programs through). Bit-bang byte: bit0 TCK, bit1 TMS,
// bit4 TDI, bit5 output-enable, bit6 read. Shift byte: 0x80|count then data.

static void bb(const uint8_t *cmds, uint32_t n) {
    uint8_t tx[64];
    for (uint32_t off = 0; off < n; ) {
        uint32_t k = (n - off) < 64 ? (n - off) : 64;
        blaster_process((uint8_t *)(cmds + off), (int)k, tx);
        off += k;
    }
}

static void jtag_reset(void) {
    // 5 TCK with TMS=1 -> Test-Logic-Reset, then one TMS=0 -> Run-Test/Idle.
    uint8_t c[12];
    int p = 0;
    for (int i = 0; i < 5; i++) { c[p++] = 0x22; c[p++] = 0x23; }
    c[p++] = 0x20; c[p++] = 0x21;
    bb(c, p);
}

// n TCK in Run-Test/Idle (TMS=0).
static void tck_rti(int n) {
    uint8_t c[64];
    while (n > 0) {
        int k = n < 32 ? n : 32, p = 0;
        for (int i = 0; i < k; i++) { c[p++] = 0x20; c[p++] = 0x21; }
        bb(c, p);
        n -= k;
    }
}

static void scan_ir(int ir) {
    uint8_t c[64];
    int p = 0;
    // RTI -> Select-DR -> Select-IR -> Capture-IR -> Shift-IR
    c[p++] = 0x22; c[p++] = 0x23;
    c[p++] = 0x22; c[p++] = 0x23;
    c[p++] = 0x20; c[p++] = 0x21;
    c[p++] = 0x20; c[p++] = 0x21;
    for (int i = 0; i < IR_LEN; i++) {
        uint8_t tdi = (ir >> i) & 1 ? 0x10 : 0x00;
        uint8_t tms = (i == IR_LEN - 1) ? 0x02 : 0x00;
        c[p++] = 0x20 | tdi | tms;
        c[p++] = 0x21 | tdi | tms;
    }
    // Exit1-IR -> Update-IR -> RTI
    c[p++] = 0x22; c[p++] = 0x23;
    c[p++] = 0x20; c[p++] = 0x21;
    bb(c, p);
}

static void enter_shift_dr(void) {
    uint8_t c[] = {0x22, 0x23, 0x20, 0x21, 0x20, 0x21};
    bb(c, sizeof c);
}

static void exit_shift_dr(void) {
    uint8_t c[] = {0x22, 0x23, 0x20, 0x21};
    bb(c, sizeof c);
}

bool fpga_config_from_flash(void) {
    uint32_t len = fpga_staged_len();
    if (len == 0) return false;
    const uint8_t *rbf = (const uint8_t *)(STAGE_XIP + STAGE_HDR_LEN);

    blaster_reset();               // init JTAG pins/engine

    jtag_reset();
    scan_ir(IR_CONFIG);
    tck_rti(6000);
    enter_shift_dr();

    // Shift all but the last byte, LSB-first, 63 data bytes per shift command.
    uint32_t total = len - 1, pos = 0;
    uint8_t chunk[64];
    while (pos < total) {
        int n = (total - pos) < 63 ? (int)(total - pos) : 63;
        chunk[0] = 0x80 | n;
        memcpy(chunk + 1, rbf + pos, n);
        bb(chunk, 1 + n);
        pos += n;
    }
    // Last byte bit-banged with TMS=1 on the final bit (Exit1-DR).
    uint8_t last = rbf[len - 1], lc[16];
    int lp = 0;
    for (int i = 0; i < 8; i++) {
        uint8_t tdi = (last >> i) & 1 ? 0x10 : 0x00;
        uint8_t tms = (i == 7) ? 0x02 : 0x00;
        lc[lp++] = 0x20 | tdi | tms;
        lc[lp++] = 0x21 | tdi | tms;
    }
    bb(lc, lp);
    exit_shift_dr();

    scan_ir(IR_CHECK_STATUS); tck_rti(200);
    scan_ir(IR_STARTUP);      tck_rti(200);
    scan_ir(IR_BYPASS);

    // Read CONF_DONE (bit-bang read byte: OE|TMS|READ).
    uint8_t rd = 0x62, buf[1] = {0};
    blaster_process(&rd, 1, buf);
    return (buf[0] & 1) != 0;
}

#ifdef BOARD_WUKONG
// ---------------------------------- Xilinx 7-series (Artix-7) config -------
// Same blaster-byte primitives as above (jtag_reset/tck_rti/enter_shift_dr/
// exit_shift_dr/bb), but IR is 6 bits and the flow is JPROGRAM/CFG_IN/JSTART.
// The bitstream is STREAMED from SD (not flash-staged): the Wukong's SD hangs off
// the Pico directly, and the ~3.8 MB config won't fit the 1 MB stage region.
#define XIR_LEN       6
#define XIR_JPROGRAM  0x0B
#define XIR_CFG_IN    0x05
#define XIR_JSTART    0x0C
#define XIR_BYPASS    0x3F

// Xilinx wants the config bitstream MSB-first; the blaster shift is LSB-first, so
// reverse each byte (this is exactly openFPGALoader's reverse=true for MEM_MODE).
static uint8_t reverse_byte(uint8_t b) {
    b = (uint8_t)((b & 0xF0) >> 4 | (b & 0x0F) << 4);
    b = (uint8_t)((b & 0xCC) >> 2 | (b & 0x33) << 2);
    b = (uint8_t)((b & 0xAA) >> 1 | (b & 0x55) << 1);
    return b;
}

// IR scan with the 6-bit Xilinx IR length (RTI -> Shift-IR -> RTI).
static void scan_ir_x(int ir) {
    uint8_t c[64];
    int p = 0;
    c[p++] = 0x22; c[p++] = 0x23;
    c[p++] = 0x22; c[p++] = 0x23;
    c[p++] = 0x20; c[p++] = 0x21;
    c[p++] = 0x20; c[p++] = 0x21;
    for (int i = 0; i < XIR_LEN; i++) {
        uint8_t tdi = (ir >> i) & 1 ? 0x10 : 0x00;
        uint8_t tms = (i == XIR_LEN - 1) ? 0x02 : 0x00;
        c[p++] = 0x20 | tdi | tms;
        c[p++] = 0x21 | tdi | tms;
    }
    c[p++] = 0x22; c[p++] = 0x23;
    c[p++] = 0x20; c[p++] = 0x21;
    bb(c, p);
}

// Shift n (already bit-reversed) bytes into DR, LSB-first, staying in Shift-DR.
static void shift_dr_bytes(const uint8_t *buf, uint32_t n) {
    uint8_t chunk[64];
    uint32_t pos = 0;
    while (pos < n) {
        int k = (n - pos) < 63 ? (int)(n - pos) : 63;
        chunk[0] = 0x80 | k;
        memcpy(chunk + 1, buf + pos, (size_t)k);
        bb(chunk, 1 + k);
        pos += (uint32_t)k;
    }
}

// Shift the final byte (already reversed) with TMS=1 on the last bit -> Exit1-DR.
static void shift_dr_last(uint8_t b) {
    uint8_t lc[16];
    int lp = 0;
    for (int i = 0; i < 8; i++) {
        uint8_t tdi = (b >> i) & 1 ? 0x10 : 0x00;
        uint8_t tms = (i == 7) ? 0x02 : 0x00;
        lc[lp++] = 0x20 | tdi | tms;
        lc[lp++] = 0x21 | tdi | tms;
    }
    bb(lc, lp);
}

bool fpga_config_from_sd(const char *path) {
    FIL f;
    FRESULT fr = f_open(&f, path, FA_READ);
    if (fr != FR_OK) { cdc_printf("fpga: f_open(%s) failed fr=%d\r\n", path, fr); return false; }

    // Parse the .bit header to find the raw config data (field 'e').
    uint8_t hdr[256];
    UINT rd = 0;
    fr = f_read(&f, hdr, sizeof hdr, &rd);
    if (fr != FR_OK || rd < 64) {
        cdc_printf("fpga: %s header read failed (fr=%d rd=%u size=%lu)\r\n",
                   path, fr, rd, (unsigned long)f_size(&f));
        f_close(&f); return false;
    }
    uint32_t pos = 2 + (((uint32_t)hdr[0] << 8) | hdr[1]) + 2;   // initial field + 0x00 0x01
    uint32_t data_off = 0, data_len = 0;
    while (pos + 5 < rd) {
        uint8_t key = hdr[pos++];
        if (key == 'e') {
            data_len = ((uint32_t)hdr[pos] << 24) | ((uint32_t)hdr[pos+1] << 16) |
                       ((uint32_t)hdr[pos+2] << 8) | hdr[pos+3];
            pos += 4; data_off = pos; break;
        }
        pos += 2 + (((uint32_t)hdr[pos] << 8) | hdr[pos+1]);     // 'a'..'d': 2-byte len + string
    }
    if (data_len == 0 || f_lseek(&f, data_off) != FR_OK) {
        cdc_printf("fpga: %s: no bitstream data field\r\n", path);
        f_close(&f); return false;
    }
    cdc_printf("fpga: %s (%lu-byte .bit) -> shifting %lu config bytes over JTAG (~10s)...\r\n",
               path, (unsigned long)f_size(&f), (unsigned long)data_len);

    blaster_reset();               // init JTAG pins / PIO shift engine
    jtag_reset();
    scan_ir_x(XIR_JPROGRAM);
    sleep_ms(100);                 // INIT_B: config memory clear (generous for XC7A100T)
    tck_rti(10000);
    scan_ir_x(XIR_CFG_IN);
    enter_shift_dr();

    // Stream data_len bytes: all but the very last via shift commands (staying in
    // Shift-DR), the final byte bit-banged with TMS=1 to leave via Exit1-DR.
    static uint8_t buf[4096];
    uint32_t remaining = data_len;
    bool ok = true;
    while (remaining > 0) {
        UINT want = remaining < sizeof buf ? remaining : (UINT)sizeof buf;
        UINT got = 0;
        if (f_read(&f, buf, want, &got) != FR_OK || got == 0) { ok = false; break; }
        for (UINT i = 0; i < got; i++) buf[i] = reverse_byte(buf[i]);
        remaining -= got;
        if (remaining == 0) {                 // this chunk holds the final byte
            if (got > 1) shift_dr_bytes(buf, got - 1);
            shift_dr_last(buf[got - 1]);
        } else {
            shift_dr_bytes(buf, got);
        }
    }
    f_close(&f);
    if (!ok) { exit_shift_dr(); return false; }

    exit_shift_dr();
    scan_ir_x(XIR_JSTART);
    tck_rti(64);                    // startup sequence (>=32 TCK)
    scan_ir_x(XIR_BYPASS);
    tck_rti(64);
    jtag_reset();

    // DONE isn't directly readable via this blaster path; the real proof is the
    // Atari booting from the SPI link afterwards. Success here means the whole
    // bitstream streamed cleanly from SD.
    cdc_printf("fpga: config done (%lu bytes shifted)\r\n", (unsigned long)data_len);
    return true;
}
#endif // BOARD_WUKONG
