// Atari 800 supervisor firmware — RP2040-STAMP on the
// ATARI-800-QMTechCB-RP2040-STAMP-HDMI-LG board.
//
// Phase 1: USB HID keyboard -> FPGA over SPI.
//   * Native USB (USB0, stamp connector): CDC console (log + status).
//   * PIO-USB host on USB1 (D+ = GPIO7, D- = GPIO6, "top" of the stacked
//     connector pair; USB2 = GPIO8/9 is the fallback, see USE_USB2).
//   * Raw 8-byte HID boot reports are forwarded unmodified to the FPGA's
//     RpAtariKeyboard over hardware SPI0 (GPIO16 RX / 17 CSn / 18 SCK /
//     19 TX). All HID->Atari mapping lives in the FPGA.
//
// SPI frames (mode 0, MSB first, one frame per CS window):
//   'K' + 8-byte HID boot report     keyboard state (committed at CS rise)
//   'C' + control byte               bit0 reset, bit1 start, bit2 select,
//                                    bit3 option (held levels)
// MISO status: byte1 = 0xA5, byte2 = FPGA frame counter (link check).

#include <stdio.h>
#include <string.h>
#include <stdlib.h>
#include "pico/stdlib.h"
#include "hardware/clocks.h"
#include "hardware/spi.h"
#include "pio_usb.h"
#include "tusb.h"
#include "sd_spi.h"
#include "lib/fatfs/source/ff.h"
#include "config.h"
#include "sio.h"
#include "jtag.h"
#include "blaster.h"
#include "ft245_eeprom.h"
#include "fpga_config.h"
#include "supervisor.h"

// Bytes of FPGA core image (/fpga/core.rbf) staged into flash at boot (0 = none).
static uint32_t g_fpga_staged = 0;

// ---- Board wiring ----
#ifndef USE_USB2
#define USE_USB2 0            // 0: USB1 (D+=7, D-=6)  1: USB2 (D+=8, D-=9)
#endif
#define SPI_PORT spi0
#define PIN_SPI_RX  16        // FPGA_DO  (FPGA -> RP2040)
#define PIN_SPI_CSN 17        // FPGA_CSN (soft GPIO)
#define PIN_SPI_SCK 18        // FPGA_CLK
#define PIN_SPI_TX  19        // FPGA_DI  (RP2040 -> FPGA)

// Ring log: everything is also kept in a buffer so boot-time events (before
// the console attaches) can be replayed with the 'l' command.
static char logring[2048];
static uint16_t logring_w;

void cdc_printf(const char *fmt, ...) {
  char buf[224];
  va_list args;
  va_start(args, fmt);
  int n = vsnprintf(buf, sizeof buf, fmt, args);
  va_end(args);
  if (n <= 0) return;
  for (int i = 0; i < n; i++) {
    logring[logring_w] = buf[i];
    logring_w = (logring_w + 1) % sizeof logring;
  }
  if (tud_cdc_connected()) {
    // CDC TX FIFO is 64 bytes: pump the device task until the line is out
    int off = 0;
    absolute_time_t dl = make_timeout_time_ms(100);
    while (off < n && absolute_time_diff_us(get_absolute_time(), dl) > 0) {
      off += (int)tud_cdc_write(buf + off, (uint32_t)(n - off));
      tud_cdc_write_flush();
      tud_task();
    }
  }
}

static void dump_logring(void) {
  for (uint32_t i = 0; i < sizeof logring; i++) {
    char c = logring[(logring_w + i) % sizeof logring];
    if (c) { tud_cdc_write(&c, 1); if (i % 64 == 0) { tud_cdc_write_flush(); tud_task(); } }
  }
  tud_cdc_write_flush();
}

// ---- SPI link to the FPGA ----
static void fpga_spi_init(void) {
  spi_init(SPI_PORT, 1000 * 1000);           // 1 MHz, mode 0
  gpio_set_function(PIN_SPI_RX, GPIO_FUNC_SPI);
  gpio_set_function(PIN_SPI_SCK, GPIO_FUNC_SPI);
  gpio_set_function(PIN_SPI_TX, GPIO_FUNC_SPI);
  gpio_init(PIN_SPI_CSN);
  gpio_set_dir(PIN_SPI_CSN, GPIO_OUT);
  gpio_put(PIN_SPI_CSN, 1);
}

static void fpga_spi_frame(const uint8_t *tx, uint8_t *rx, size_t len) {
  gpio_put(PIN_SPI_CSN, 0);
  busy_wait_us(2);
  spi_write_read_blocking(SPI_PORT, tx, rx, len);
  busy_wait_us(2);
  gpio_put(PIN_SPI_CSN, 1);
  busy_wait_us(2);
}

static uint8_t last_fpga_frame_cnt;

static void fpga_send_keyboard(const uint8_t report[8]) {
  uint8_t tx[9] = {'K'};
  uint8_t rx[9];
  memcpy(tx + 1, report, 8);
  fpga_spi_frame(tx, rx, sizeof tx);
  last_fpga_frame_cnt = rx[1];   // MISO status: [0xA5][frameCnt][0]...
  // Link check: second MISO byte carries 0xA5 preloaded at CS fall
  if (rx[1] != 0xA5 && rx[0] != 0xA5) {
    cdc_printf("! SPI link status unexpected: %02x %02x %02x\r\n", rx[0], rx[1], rx[2]);
  }
}

// ---- SDRAM loader over the SPI link ----
// 'W' + addr[23:0] + data (quads written as they stream, little-endian).
// 'Z' zeroes the FPGA's byte counter + checksum; a 7-byte no-op frame reads
// back MISO status: [.., 0xA5?, frameCnt, cntL, cntH, sumL, sumH].
static void fpga_load_status(uint16_t *cnt, uint16_t *sum) {
  uint8_t tx[7] = {0}, rx[7];
  fpga_spi_frame(tx, rx, sizeof tx);
  *cnt = (uint16_t)rx[2] | ((uint16_t)rx[3] << 8);
  *sum = (uint16_t)rx[4] | ((uint16_t)rx[5] << 8);
}

static void fpga_load_zero(void) {
  uint8_t tx[1] = {'Z'}, rx[1];
  fpga_spi_frame(tx, rx, 1);
}

// One 'W' frame: up to 252 data bytes (multiple of 4).
static void fpga_load_chunk(uint32_t addr, const uint8_t *data, uint32_t len) {
  uint8_t tx[4 + 252], rx[sizeof tx];
  tx[0] = 'W';
  tx[1] = (uint8_t)(addr >> 16);
  tx[2] = (uint8_t)(addr >> 8);
  tx[3] = (uint8_t)addr;
  memcpy(tx + 4, data, len);
  fpga_spi_frame(tx, rx, 4 + len);
}

static void fpga_load(uint32_t addr, const uint8_t *data, uint32_t len,
                      uint16_t *local_cnt, uint16_t *local_sum) {
  for (uint32_t off = 0; off < len; off += 252) {
    uint32_t n = len - off > 252 ? 252 : len - off;
    n &= ~3u;                       // whole quads only
    if (n == 0) break;
    fpga_load_chunk(addr + off, data + off, n);
    for (uint32_t i = 0; i < n; i++) { *local_sum += data[off + i]; (*local_cnt)++; }
  }
}

// Set loader destination: 0 = SDRAM (port D), 1 = BRAM OS-ROM, 2 = BRAM RAM.
// BRAM-ROM 'W' addresses are ROM-SPACE: D800->0x1800, E000->0x2000.
static void fpga_set_dest(uint8_t dest) {
  uint8_t tx[2] = { 0x42, dest }, rx[2];
  fpga_spi_frame(tx, rx, sizeof tx);
}

// ---- SioBridge register access (see fpga_link.h) ----
void fpga_sio_write(uint8_t addr, uint16_t data) {
  uint8_t tx[4] = { 0x51, (uint8_t)(addr & 0x0F),
                    (uint8_t)(data & 0xFF), (uint8_t)(data >> 8) };
  uint8_t rx[4];
  fpga_spi_frame(tx, rx, sizeof tx);
}

uint16_t fpga_sio_read(uint8_t addr) {
  // Step 1: 'S' triggers the bus read + latch (and any FIFO/sticky side effect).
  uint8_t tx1[2] = { 0x53, (uint8_t)(addr & 0x0F) }, rx1[2];
  fpga_spi_frame(tx1, rx1, sizeof tx1);
  // Step 2: zero-command status frame returns the latch in MISO bytes 25/26.
  uint8_t tx2[27] = {0}, rx2[27];
  fpga_spi_frame(tx2, rx2, sizeof tx2);
  return (uint16_t)rx2[25] | ((uint16_t)rx2[26] << 8);
}

// 'V' frame: FPGA reads len bytes at addr (byte mode - the CPU's view) and
// sums them; poll status bytes 6..8 for the result. Returns true on match.
static bool fpga_verify_content(uint32_t addr, uint32_t len, uint16_t want) {
  uint8_t tx[8] = { 0x56,
    (uint8_t)(addr >> 16), (uint8_t)(addr >> 8), (uint8_t)addr,
    (uint8_t)(len >> 16),  (uint8_t)(len >> 8),  (uint8_t)len, 0 };
  uint8_t rx[10];
  fpga_spi_frame(tx, rx, sizeof tx);
  uint8_t st[12] = {0};
  for (int i = 0; i < 400; i++) {          // 8 KB at ~1 us/byte: well under 100 ms
    sleep_ms(5);
    uint8_t z[12] = {0};
    fpga_spi_frame(z, st, sizeof z);
    if ((st[8] & 1) == 0) break;
  }
  uint16_t got = st[6] | (st[7] << 8);
  cdc_printf("verify %06lx+%lu: content sum %04x %s %04x | drained %u ovf %u\r\n",
             addr, len, got, got == want ? "==" : "!=", want,
             st[9] | (st[10] << 8), (st[8] >> 1) & 1);
  return got == want;
}

void fpga_send_control(uint8_t bits) {
  uint8_t tx[2] = {'C', bits};
  uint8_t rx[2];
  fpga_spi_frame(tx, rx, sizeof tx);
}

// Capture-window offset that centres the picture in the framebuffer. Measured
// against the standard Atari playfield origin (fixed across graphics modes):
// hStart shifts content right, vSkip drops top-border lines. These are the
// default values, applied at startup and on every boot; a future SD config
// file (e.g. /config.txt) will override them by writing new values here.
#define FB_HSTART 4
#define FB_VSKIP  21

static uint8_t g_fb_hstart = FB_HSTART;
static uint8_t g_fb_vskip  = FB_VSKIP;

static void fpga_set_offset(uint8_t hstart, uint8_t vskip) {
  uint8_t tx[3] = {'G', hstart, vskip};
  uint8_t rx[3];
  fpga_spi_frame(tx, rx, sizeof tx);
}

// Cartridge select: CartLogic mode (0 = none, 0x01 = 8K, 0x21 = 16K, ...).
// The holding register is in the BOOT-reset cfgArea, so it survives the reset
// that boots the cart. The cart + mode come from the SD config (see config.c).

static void fpga_set_cart(uint8_t mode) {
  uint8_t tx[2] = {'X', mode};
  uint8_t rx[2];
  fpga_spi_frame(tx, rx, sizeof tx);
}

static void fpga_set_phase(uint8_t phase) {
  uint8_t tx[2] = {'P', (uint8_t)(phase & 7)};
  uint8_t rx[2];
  fpga_spi_frame(tx, rx, sizeof tx);
}

// Bit-banged K frame at ~20 kHz: same wire protocol, glacial timing.
// Splits "SPI speed/format problem" from "FPGA receive logic problem".
static void fpga_bitbang_key_a(void) {
  const uint8_t frame[9] = {'K', 0, 0, 0x04, 0, 0, 0, 0, 0};  // 'a' pressed
  gpio_set_function(PIN_SPI_SCK, GPIO_FUNC_SIO);
  gpio_set_function(PIN_SPI_TX, GPIO_FUNC_SIO);
  gpio_set_dir(PIN_SPI_SCK, GPIO_OUT);
  gpio_set_dir(PIN_SPI_TX, GPIO_OUT);
  gpio_put(PIN_SPI_SCK, 0);
  gpio_put(PIN_SPI_CSN, 0);
  busy_wait_us(50);
  for (int b = 0; b < 9; b++) {
    for (int bit = 7; bit >= 0; bit--) {
      gpio_put(PIN_SPI_TX, (frame[b] >> bit) & 1);
      busy_wait_us(25);
      gpio_put(PIN_SPI_SCK, 1);
      busy_wait_us(25);
      gpio_put(PIN_SPI_SCK, 0);
    }
  }
  busy_wait_us(50);
  gpio_put(PIN_SPI_CSN, 1);
  busy_wait_us(50);
  gpio_set_function(PIN_SPI_SCK, GPIO_FUNC_SPI);
  gpio_set_function(PIN_SPI_TX, GPIO_FUNC_SPI);
}

// ---- CDC console commands (single letters) ----

// Stream one file from SD to a load destination, retrying on checksum mismatch.
// dest: 1 = BRAM OS-ROM (rom-space addr), 2 = BRAM RAM (cart). Returns bytes
// streamed on success, or -1 after 3 failed attempts / open failure.
static long stream_file(const char *path, uint32_t addr, uint8_t dest,
                        const char *what) {
  for (int attempt = 0; attempt < 3; attempt++) {
    FIL f;
    if (f_open(&f, path, FA_READ) != FR_OK) {
      cdc_printf("boot: open %s failed\r\n", path);
      return -1;
    }
    fpga_set_dest(dest);
    fpga_load_zero();
    uint16_t lcnt = 0, lsum = 0; uint32_t total = 0;
    static uint8_t buf[504]; UINT rd;
    while (f_read(&f, buf, sizeof buf, &rd) == FR_OK && rd > 0) {
      fpga_load(addr + total, buf, rd, &lcnt, &lsum);
      total += rd; tud_task();
    }
    f_close(&f);
    uint16_t fcnt, fsum; fpga_load_status(&fcnt, &fsum);
    bool match = (lcnt == fcnt && lsum == fsum);
    cdc_printf("boot: %s %s -> %s %04lx (%lu bytes) stream %s\r\n", what, path,
               dest == 1 ? "ROM-BRAM" : "RAM-BRAM", addr, total,
               match ? "ok" : "CHECKSUM MISMATCH");
    if (match) return (long)total;       // BRAM has no read-back; trust stream sum
    cdc_printf("boot: retrying %s\r\n", path);
  }
  return -1;
}

// Load the OS + cart described by `cfg` into blank BRAM, mount its disk images
// for the SIO emu, and reset the Atari to run. Assumes the SD is mounted. Used
// by both the config default (do_boot) and the supervisor's live selection.
void boot_run(const boot_config_t *cfg) {
  fpga_send_control(0x10);               // HALT the 6502: quiet the bus during load

  bool ok = (cfg->osCount > 0);
  for (int i = 0; i < cfg->osCount && ok; i++)
    ok = stream_file(cfg->os[i].path, cfg->os[i].romAddr, 1, "os") >= 0;

  uint8_t cartMode = 0;
  if (ok && cfg->hasCart) {
    if (stream_file(cfg->cartPath, cfg->cartAddr, 2, "cart") >= 0)
      cartMode = cfg->cartMode;          // enable emuCart RD5 -> OS boots it from BRAM
    else
      cdc_printf("boot: cart load failed - memo pad\r\n");
  } else if (ok) {
    cdc_printf("boot: no cart - memo pad / disk boot\r\n");
  }
  fpga_set_dest(0);

  // Mount disk images for the SIO drive emulator (D1: = slot 0).
  sio_unmount_all();
  for (int i = 0; i < CFG_MAX_DISKS; i++) {
    if (!cfg->diskPath[i][0]) continue;
    if (sio_mount(i, cfg->diskPath[i]))
      cdc_printf("boot: D%d: mounted %s\r\n", i + 1, cfg->diskPath[i]);
    else
      cdc_printf("boot: D%d: mount FAILED %s\r\n", i + 1, cfg->diskPath[i]);
  }

  if (ok) {
    cdc_printf("boot: loaded, resetting Atari (cart mode %02x)\r\n", cartMode);
    fpga_set_offset(g_fb_hstart, g_fb_vskip);
    fpga_set_cart(cartMode);             // 0 = memo pad; else boot the cart
    fpga_send_control(0x11);             // release halt via reset
    fpga_send_control(0x00);
  } else {
    fpga_set_cart(0);
    fpga_send_control(0x00);
    cdc_printf("boot: load errors - not resetting\r\n");
  }
}

// Boot from the SD config: mount, parse /config.json + machine config, run.
// Called automatically at power-on and by the console 'B' command.
static void do_boot(void) {
  static FATFS fs;
  if (f_mount(&fs, "", 1) != FR_OK) {
    cdc_printf("boot: SD mount failed\r\n"); fpga_send_control(0x00); return;
  }
  boot_config_t cfg;
  if (!config_load(&cfg)) {
    cdc_printf("boot: config load failed (/config.json + machine config)\r\n");
    fpga_send_control(0x00);
    return;
  }
  cdc_printf("boot: config ok - %d OS block(s), cart %s, %d disk(s)\r\n",
             cfg.osCount, cfg.hasCart ? "yes" : "no", cfg.diskCount);
  boot_run(&cfg);
}

static void handle_console(void) {
  if (!tud_cdc_available()) return;
  uint8_t ch;
  if (tud_cdc_read(&ch, 1) != 1) return;
  // While the supervisor menu is up, console keys drive it (mirrors the USB
  // keyboard). '~' opens it from a bare serial terminal (Alt-F12 is the USB-
  // keyboard hotkey).
  if (sup_active()) { sup_feed_key((char)ch); return; }
  if (ch == '~') { sup_open(); return; }
  switch (ch) {
    case 'r': cdc_printf("reset pulse\r\n"); fpga_send_control(0x01); fpga_send_control(0x00); break;
    case 'R': cdc_printf("reset HELD (0 to release)\r\n"); fpga_send_control(0x01); break;
    case '1': cdc_printf("start held\r\n");  fpga_send_control(0x02); break;
    case '0': cdc_printf("controls released\r\n"); fpga_send_control(0x00); break;
    case 's': {
      uint8_t tx[3] = {'K', 0, 0}, rx[3];
      fpga_spi_frame(tx, rx, sizeof tx);
      cdc_printf("status: %02x %02x %02x (frames=%u)\r\n", rx[0], rx[1], rx[2], last_fpga_frame_cnt);
      break;
    }
    case 'k': {
      fpga_bitbang_key_a();
      uint8_t tx[3] = {'K', 0, 0}, rx[3];
      fpga_spi_frame(tx, rx, sizeof tx);
      cdc_printf("bitbang K sent; status now: %02x %02x %02x\r\n", rx[0], rx[1], rx[2]);
      break;
    }
    case 'i': {   // SD card init + info
      if (!sd_card_present()) { cdc_printf("sd: no card detected (CD high)\r\n"); break; }
      int r = sd_init();
      if (r != 0) { cdc_printf("sd: init failed (%d)\r\n", r); break; }
      uint32_t blocks = sd_capacity_blocks();
      cdc_printf("sd: %s, %lu blocks (%lu MB)\r\n",
                 sd_sdhc() ? "SDHC/XC" : "SDSC", blocks, blocks / 2048);
      break;
    }
    case 'd': {   // mount + list root and /cartridge
      static FATFS fs;
      FRESULT fr = f_mount(&fs, "", 1);
      if (fr != FR_OK) { cdc_printf("sd: f_mount failed (%d)\r\n", fr); break; }
      const char *dirs[] = { "/", "/cartridge" };
      for (unsigned di = 0; di < 2; di++) {
        DIR dir; FILINFO fno;
        fr = f_opendir(&dir, dirs[di]);
        if (fr != FR_OK) { cdc_printf("sd: opendir %s failed (%d)\r\n", dirs[di], fr); continue; }
        cdc_printf("== %s ==\r\n", dirs[di]);
        int n = 0;
        while (f_readdir(&dir, &fno) == FR_OK && fno.fname[0]) {
          cdc_printf("  %s%-40s %lu\r\n", (fno.fattrib & AM_DIR) ? "/" : " ",
                     fno.fname, (unsigned long)fno.fsize);
          if (++n >= 64) break;
          tud_task();
        }
        f_closedir(&dir);
      }
      cdc_printf("sd: entries listed\r\n");
      break;
    }
    case 'B': do_boot(); break;   // manual re-boot (also runs automatically at power-on)
    case 'D': sio_stats_print(); break;   // SIO disk-drive activity counters
    case 'J': jtag_idcode_print(); break; // JTAG bring-up: read FPGA IDCODE (GPIO0-3 -> J10)
    case 'F': {   // configure the FPGA from the staged /fpga/core.rbf (SD-side load)
      uint32_t n = fpga_staged_len();
      if (n == 0) { cdc_printf("fpga: no staged core (put /fpga/core.rbf on SD, reset to stage)\r\n"); break; }
      cdc_printf("fpga: configuring from staged %lu-byte .rbf ...\r\n", (unsigned long)n);
      tud_cdc_write_flush(); tud_task();
      bool ok = fpga_config_from_flash();
      cdc_printf("fpga: CONF_DONE=%d %s\r\n", ok, ok ? "-- configured (run 'B' to boot the Atari)" : "-- FAILED");
      break;
    }
    case 'w': {   // SDRAM load channel self-test: 1 KB pattern @ 0x300000
      static uint8_t pat[1024];
      for (int i = 0; i < 1024; i++) pat[i] = (uint8_t)(i * 7 + 3);
      fpga_load_zero();
      uint16_t lcnt = 0, lsum = 0;
      // covers the OS vector area: 0x387FFC = pat[0x3FC] = 0xE7, FFFD -> 0xEE
      fpga_load(0x387C00, pat, sizeof pat, &lcnt, &lsum);
      uint16_t fcnt, fsum;
      fpga_load_status(&fcnt, &fsum);
      cdc_printf("loadtest: local cnt=%u sum=%04x | fpga cnt=%u sum=%04x -> %s\r\n",
                 lcnt, lsum, fcnt, fsum,
                 (lcnt == fcnt && lsum == fsum) ? "PASS" : "FAIL");
      break;
    }
    case 'L': {   // L <hexaddr> <path>  — load a file from SD into SDRAM
      char line[96]; int n = 0;
      absolute_time_t dl = make_timeout_time_ms(500);
      while (n < 95 && absolute_time_diff_us(get_absolute_time(), dl) > 0) {
        tud_task();
        uint8_t c;
        if (tud_cdc_available() && tud_cdc_read(&c, 1) == 1) {
          if (c == '\r' || c == '\n') break;
          line[n++] = (char)c;
          dl = make_timeout_time_ms(500);
        }
      }
      line[n] = 0;
      uint32_t addr = 0; char path[80] = {0};
      if (sscanf(line, " %lx %79s", &addr, path) != 2) {
        cdc_printf("usage: L <hexaddr> <path>   e.g. L 300000 /cartridge/foo.rom\r\n");
        break;
      }
      static FATFS fs;
      if (f_mount(&fs, "", 1) != FR_OK) { cdc_printf("mount failed\r\n"); break; }
      FIL f;
      FRESULT fr = f_open(&f, path, FA_READ);
      if (fr != FR_OK) { cdc_printf("open '%s' failed (%d)\r\n", path, fr); break; }
      fpga_load_zero();
      uint16_t lcnt = 0, lsum = 0;
      uint32_t total = 0;
      static uint8_t buf[504];        // multiple of 4 and of 252
      UINT rd;
      while (f_read(&f, buf, sizeof buf, &rd) == FR_OK && rd > 0) {
        fpga_load(addr + total, buf, rd, &lcnt, &lsum);
        total += rd;
        tud_task();
      }
      f_close(&f);
      uint16_t fcnt, fsum;
      fpga_load_status(&fcnt, &fsum);
      cdc_printf("loaded '%s': %lu bytes @ %06lx | fpga cnt=%u sum=%04x local cnt=%u sum=%04x -> %s\r\n",
                 path, total, addr, fcnt, fsum, lcnt, lsum,
                 (lcnt == fcnt && lsum == fsum) ? "OK" : "MISMATCH");
      break;
    }
    case 'x': {   // x <hexaddr> : dump 16 SDRAM bytes (as the CPU sees them)
      char line[32]; int n = 0;
      absolute_time_t dl = make_timeout_time_ms(500);
      while (n < 31 && absolute_time_diff_us(get_absolute_time(), dl) > 0) {
        tud_task(); uint8_t c;
        if (tud_cdc_available() && tud_cdc_read(&c, 1) == 1) {
          if (c == '\r' || c == '\n') break;
          line[n++] = (char)c; dl = make_timeout_time_ms(500);
        }
      }
      line[n] = 0;
      uint32_t addr = 0;
      if (sscanf(line, " %lx", &addr) != 1) { cdc_printf("usage: x <hexaddr>\r\n"); break; }
      uint8_t tx[4 + 20] = {'R', (uint8_t)(addr >> 16), (uint8_t)(addr >> 8), (uint8_t)addr};
      uint8_t rx[sizeof tx];
      fpga_spi_frame(tx, rx, sizeof tx);
      cdc_printf("%06lx:", addr);
      for (int i = 6; i < 22; i++) cdc_printf(" %02x", rx[i]);   // pipeline skip
      cdc_printf("\r\n");
      break;
    }
    case 'g': {   // read FPGA debug GPIOs (22 = sticky OS-region fetch)
      gpio_init(22); gpio_set_dir(22, GPIO_IN);
      gpio_init(15); gpio_set_dir(15, GPIO_IN);
      int t0 = gpio_get(15);
      sleep_ms(50);
      (void)t0;
      uint8_t z[26] = {0}, st[26];
      fpga_spi_frame(z, st, sizeof z);
      cdc_printf("fb meters: late=%u drop=%u | portA maxStall=%u cyc (since config)\r\n",
                 st[11] | (st[12] << 8), st[13] | (st[14] << 8), st[23] | (st[24] << 8));
      break;
    }
    case 'm': case 'M': {  // SDRAM BIST status (sdram_test bitstream); 'M' restarts
      uint8_t tx[24] = {0}, rx[24];
      if (ch == 'M') {
        tx[0] = 0xA0;
        fpga_spi_frame(tx, rx, sizeof tx);
        cdc_printf("bist: restart sent\r\n");
        tx[0] = 0;
        sleep_ms(200);
      }
      for (int i = 0; i < 120; i++) {          // up to ~60 s
        fpga_spi_frame(tx, rx, sizeof tx);
        if (rx[0] != 0xB5 || rx[22] != 0x5A) {
          cdc_printf("bist: bad frame (%02x...%02x) - is sdram_test.sof loaded?\r\n", rx[0], rx[22]);
          break;
        }
        uint32_t prog = rx[3] | (rx[4] << 8) | (rx[5] << 16) | ((uint32_t)rx[6] << 24);
        uint16_t errs = rx[7] | (rx[8] << 8);
        if (rx[1] == 0) {
          cdc_printf("bist: RUNNING phase %u addr %07lx errs %u\r\n", rx[2], prog, errs);
        } else {
          uint32_t fa = rx[9] | (rx[10] << 8) | (rx[11] << 16) | ((uint32_t)rx[12] << 24);
          uint32_t fg = rx[13] | (rx[14] << 8) | (rx[15] << 16) | ((uint32_t)rx[16] << 24);
          uint32_t fe = rx[17] | (rx[18] << 8) | (rx[19] << 16) | ((uint32_t)rx[20] << 24);
          if (rx[1] == 1) cdc_printf("bist: PASS (errs %u)\r\n", errs);
          else cdc_printf("bist: FAIL errs %u | first: phase %u addr %07lx got %08lx exp %08lx\r\n",
                          errs, rx[21], fa, fg, fe);
          break;
        }
        if (ch == 'm') break;                   // single poll for lowercase
        sleep_ms(500);
      }
      break;
    }
    case 'v': {   // re-verify loaded os2 twice + dump first 32 bytes vs file
      fpga_send_control(0x10);
      fpga_verify_content(0x00D800, 2048, 0x11e1);
      fpga_verify_content(0x00D800, 2048, 0x11e1);
      uint8_t mem[32];
      for (int i = 0; i < 32; i++) {   // V len=1 = single-byte peek
        uint8_t tx[8] = { 0x56, 0x00, 0xD8, (uint8_t)i, 0, 0, 1, 0 };
        uint8_t rx[10];
        fpga_spi_frame(tx, rx, sizeof tx);
        sleep_ms(2);
        uint8_t z[10] = {0}, st[10];
        fpga_spi_frame(z, st, sizeof z);
        mem[i] = st[6];
      }
      fpga_send_control(0x00);
      static FATFS fs; FIL f; UINT rd; uint8_t fb2[32] = {0};
      if (f_mount(&fs, "", 1) == FR_OK && f_open(&f, "/atari/800/os/atarios2.rom", FA_READ) == FR_OK) {
        f_read(&f, fb2, 32, &rd); f_close(&f);
      }
      for (int r = 0; r < 2; r++) {
        cdc_printf("sdram %06x:", 0x00D800 + r * 16);
        for (int i = 0; i < 16; i++) cdc_printf(" %02x", mem[r * 16 + i]);
        cdc_printf("\r\n  file %06x:", r * 16);
        for (int i = 0; i < 16; i++) cdc_printf(" %02x", fb2[r * 16 + i]);
        cdc_printf("\r\n");
      }
      break;
    }
    case 'y': {   // y <hexaddr>: 64-byte hexdump via V single-byte peeks
      char line[32]; int n = 0;
      absolute_time_t dl = make_timeout_time_ms(500);
      while (n < 31 && absolute_time_diff_us(get_absolute_time(), dl) > 0) {
        tud_task(); uint8_t c2;
        if (tud_cdc_available() && tud_cdc_read(&c2, 1) == 1) {
          if (c2 == '\r' || c2 == '\n') break;
          line[n++] = (char)c2; dl = make_timeout_time_ms(500);
        }
      }
      line[n] = 0;
      uint32_t a = strtoul(line, NULL, 16);
      for (int r = 0; r < 4; r++) {
        cdc_printf("%06lx:", a + r * 16);
        for (int i = 0; i < 16; i++) {
          uint32_t p = a + r * 16 + i;
          uint8_t tx[8] = { 0x56, (uint8_t)(p >> 16), (uint8_t)(p >> 8), (uint8_t)p, 0, 0, 1, 0 };
          uint8_t rx[10];
          fpga_spi_frame(tx, rx, sizeof tx);
          sleep_ms(1);
          uint8_t z[10] = {0}, st[10];
          fpga_spi_frame(z, st, sizeof z);
          cdc_printf(" %02x", st[6]);
        }
        cdc_printf("\r\n");
      }
      break;
    }
    case 'o': {   // o <hStart> <vSkip> : set capture-window offset (live, no rebuild)
      char line[32]; int n = 0;
      absolute_time_t dl = make_timeout_time_ms(500);
      while (n < 31 && absolute_time_diff_us(get_absolute_time(), dl) > 0) {
        tud_task(); uint8_t c2;
        if (tud_cdc_available() && tud_cdc_read(&c2, 1) == 1) {
          if (c2 == '\r' || c2 == '\n') break;
          line[n++] = (char)c2; dl = make_timeout_time_ms(500);
        }
      }
      line[n] = 0;
      char *sp = line;
      g_fb_hstart = (uint8_t)strtoul(sp, &sp, 10);
      g_fb_vskip  = (uint8_t)strtoul(sp, &sp, 10);
      fpga_set_offset(g_fb_hstart, g_fb_vskip);
      cdc_printf("capture offset set: hStart=%d vSkip=%d\r\n", g_fb_hstart, g_fb_vskip);
      break;
    }
    case 'G': {   // report the live-content bounding box inside the framebuffer
      uint8_t z[24] = {0}, st[24];
      fpga_spi_frame(z, st, sizeof z);
      // status stream byte 0 is the 0xA5 magic, so field is(N) lands at byte N+1
      int minX = st[15] | (st[16] << 8), maxX = st[17] | (st[18] << 8);
      int minY = st[19] | (st[20] << 8), maxY = st[21] | (st[22] << 8);
      cdc_printf("fb content bbox: x=%d..%d (w=%d)  y=%d..%d (h=%d)  buffer 384x288\r\n",
                 minX, maxX, maxX - minX + 1, minY, maxY, maxY - minY + 1);
      cdc_printf("  left margin=%d right=%d  top=%d bottom=%d\r\n",
                 minX, 383 - maxX, minY, 287 - maxY);
      break;
    }
    case 'P': {   // P <n> : set capture pixel-sample phase 0..7 (sweep to kill speckle)
      char line[16]; int n = 0;
      absolute_time_t dl = make_timeout_time_ms(500);
      while (n < 15 && absolute_time_diff_us(get_absolute_time(), dl) > 0) {
        tud_task(); uint8_t c2;
        if (tud_cdc_available() && tud_cdc_read(&c2, 1) == 1) {
          if (c2 == '\r' || c2 == '\n') break;
          line[n++] = (char)c2; dl = make_timeout_time_ms(500);
        }
      }
      line[n] = 0;
      uint8_t ph = (uint8_t)(strtoul(line, NULL, 10) & 7);
      fpga_set_phase(ph);
      cdc_printf("pixel sample phase = %u\r\n", ph);
      break;
    }
    case 'l': dump_logring(); break;
    case 'h':
    default:
      cdc_printf("supervisor: B=boot-os r=reset 1=start 0=release s=status i=sdinfo d=sddir w=loadtest L=<addr> <path> l=bootlog h=help\r\n");
      break;
  }
}

// Generic device mount/unmount — fires for ANY device (incl. hubs), telling
// us whether anything connects electrically even if HID never binds.
void tuh_mount_cb(uint8_t dev_addr) {
  uint16_t vid = 0, pid = 0;
  tuh_vid_pid_get(dev_addr, &vid, &pid);
  cdc_printf("USB device mounted addr=%u vid=%04x pid=%04x\r\n", dev_addr, vid, pid);
}
void tuh_umount_cb(uint8_t dev_addr) {
  cdc_printf("USB device unmounted addr=%u\r\n", dev_addr);
}

// ---- TinyUSB host callbacks ----
void tuh_hid_mount_cb(uint8_t dev_addr, uint8_t instance, uint8_t const *desc_report, uint16_t desc_len) {
  (void)desc_report; (void)desc_len;
  uint8_t const itf_protocol = tuh_hid_interface_protocol(dev_addr, instance);
  cdc_printf("HID mounted addr=%u inst=%u proto=%u (%s)\r\n", dev_addr, instance,
             itf_protocol, itf_protocol == HID_ITF_PROTOCOL_KEYBOARD ? "keyboard" : "other");
  // Request reports from EVERY HID instance: many keyboards use report
  // protocol (proto=NONE) rather than the boot interface.
  bool ok = tuh_hid_receive_report(dev_addr, instance);
  cdc_printf("  receive_report(%u,%u) -> %d\r\n", dev_addr, instance, ok);
}

void tuh_hid_umount_cb(uint8_t dev_addr, uint8_t instance) {
  cdc_printf("HID unmounted addr=%u inst=%u\r\n", dev_addr, instance);
  uint8_t empty[8] = {0};
  fpga_send_keyboard(empty);   // release all keys
}

void tuh_hid_report_received_cb(uint8_t dev_addr, uint8_t instance, uint8_t const *report, uint16_t len) {
  uint8_t const proto = tuh_hid_interface_protocol(dev_addr, instance);
  if (len >= 8 && (proto == HID_ITF_PROTOCOL_KEYBOARD || proto == HID_ITF_PROTOCOL_NONE)) {
    // Supervisor hotkey/menu gets first look; if it consumes the report
    // (Alt-F12, or any key while the menu is up) it never reaches the Atari.
    if (sup_hid_report(report)) {
      tuh_hid_receive_report(dev_addr, instance);
      return;
    }
    fpga_send_keyboard(report);
    cdc_printf("key p%u: %02x [%02x %02x %02x %02x %02x %02x] fpga=%u\r\n",
               proto, report[0], report[2], report[3], report[4], report[5], report[6], report[7],
               last_fpga_frame_cnt);
  } else {
    cdc_printf("hid p%u len=%u: %02x %02x %02x...\r\n", proto, len,
               len > 0 ? report[0] : 0, len > 1 ? report[1] : 0, len > 2 ? report[2] : 0);
  }
  tuh_hid_receive_report(dev_addr, instance);
}

#ifndef BISECT_CDC_ONLY
#define BISECT_CDC_ONLY 0
#endif

#ifndef BISECT_NO_CLOCK
#define BISECT_NO_CLOCK 0
#endif

// ---- USB-Blaster (FTDI) emulation ----------------------------------------
// Ported from ~/pico-usb-blaster (MIT). Active only while g_blaster_mode is set
// (the device then enumerates as 09fb:6001, see usb_descriptors.c). jtagd /
// quartus_pgm drive JTAG through the vendor bulk endpoints and probe the device
// via FTDI vendor control requests, which we answer here.

// FTDI vendor request codes
#define FTDI_SIO_RESET             0x00
#define FTDI_SIO_MODEM_CTRL        0x01
#define FTDI_SIO_SET_FLOW_CTRL     0x02
#define FTDI_SIO_SET_BAUD_RATE     0x03
#define FTDI_SIO_SET_DATA          0x04
#define FTDI_SIO_GET_MODEM_STATUS  0x05
#define FTDI_SIO_SET_LATENCY_TIMER 0x09
#define FTDI_SIO_GET_LATENCY_TIMER 0x0A
#define FTDI_SIO_READ_EEPROM       0x90

// FTDI modem/line status; also the 2-byte header prepended to every bulk IN.
#define FTDI_MODEM_STATUS 0x31
#define FTDI_LINE_STATUS  0x60

static uint8_t ftdi_latency_timer = 2;   // ms

static bool handle_vendor_in_request(uint8_t rhport, tusb_control_request_t const *request) {
  uint8_t response[2] = {0};
  uint16_t resp_length;

  switch (request->bRequest) {
    case FTDI_SIO_GET_MODEM_STATUS:
      response[0] = FTDI_MODEM_STATUS; response[1] = FTDI_LINE_STATUS;
      resp_length = (request->wLength < 2) ? request->wLength : 2;
      break;
    case FTDI_SIO_GET_LATENCY_TIMER:
      response[0] = ftdi_latency_timer; resp_length = 1;
      break;
    case FTDI_SIO_READ_EEPROM: {
      uint16_t address = request->wIndex * 2;
      if ((address + 1) < FT245_EEPROM_LENGTH) {
        response[0] = FT245_EEPROM[address]; response[1] = FT245_EEPROM[address + 1];
      }
      resp_length = (request->wLength < 2) ? request->wLength : 2;
      break;
    }
    default:
      response[0] = FTDI_MODEM_STATUS; response[1] = FTDI_LINE_STATUS;
      resp_length = (request->wLength < 2) ? request->wLength : 2;
      break;
  }

  tud_control_xfer(rhport, request, response, resp_length);
  return true;
}

static bool handle_vendor_out_request(uint8_t rhport, tusb_control_request_t const *request) {
  switch (request->bRequest) {
    case FTDI_SIO_RESET:              blaster_reset(); break;
    case FTDI_SIO_SET_LATENCY_TIMER:  ftdi_latency_timer = request->wValue & 0xFF; break;
    case FTDI_SIO_MODEM_CTRL:
    case FTDI_SIO_SET_FLOW_CTRL:
    case FTDI_SIO_SET_BAUD_RATE:
    case FTDI_SIO_SET_DATA:           break;   // ACK silently
    default:                          break;
  }
  if (request->wLength > 0) tud_control_xfer(rhport, request, NULL, 0);
  else                      tud_control_status(rhport, request);
  return true;
}

bool tud_vendor_control_xfer_cb(uint8_t rhport, uint8_t stage, tusb_control_request_t const *request) {
  if (stage != CONTROL_STAGE_SETUP) return true;
  if (request->bmRequestType_bit.direction == TUSB_DIR_IN)
    return handle_vendor_in_request(rhport, request);
  return handle_vendor_out_request(rhport, request);
}

// Pump the Blaster bulk endpoints: read OUT protocol bytes, drive JTAG, and
// return any read-back bytes as FT245 packets (2-byte status header + payload).
static void blaster_vendor_task(void) {
  static uint32_t prev_tx_ms = 0;
  static uint8_t  tx_buf[2 + 64 * 2] = { FTDI_MODEM_STATUS, FTDI_LINE_STATUS };
  static int      tx_ready = 0;

  if (!tud_mounted()) { tx_ready = 0; return; }

  while (tud_vendor_available() && tx_ready <= 64) {
    uint8_t buf[64];
    uint32_t count = tud_vendor_read(buf, sizeof(buf));
    tx_ready += blaster_process(buf, (int)count, tx_buf + 2 + tx_ready);
  }

  uint32_t now = to_ms_since_boot(get_absolute_time());
  if (tx_ready > 0 || (now - prev_tx_ms) >= ftdi_latency_timer) {
    int txCount = tx_ready > 62 ? 62 : tx_ready;
    // Only write once the previous IN packet has drained, so each USB transfer
    // carries exactly one FT245 status header (see pico-usb-blaster notes).
    if (tud_vendor_write_available() < CFG_TUD_VENDOR_TX_BUFSIZE) return;
    tud_vendor_write(tx_buf, txCount + 2);
    tud_vendor_write_flush();
    prev_tx_ms = now;
    tx_ready -= txCount;
    if (tx_ready > 0) memcpy(tx_buf + 2, tx_buf + 2 + txCount, tx_ready);
  }
}

int main(void) {
#if !BISECT_CDC_ONLY && !BISECT_NO_CLOCK
  set_sys_clock_khz(120000, true);   // PIO-USB officially supports 120 MHz
#endif

  // Pre-USB (single-core -> RP2040 flash writes are safe): stage the FPGA core
  // image from SD into flash IF it changed, so it can be JTAG-loaded later
  // independent of the SD card (which is offline during reconfiguration). Only
  // rewrites flash when the SD file's size/mtime differ -> no wear per boot.
  {
    static FATFS stagefs;
    if (sd_card_present() && sd_init() == 0 && f_mount(&stagefs, "", 1) == FR_OK) {
      g_fpga_staged = fpga_stage_if_changed("/fpga/core.rbf");
      f_mount(0, "", 0);
    }
    cdc_printf("fpga: staged core = %lu bytes%s\r\n", (unsigned long)g_fpga_staged,
               g_fpga_staged ? " (console 'F' to configure)" : " (none)");
  }

  // PIO-USB host on rhport 1
  pio_usb_configuration_t pio_cfg = PIO_USB_DEFAULT_CONFIG;
#if USE_USB2
  pio_cfg.pin_dp = 8;                       // USB2: D+=8, D-=9
  pio_cfg.pinout = PIO_USB_PINOUT_DPDM;
#else
  pio_cfg.pin_dp = 7;                       // USB1: D+=7, D-=6
  pio_cfg.pinout = PIO_USB_PINOUT_DMDP;
#endif
#if !BISECT_CDC_ONLY
  tuh_configure(1, TUH_CFGID_RPI_PIO_USB_CONFIGURATION, &pio_cfg);
#endif

  tud_init(0);   // device (CDC console) on native USB
#if !BISECT_CDC_ONLY
  tuh_init(1);   // host (keyboard) on PIO-USB
#endif

  fpga_spi_init();
  fpga_send_control(0x00);
  fpga_set_offset(g_fb_hstart, g_fb_vskip);   // centre the picture by default
  blaster_reset();   // idle the JTAG pins (GPIO0-3); ready for Quartus to program

  // Cold-boot re-enumeration. tud_init() above asserts the USB pull-up and
  // presents the device before tuh_init()/fpga_spi_init() run, but those run
  // without pumping tud_task() — so a host that begins enumerating in that
  // window gets no answer to its control transfers and gives up until a
  // physical replug. Now that everything is initialized and the main loop
  // (which pumps tud_task promptly) is about to start, drop and re-assert the
  // pull-up: the host sees a fresh connect and enumerates against a stack that
  // responds immediately. This is what replugging did, done automatically.
  tud_disconnect();
  sleep_ms(120);
  tud_connect();

  // Auto-boot: load the OS + default cart from SD into blank BRAM and run.
  // Same sequence as the console 'B' command; 'B' re-runs it on demand.
  do_boot();

  absolute_time_t next_beat = make_timeout_time_ms(3000);
  while (true) {
    tud_task();
    blaster_vendor_task();               // service Quartus JTAG programming (idle-cheap)
#if !BISECT_CDC_ONLY
    tuh_task();
#endif
    handle_console();
    if (sio_any_mounted()) sio_poll();   // service SIO disk commands (D1:..)
    if (absolute_time_diff_us(get_absolute_time(), next_beat) < 0) {
      next_beat = make_timeout_time_ms(3000);
      int mounted = 0;
      for (uint8_t a = 1; a <= CFG_TUH_DEVICE_MAX; a++) if (tuh_mounted(a)) mounted++;
      // re-arm HID polling in case a request was dropped
      for (uint8_t a = 1; a <= CFG_TUH_DEVICE_MAX; a++) {
        for (uint8_t i = 0; i < 4; i++) {
          if (tuh_hid_mounted(a, i)) tuh_hid_receive_report(a, i);
        }
      }
      static int last_mounted = -1;
      if (mounted != last_mounted) {
        last_mounted = mounted;
        cdc_printf("usb: %d device(s) on %s\r\n", mounted,
                   (USE_USB2 ? "USB2(gp8/9)" : "USB1(gp7/6)"));
      }
    }
  }
}
