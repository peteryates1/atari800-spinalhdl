// USB Mass Storage backend: exposes the SD card as a USB drive so files and
// configs can be dropped/edited from the host without pulling the card.
//
// SD block I/O is the same sd_read_block/sd_write_block the supervisor's FatFs
// uses; all SD access is single-core cooperative, so block transactions are
// serialised (no SPI corruption). To avoid two writers on one FAT, the drive is
// reported WRITE-PROTECTED while a disk is mounted for SIO (Atari actively using
// it) — read anytime, write when idle/cart-only/paused. After a host write the
// supervisor re-mounts before its next boot, so it sees the changes.
#include "tusb.h"
#include "sd_spi.h"
#include "sio.h"

#define SD_BLOCK_SIZE 512

// Ensure the SD is initialised before serving the host (auto-boot usually does
// this already, but the host may probe first).
static bool sd_ready(void) {
    if (!sd_card_present()) return false;
    if (!sd_initialized()) return sd_init() == 0;
    return true;
}

// SCSI INQUIRY — identify the "drive".
void tud_msc_inquiry_cb(uint8_t lun, uint8_t vendor_id[8], uint8_t product_id[16], uint8_t product_rev[4]) {
    (void) lun;
    const char vid[] = "Atari800";
    const char pid[] = "SD Card";
    const char rev[] = "1.0";
    memcpy(vendor_id, vid, strlen(vid));
    memcpy(product_id, pid, strlen(pid));
    memcpy(product_rev, rev, strlen(rev));
}

// Host polls this to see whether media is present/ready.
bool tud_msc_test_unit_ready_cb(uint8_t lun) {
    (void) lun;
    if (!sd_ready()) {
        // Media not present -> tell the host so it shows "no disk".
        tud_msc_set_sense(lun, SCSI_SENSE_NOT_READY, 0x3A, 0x00);
        return false;
    }
    return true;
}

// Total capacity.
void tud_msc_capacity_cb(uint8_t lun, uint32_t* block_count, uint16_t* block_size) {
    (void) lun;
    *block_count = sd_ready() ? sd_capacity_blocks() : 0;
    *block_size  = SD_BLOCK_SIZE;
}

// Start/Stop Unit (incl. host "eject"). Accept it; nothing to spin up/down.
bool tud_msc_start_stop_cb(uint8_t lun, uint8_t power_condition, bool start, bool load_eject) {
    (void) lun; (void) power_condition; (void) start; (void) load_eject;
    return true;
}

// Write-protect the drive while the Atari is actively using a disk via SIO, so
// the host can't write the FAT underneath an in-progress sector stream.
bool tud_msc_is_writable_cb(uint8_t lun) {
    (void) lun;
    return !sio_any_mounted();
}

// Read blocks from the SD into the host buffer.
int32_t tud_msc_read10_cb(uint8_t lun, uint32_t lba, uint32_t offset, void* buffer, uint32_t bufsize) {
    (void) lun;
    if (!sd_ready() || lba >= sd_capacity_blocks()) return -1;

    if (offset == 0 && (bufsize % SD_BLOCK_SIZE) == 0) {
        uint32_t nblocks = bufsize / SD_BLOCK_SIZE;
        for (uint32_t i = 0; i < nblocks; i++)
            if (sd_read_block(lba + i, (uint8_t*)buffer + i * SD_BLOCK_SIZE) != 0) return -1;
        return (int32_t)(nblocks * SD_BLOCK_SIZE);
    }

    // Partial/offset access: stage one block and copy the requested slice.
    static uint8_t blk[SD_BLOCK_SIZE];
    if (offset >= SD_BLOCK_SIZE) return -1;
    uint32_t n = SD_BLOCK_SIZE - offset;
    if (n > bufsize) n = bufsize;
    if (sd_read_block(lba, blk) != 0) return -1;
    memcpy(buffer, blk + offset, n);
    return (int32_t) n;
}

// Write blocks from the host buffer to the SD.
int32_t tud_msc_write10_cb(uint8_t lun, uint32_t lba, uint32_t offset, uint8_t* buffer, uint32_t bufsize) {
    (void) lun;
    if (!sd_ready() || lba >= sd_capacity_blocks()) return -1;

    if (offset == 0 && (bufsize % SD_BLOCK_SIZE) == 0) {
        uint32_t nblocks = bufsize / SD_BLOCK_SIZE;
        for (uint32_t i = 0; i < nblocks; i++)
            if (sd_write_block(lba + i, buffer + i * SD_BLOCK_SIZE) != 0) return -1;
        return (int32_t)(nblocks * SD_BLOCK_SIZE);
    }

    // Partial/offset write: read-modify-write one block.
    static uint8_t blk[SD_BLOCK_SIZE];
    if (offset >= SD_BLOCK_SIZE) return -1;
    uint32_t n = SD_BLOCK_SIZE - offset;
    if (n > bufsize) n = bufsize;
    if (sd_read_block(lba, blk) != 0) return -1;
    memcpy(blk + offset, buffer, n);
    if (sd_write_block(lba, blk) != 0) return -1;
    return (int32_t) n;
}

// Other SCSI commands. Accept the harmless ones, reject the rest.
int32_t tud_msc_scsi_cb(uint8_t lun, uint8_t const scsi_cmd[16], void* buffer, uint16_t bufsize) {
    (void) lun; (void) buffer; (void) bufsize;
    switch (scsi_cmd[0]) {
        case SCSI_CMD_PREVENT_ALLOW_MEDIUM_REMOVAL:
            return 0;   // no removable-media lock to honour
        default:
            tud_msc_set_sense(lun, SCSI_SENSE_ILLEGAL_REQUEST, 0x20, 0x00);
            return -1;
    }
}
