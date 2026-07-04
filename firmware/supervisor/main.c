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
  char buf[128];
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
    tud_cdc_write(buf, (uint32_t)n);
    tud_cdc_write_flush();
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
    case 'l': dump_logring(); break;
    case 'h':
    default:
      cdc_printf("supervisor: r=reset 1=hold-start 0=release s=status l=bootlog h=help\r\n");
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
      int armed = 0;
      for (uint8_t a = 1; a <= CFG_TUH_DEVICE_MAX; a++) {
        for (uint8_t i = 0; i < 4; i++) {
          if (tuh_hid_mounted(a, i) && tuh_hid_receive_report(a, i)) armed++;
        }
      }
      cdc_printf("beat: usb_port=%s devices=%d rearmed=%d\r\n",
                 (USE_USB2 ? "USB2(gp8/9)" : "USB1(gp7/6)"), mounted, armed);
    }
  }
}
