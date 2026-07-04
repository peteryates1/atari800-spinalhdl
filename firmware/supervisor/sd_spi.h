#pragma once
#include <stdbool.h>
#include <stdint.h>
bool sd_card_present(void);
int  sd_init(void);
bool sd_initialized(void);
bool sd_sdhc(void);
int  sd_read_block(uint32_t lba, uint8_t *buf);
int  sd_write_block(uint32_t lba, const uint8_t *buf);
uint32_t sd_capacity_blocks(void);
