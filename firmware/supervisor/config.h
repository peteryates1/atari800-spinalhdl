// Config-driven boot: parse the SD-card JSON hierarchy into a boot plan.
//
// Layout on the card:
//   /config.json                         { "default": "/atari/800" }
//   <machine>/config.json                memory-map[], cartridge{}, disks{}
//   <machine>/cartridge/<name>/config.json   { file, type }
//   <machine>/disks/<name>/config.json       { file }
//
// The parser is deliberately tiny and schema-specific (not a general JSON
// library): the files are small and regular. It fills a boot_config_t that
// do_boot() then streams into BRAM, and that the SIO emu mounts as drives.
#ifndef CONFIG_H
#define CONFIG_H

#include <stdint.h>
#include <stdbool.h>

#define CFG_MAX_OS_BLOCKS 4
#define CFG_MAX_DISKS     4
#define CFG_PATH_LEN      128
#define CFG_NAME_LEN      48

typedef struct {
  char     path[CFG_PATH_LEN];  // absolute path to the ROM file
  uint32_t romAddr;             // ROM-space load address (Atari addr & 0x3FFF)
} cfg_os_block_t;

typedef struct {
  bool     valid;

  char     machine[CFG_PATH_LEN];   // machine dir, e.g. "/atari/800"
  char     cartDir[CFG_NAME_LEN];   // cartridge subdir name ("cartridge")
  char     diskDir[CFG_NAME_LEN];   // disks subdir name ("disks")
  char     memMap[640];             // raw memory-map[] inner text (for save)

  // OS ROM blocks (memory-map entries that carry a "file")
  cfg_os_block_t os[CFG_MAX_OS_BLOCKS];
  int            osCount;

  // Cartridge (the machine's default cart)
  bool     hasCart;
  char     cartName[CFG_NAME_LEN];  // cart folder name ("" = none)
  char     cartPath[CFG_PATH_LEN];
  uint32_t cartAddr;            // 0xA000 (8K) or 0x8000 (16K)
  uint8_t  cartMode;            // 0x01 (8K) or 0x21 (16K); enables emuCart RD5

  // Disk images per drive slot (D1: = index 0). Empty diskPath = no disk.
  char     diskName[CFG_MAX_DISKS][CFG_NAME_LEN];   // disk folder name
  char     diskPath[CFG_MAX_DISKS][CFG_PATH_LEN];
  int      diskCount;          // slots filled by config_load (in order)
} boot_config_t;

// Walk /config.json -> machine config -> cart + disks. Returns false if the
// top-level config or machine config can't be read/parsed. Missing cart/disks
// are not errors (cfg.hasCart / cfg.diskCount reflect what was found).
bool config_load(boot_config_t *cfg);

// ---- supervisor live-edit helpers (do NOT touch the SD files) ----

// List the subfolder names under <machine>/<subdir> (e.g. cfg->cartDir) into
// names[]. Returns the count (<= max).
int config_list_subdirs(const char *machine, const char *subdir,
                        char names[][CFG_NAME_LEN], int max);

// Set the live cart selection by folder name (reads its config.json for
// file/type). name NULL or "none" clears the cart. Returns false if the named
// cart can't be resolved.
bool config_select_cart(boot_config_t *cfg, const char *name);

// Set the live disk in a drive slot (0 = D1:) by folder name. name NULL or
// "none" ejects. Returns false if the named disk can't be resolved.
bool config_select_disk(boot_config_t *cfg, int drive, const char *name);

// Persist the current cart/disk selection back to <machine>/config.json
// (rewrites cartridge.default and disks.drives; leaves memory-map intact).
// Only called by the explicit "save as default" menu action.
bool config_save(const boot_config_t *cfg);

#endif // CONFIG_H
