// FatFs media glue for the SPI SD card.
#include "lib/fatfs/source/ff.h"
#include "lib/fatfs/source/diskio.h"
#include "sd_spi.h"

DSTATUS disk_status(BYTE drv) {
  (void)drv;
  return sd_initialized() ? 0 : STA_NOINIT;
}

DSTATUS disk_initialize(BYTE drv) {
  (void)drv;
  return (sd_init() == 0) ? 0 : STA_NOINIT;
}

DRESULT disk_read(BYTE drv, BYTE *buf, LBA_t sector, UINT count) {
  (void)drv;
  for (UINT i = 0; i < count; i++)
    if (sd_read_block(sector + i, buf + 512 * i) != 0) return RES_ERROR;
  return RES_OK;
}

DRESULT disk_write(BYTE drv, const BYTE *buf, LBA_t sector, UINT count) {
  (void)drv;
  for (UINT i = 0; i < count; i++)
    if (sd_write_block(sector + i, buf + 512 * i) != 0) return RES_ERROR;
  return RES_OK;
}

DRESULT disk_ioctl(BYTE drv, BYTE cmd, void *ptr) {
  (void)drv;
  switch (cmd) {
    case CTRL_SYNC: return RES_OK;
    case GET_SECTOR_COUNT: *(LBA_t*)ptr = sd_capacity_blocks(); return RES_OK;
    case GET_SECTOR_SIZE:  *(WORD*)ptr = 512; return RES_OK;
    case GET_BLOCK_SIZE:   *(DWORD*)ptr = 1; return RES_OK;
  }
  return RES_PARERR;
}

DWORD get_fattime(void) {
  return ((DWORD)(2026 - 1980) << 25) | ((DWORD)7 << 21) | ((DWORD)4 << 16);
}
