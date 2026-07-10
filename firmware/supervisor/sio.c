#include "sio.h"
#include "fpga_link.h"
#include "boot.h"        // cdc_printf
#include "ff.h"
#include "pico/stdlib.h"
#include "tusb.h"
#include <string.h>

// ===== SIO protocol constants (from SioDiskEmu.java) =====
#define SIO_ACK      0x41
#define SIO_NAK      0x4E
#define SIO_COMPLETE 0x43
#define SIO_ERROR    0x45

#define CMD_READ_SECTOR 0x52  // 'R'
#define CMD_GET_STATUS  0x53  // 'S'
#define CMD_GET_SPEED   0x3F  // '?'

#define DEVICE_D1 0x31        // D1:..D4: = 0x31..0x34

// SIO protocol timing (microseconds), after COMMAND goes high
#define T2_DELAY 250  // COMMAND high -> ACK
#define T5_DELAY 250  // ACK -> COMPLETE/ERROR
#define T3_DELAY 150  // COMPLETE -> data frame

// SioBridge register addresses
#define SREG_STATUS 0
#define SREG_RXDATA 1
#define SREG_TXDATA 2
#define SREG_TXSTAT 3
#define SREG_RXSTAT 5

// STATUS bits
#define ST_TXBUSY 0x04
// TX/RX_STATUS bits
#define FIFO_EMPTY 0x01
#define FIFO_FULL  0x02

typedef struct {
  bool mounted;
  FIL  fil;
  int  sectorSize;   // 128 or 256
  int  sectorCount;  // total sectors
} sio_drive_t;

static sio_drive_t drives[SIO_MAX_DRIVES];
static uint8_t      sectorBuf[256];

// ===== debug counters (viewable via the console 'D' command) =====
static uint32_t s_frames, s_forus, s_reads, s_status, s_speed, s_nak;
static int      s_maxRx = 0, s_lastDev = -1, s_lastCmd = -1, s_lastSec = -1;

void sio_stats_print(void) {
  cdc_printf("sio: maxRx=%d frames=%lu forUs=%lu reads=%lu status=%lu speed=%lu nak=%lu"
             " | last dev=%02x cmd=%02x sec=%d\r\n",
             s_maxRx, (unsigned long)s_frames, (unsigned long)s_forus,
             (unsigned long)s_reads, (unsigned long)s_status, (unsigned long)s_speed,
             (unsigned long)s_nak, s_lastDev & 0xFF, s_lastCmd & 0xFF, s_lastSec);
}

// ===== mount / unmount =====

bool sio_mount(int drive, const char *path) {
  if (drive < 0 || drive >= SIO_MAX_DRIVES) return false;
  sio_drive_t *d = &drives[drive];
  if (d->mounted) { f_close(&d->fil); d->mounted = false; }

  if (f_open(&d->fil, path, FA_READ) != FR_OK) return false;

  uint8_t hdr[16];
  UINT rd = 0;
  if (f_read(&d->fil, hdr, 16, &rd) != FR_OK || rd != 16) {
    f_close(&d->fil); return false;
  }

  // ATR header: magic=0x0296 (LE), paragraphs (LE16 + hi byte), sectorSize LE16
  int magic = hdr[0] | (hdr[1] << 8);
  if (magic != 0x0296) { f_close(&d->fil); return false; }

  int paraLo = hdr[2] | (hdr[3] << 8);
  d->sectorSize = hdr[4] | (hdr[5] << 8);
  int paraHi = hdr[6] & 0xFF;

  uint32_t paragraphs = (uint32_t)paraLo | ((uint32_t)paraHi << 16);
  uint32_t dataSize = paragraphs * 16;   // total sector-data bytes

  // Boot sectors 1-3 are always 128 bytes; the rest use sectorSize.
  if (dataSize <= 384) d->sectorCount = (int)(dataSize / 128);
  else                 d->sectorCount = 3 + (int)((dataSize - 384) / d->sectorSize);

  d->mounted = true;
  return true;
}

void sio_unmount_all(void) {
  for (int i = 0; i < SIO_MAX_DRIVES; i++) {
    if (drives[i].mounted) { f_close(&drives[i].fil); drives[i].mounted = false; }
  }
}

bool sio_any_mounted(void) {
  for (int i = 0; i < SIO_MAX_DRIVES; i++) if (drives[i].mounted) return true;
  return false;
}

// ===== SIO checksum: 8-bit sum with end-around carry =====
static int sio_checksum(const uint8_t *data, int len) {
  int sum = 0;
  for (int i = 0; i < len; i++) {
    sum += data[i];
    if (sum > 0xFF) sum = (sum & 0xFF) + 1;
  }
  return sum & 0xFF;
}

// ===== TX helpers =====
static void tx_enable(void)  { fpga_sio_write(SREG_STATUS, 1); }  // CTRL: TX_ENABLE
static void tx_disable(void) { fpga_sio_write(SREG_STATUS, 0); }

static void tx_byte(uint8_t b) {
  // Wait for space in the 16-deep TX FIFO (it drains at 19200 baud).
  while (fpga_sio_read(SREG_TXSTAT) & FIFO_FULL) tud_task();
  fpga_sio_write(SREG_TXDATA, b);
}

static void tx_wait_done(void) {
  while (!(fpga_sio_read(SREG_TXSTAT) & FIFO_EMPTY)) tud_task();  // FIFO drained
  while (fpga_sio_read(SREG_STATUS) & ST_TXBUSY)     tud_task();  // P2S idle
}

// ===== sector read from ATR =====
static bool read_sector_data(sio_drive_t *d, int sector, int size) {
  uint32_t fileOffset;
  if (sector <= 3) fileOffset = 16 + (uint32_t)(sector - 1) * 128;
  else             fileOffset = 16 + 384 + (uint32_t)(sector - 4) * d->sectorSize;

  memset(sectorBuf, 0, size);
  if (f_lseek(&d->fil, fileOffset) != FR_OK) return false;
  UINT rd = 0;
  if (f_read(&d->fil, sectorBuf, size, &rd) != FR_OK) return false;
  return (int)rd == size;
}

// ===== command handlers =====
static void cmd_read_sector(sio_drive_t *d, int sector) {
  busy_wait_us(T2_DELAY);
  tx_enable();
  tx_byte(SIO_ACK);

  if (!d->mounted || sector < 1 || sector > d->sectorCount) {
    busy_wait_us(T5_DELAY);
    tx_byte(SIO_ERROR);
    tx_wait_done();
    tx_disable();
    return;
  }

  int size = (sector <= 3) ? 128 : d->sectorSize;
  bool ok = read_sector_data(d, sector, size);

  busy_wait_us(T5_DELAY);
  if (!ok) {
    tx_byte(SIO_ERROR);
    tx_wait_done();
    tx_disable();
    return;
  }

  tx_byte(SIO_COMPLETE);
  busy_wait_us(T3_DELAY);

  int cksum = 0;
  for (int i = 0; i < size; i++) {
    tx_byte(sectorBuf[i]);
    cksum += sectorBuf[i];
    if (cksum > 0xFF) cksum = (cksum & 0xFF) + 1;
  }
  tx_byte(cksum & 0xFF);

  tx_wait_done();
  tx_disable();
}

static void cmd_get_status(sio_drive_t *d) {
  busy_wait_us(T2_DELAY);
  tx_enable();
  tx_byte(SIO_ACK);
  busy_wait_us(T5_DELAY);
  tx_byte(SIO_COMPLETE);
  busy_wait_us(T3_DELAY);

  int stat0 = 0x10;                        // motor on
  if (d->sectorSize == 256) stat0 |= 0x20; // double density
  if (!d->mounted)          stat0 |= 0x08; // write protected when no disk

  uint8_t status[4] = { (uint8_t)stat0, 0xFF, 0xE0, 0x00 };
  int cksum = 0;
  for (int i = 0; i < 4; i++) {
    tx_byte(status[i]);
    cksum += status[i];
    if (cksum > 0xFF) cksum = (cksum & 0xFF) + 1;
  }
  tx_byte(cksum & 0xFF);

  tx_wait_done();
  tx_disable();
}

static void cmd_get_speed(void) {
  busy_wait_us(T2_DELAY);
  tx_enable();
  tx_byte(SIO_ACK);
  busy_wait_us(T5_DELAY);
  tx_byte(SIO_COMPLETE);
  busy_wait_us(T3_DELAY);

  tx_byte(0x00);   // standard speed (19200 baud)
  tx_byte(0x00);   // checksum == data for a single byte
  tx_wait_done();
  tx_disable();
}

static void cmd_nak(void) {
  busy_wait_us(T2_DELAY);
  tx_enable();
  tx_byte(SIO_NAK);
  tx_wait_done();
  tx_disable();
}

// ===== poll / dispatch =====
void sio_poll(void) {
  // A command frame is 5 bytes. Wait until at least 5 are queued.
  uint16_t rxStat = fpga_sio_read(SREG_RXSTAT);
  int count = (rxStat >> 2) & 0x1F;
  if (count > s_maxRx) s_maxRx = count;   // did any SIO bytes ever arrive?
  if (count < 5) return;

  // Drain the FIFO, tagging each byte with its command-byte index (high byte
  // of RX_DATA). Find the last complete 5-byte frame (idx 0..4).
  uint16_t raw[16];
  int total = 0;
  while (total < 16 && !(fpga_sio_read(SREG_RXSTAT) & FIFO_EMPTY)) {
    raw[total++] = fpga_sio_read(SREG_RXDATA);
  }

  uint8_t frame[5];
  int fc = 0;
  bool found = false;
  for (int i = 0; i < total; i++) {
    int data = raw[i] & 0xFF;
    int idx  = (raw[i] >> 8) & 0xFF;
    if (idx == 0)              { frame[0] = data; fc = 1; found = true; }
    else if (found && idx == fc && fc < 5) { frame[fc] = data; fc++; }
  }
  if (fc < 5) return;   // no complete frame

  int deviceId = frame[0];
  int command  = frame[1];
  int aux1     = frame[2];
  int aux2     = frame[3];
  int checksum = frame[4];

  s_frames++;
  s_lastDev = deviceId; s_lastCmd = command;

  int drive = deviceId - DEVICE_D1;
  if (drive < 0 || drive >= SIO_MAX_DRIVES) return;   // not our device
  if (!drives[drive].mounted) return;                 // absent drive -> no reply
  s_forus++;

  if (sio_checksum(frame, 4) != checksum) { s_nak++; cmd_nak(); return; }

  int sector = (aux2 << 8) | aux1;
  sio_drive_t *d = &drives[drive];

  switch (command) {
    case CMD_READ_SECTOR: s_reads++;  s_lastSec = sector; cmd_read_sector(d, sector); break;
    case CMD_GET_STATUS:  s_status++; cmd_get_status(d);          break;
    case CMD_GET_SPEED:   s_speed++;  cmd_get_speed();            break;
    default:              s_nak++;    cmd_nak();                  break;
  }
}
