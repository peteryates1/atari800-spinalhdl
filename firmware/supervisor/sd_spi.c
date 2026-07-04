// SD card, SPI mode, through the FPGA passthrough:
//   RP2040 SPI1: GPIO10=SCK, GPIO11=TX(CMD/MOSI), GPIO12=RX(DAT0/MISO),
//   GPIO13=CS (soft), GPIO14=card-detect (input, via FPGA).
// The FPGA forwards these combinationally to the SD socket, adding ~tens of
// ns each way — clocks kept conservative (400 kHz init, 4 MHz data).

#include <string.h>
#include "pico/stdlib.h"
#include "hardware/spi.h"
#include "sd_spi.h"

#define SD_SPI      spi1
#define PIN_SD_SCK  10
#define PIN_SD_TX   11
#define PIN_SD_RX   12
#define PIN_SD_CS   13
#define PIN_SD_CD   14

static bool sd_is_sdhc;
static bool sd_ready;

static inline void cs_low(void)  { gpio_put(PIN_SD_CS, 0); }
static inline void cs_high(void) { gpio_put(PIN_SD_CS, 1); }

static uint8_t xfer(uint8_t b) {
  uint8_t rx;
  spi_write_read_blocking(SD_SPI, &b, &rx, 1);
  return rx;
}

static void clocks(int n) { while (n--) xfer(0xFF); }

// Send a command, return R1 (0xFF on response timeout).
static uint8_t sd_cmd(uint8_t cmd, uint32_t arg, uint8_t crc) {
  uint8_t frame[6] = {
    (uint8_t)(0x40 | cmd),
    (uint8_t)(arg >> 24), (uint8_t)(arg >> 16), (uint8_t)(arg >> 8), (uint8_t)arg,
    crc
  };
  xfer(0xFF);
  spi_write_blocking(SD_SPI, frame, 6);
  for (int i = 0; i < 10; i++) {
    uint8_t r = xfer(0xFF);
    if (!(r & 0x80)) return r;
  }
  return 0xFF;
}

bool sd_card_present(void) {
  gpio_init(PIN_SD_CD);
  gpio_set_dir(PIN_SD_CD, GPIO_IN);
  gpio_pull_up(PIN_SD_CD);
  sleep_us(50);
  // Most sockets: switch closes to GND when a card is inserted (active low).
  return !gpio_get(PIN_SD_CD);
}

int sd_init(void) {
  sd_ready = false;
  spi_init(SD_SPI, 400 * 1000);
  gpio_set_function(PIN_SD_SCK, GPIO_FUNC_SPI);
  gpio_set_function(PIN_SD_TX, GPIO_FUNC_SPI);
  gpio_set_function(PIN_SD_RX, GPIO_FUNC_SPI);
  gpio_pull_up(PIN_SD_RX);
  gpio_init(PIN_SD_CS);
  gpio_set_dir(PIN_SD_CS, GPIO_OUT);
  cs_high();
  clocks(10);                       // >74 clocks with CS high

  cs_low();
  uint8_t r = sd_cmd(0, 0, 0x95);   // GO_IDLE
  if (r != 0x01) { cs_high(); return -1; }

  r = sd_cmd(8, 0x1AA, 0x87);       // SEND_IF_COND (v2 check)
  bool v2 = false;
  if (r == 0x01) {
    uint8_t r7[4];
    for (int i = 0; i < 4; i++) r7[i] = xfer(0xFF);
    if (r7[2] != 0x01 || r7[3] != 0xAA) { cs_high(); return -2; }
    v2 = true;
  }

  // ACMD41 until ready
  absolute_time_t deadline = make_timeout_time_ms(1000);
  do {
    sd_cmd(55, 0, 0x01);
    r = sd_cmd(41, v2 ? 0x40000000 : 0, 0x01);
    if (absolute_time_diff_us(get_absolute_time(), deadline) < 0) { cs_high(); return -3; }
  } while (r != 0x00);

  sd_is_sdhc = false;
  if (v2) {
    if (sd_cmd(58, 0, 0x01) != 0x00) { cs_high(); return -4; }   // READ_OCR
    uint8_t ocr[4];
    for (int i = 0; i < 4; i++) ocr[i] = xfer(0xFF);
    sd_is_sdhc = (ocr[0] & 0x40) != 0;
  }
  if (!sd_is_sdhc) {
    if (sd_cmd(16, 512, 0x01) != 0x00) { cs_high(); return -5; } // block size
  }
  cs_high();
  xfer(0xFF);

  spi_set_baudrate(SD_SPI, 4 * 1000 * 1000);
  sd_ready = true;
  return 0;
}

bool sd_initialized(void) { return sd_ready; }
bool sd_sdhc(void) { return sd_is_sdhc; }

static bool wait_token(uint8_t token, uint32_t ms) {
  absolute_time_t deadline = make_timeout_time_ms(ms);
  while (absolute_time_diff_us(get_absolute_time(), deadline) > 0) {
    if (xfer(0xFF) == token) return true;
  }
  return false;
}

int sd_read_block(uint32_t lba, uint8_t *buf) {
  if (!sd_ready) return -1;
  cs_low();
  uint32_t addr = sd_is_sdhc ? lba : lba * 512;
  if (sd_cmd(17, addr, 0x01) != 0x00) { cs_high(); return -2; }
  if (!wait_token(0xFE, 200)) { cs_high(); return -3; }
  memset(buf, 0xFF, 512);
  spi_read_blocking(SD_SPI, 0xFF, buf, 512);
  xfer(0xFF); xfer(0xFF);           // CRC
  cs_high();
  xfer(0xFF);
  return 0;
}

int sd_write_block(uint32_t lba, const uint8_t *buf) {
  if (!sd_ready) return -1;
  cs_low();
  uint32_t addr = sd_is_sdhc ? lba : lba * 512;
  if (sd_cmd(24, addr, 0x01) != 0x00) { cs_high(); return -2; }
  xfer(0xFF);
  xfer(0xFE);                        // data token
  spi_write_blocking(SD_SPI, buf, 512);
  xfer(0xFF); xfer(0xFF);            // CRC
  uint8_t resp = xfer(0xFF);
  if ((resp & 0x1F) != 0x05) { cs_high(); return -3; }
  if (!wait_token(0xFF, 500)) { cs_high(); return -4; }  // busy ends
  cs_high();
  xfer(0xFF);
  return 0;
}

// CSD-based capacity in 512-byte blocks (0 on failure).
uint32_t sd_capacity_blocks(void) {
  if (!sd_ready) return 0;
  cs_low();
  if (sd_cmd(9, 0, 0x01) != 0x00) { cs_high(); return 0; }
  if (!wait_token(0xFE, 200)) { cs_high(); return 0; }
  uint8_t csd[16];
  for (int i = 0; i < 16; i++) csd[i] = xfer(0xFF);
  xfer(0xFF); xfer(0xFF);
  cs_high();
  xfer(0xFF);
  if ((csd[0] >> 6) == 1) {          // CSD v2 (SDHC/SDXC)
    uint32_t c_size = ((uint32_t)(csd[7] & 0x3F) << 16) | ((uint32_t)csd[8] << 8) | csd[9];
    return (c_size + 1) * 1024;
  } else {                           // CSD v1
    uint32_t c_size = ((uint32_t)(csd[6] & 0x03) << 10) | ((uint32_t)csd[7] << 2) | (csd[8] >> 6);
    uint32_t mult = 1u << ((((csd[9] & 0x03) << 1) | (csd[10] >> 7)) + 2);
    uint32_t bl_len = 1u << (csd[5] & 0x0F);
    return (c_size + 1) * mult * (bl_len / 512);
  }
}
