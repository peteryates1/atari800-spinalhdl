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
#include "pico/stdlib.h"
#include "hardware/clocks.h"
#include "hardware/spi.h"
#include "pio_usb.h"
#include "tusb.h"
#include "sd_spi.h"
#include "lib/fatfs/source/ff.h"

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

static void cdc_printf(const char *fmt, ...) {
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

static void fpga_send_control(uint8_t bits) {
  uint8_t tx[2] = {'C', bits};
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
static void handle_console(void) {
  if (!tud_cdc_available()) return;
  uint8_t ch;
  if (tud_cdc_read(&ch, 1) != 1) return;
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
    case 'd': {   // mount + list root directory
      static FATFS fs;
      FRESULT fr = f_mount(&fs, "", 1);
      if (fr != FR_OK) { cdc_printf("sd: f_mount failed (%d)\r\n", fr); break; }
      DIR dir; FILINFO fno;
      fr = f_opendir(&dir, "/");
      if (fr != FR_OK) { cdc_printf("sd: opendir failed (%d)\r\n", fr); break; }
      int n = 0;
      while (f_readdir(&dir, &fno) == FR_OK && fno.fname[0]) {
        cdc_printf("  %s%-40s %lu\r\n", (fno.fattrib & AM_DIR) ? "/" : " ",
                   fno.fname, (unsigned long)fno.fsize);
        if (++n >= 64) break;
        tud_task();
      }
      f_closedir(&dir);
      cdc_printf("sd: %d entries\r\n", n);
      break;
    }
    case 'B': {   // boot: hold reset, load OS from SD to SDRAM, release
      static FATFS fs;
      if (f_mount(&fs, "", 1) != FR_OK) { cdc_printf("boot: SD mount failed\r\n"); fpga_send_control(0x00); break; }
      struct { const char *path; uint32_t addr; } items[] = {
        { "/os/atarios2.rom", 0x141800 },   // $D800-$DFFF (2 KB), OS window @0x140000
        { "/os/atariosb.rom", 0x142000 },   // $E000-$FFFF (8 KB)
      };
      bool ok = true;
      fpga_send_control(0x10);              // HALT the 6502: quiet SDRAM during load
      for (unsigned it = 0; it < 2 && ok; it++) {
        bool fileOk = false;
        for (int attempt = 0; attempt < 3 && !fileOk; attempt++) {
          FIL f;
          if (f_open(&f, items[it].path, FA_READ) != FR_OK) {
            cdc_printf("boot: open %s failed\r\n", items[it].path); break;
          }
          fpga_load_zero();
          uint16_t lcnt = 0, lsum = 0; uint32_t total = 0;
          static uint8_t buf[504]; UINT rd;
          while (f_read(&f, buf, sizeof buf, &rd) == FR_OK && rd > 0) {
            fpga_load(items[it].addr + total, buf, rd, &lcnt, &lsum);
            total += rd; tud_task();
          }
          f_close(&f);
          uint16_t fcnt, fsum; fpga_load_status(&fcnt, &fsum);
          bool match = (lcnt == fcnt && lsum == fsum);
          cdc_printf("boot: %s -> %06lx (%lu bytes) stream %s\r\n", items[it].path,
                     items[it].addr, total, match ? "ok" : "CHECKSUM MISMATCH");
          // and now the part no stream checksum can fake: what the SDRAM holds
          fileOk = fpga_verify_content(items[it].addr, total, lsum) && match;
          if (!fileOk) cdc_printf("boot: retrying %s\r\n", items[it].path);
        }
        ok = ok && fileOk;
      }
      // The supervisor "reset" is a stretched ~1.1 ms pulse (the control
      // register lives in the reset domain), so there is no true hold: the
      // Atari runs (crashed, harmlessly - ROM regions reject 6502 writes)
      // while we load. The reset that matters is the one AFTER the load.
      if (ok) {
        cdc_printf("boot: OS loaded+verified, resetting Atari\r\n");
        fpga_send_control(0x11);            // release halt via reset
        fpga_send_control(0x00);
      } else {
        fpga_send_control(0x00);            // release halt, no reset
        cdc_printf("boot: load errors - not resetting\r\n");
      }
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
      cdc_printf("fb meters: read-late=%d write-drop=%d (sticky since boot)\r\n",
                 gpio_get(15), gpio_get(22));
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
      fpga_verify_content(0x141800, 2048, 0x11e1);
      fpga_verify_content(0x141800, 2048, 0x11e1);
      uint8_t mem[32];
      for (int i = 0; i < 32; i++) {   // V len=1 = single-byte peek
        uint8_t tx[8] = { 0x56, 0x14, 0x18, (uint8_t)i, 0, 0, 1, 0 };
        uint8_t rx[10];
        fpga_spi_frame(tx, rx, sizeof tx);
        sleep_ms(2);
        uint8_t z[10] = {0}, st[10];
        fpga_spi_frame(z, st, sizeof z);
        mem[i] = st[6];
      }
      fpga_send_control(0x00);
      static FATFS fs; FIL f; UINT rd; uint8_t fb2[32] = {0};
      if (f_mount(&fs, "", 1) == FR_OK && f_open(&f, "/os/atarios2.rom", FA_READ) == FR_OK) {
        f_read(&f, fb2, 32, &rd); f_close(&f);
      }
      for (int r = 0; r < 2; r++) {
        cdc_printf("sdram %06x:", 0x141800 + r * 16);
        for (int i = 0; i < 16; i++) cdc_printf(" %02x", mem[r * 16 + i]);
        cdc_printf("\r\n  file %06x:", r * 16);
        for (int i = 0; i < 16; i++) cdc_printf(" %02x", fb2[r * 16 + i]);
        cdc_printf("\r\n");
      }
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

int main(void) {
#if !BISECT_CDC_ONLY && !BISECT_NO_CLOCK
  set_sys_clock_khz(120000, true);   // PIO-USB officially supports 120 MHz
#endif

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

  absolute_time_t next_beat = make_timeout_time_ms(3000);
  while (true) {
    tud_task();
#if !BISECT_CDC_ONLY
    tuh_task();
#endif
    handle_console();
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
