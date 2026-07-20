#include "config.h"
#include "ff.h"
#include <stdio.h>
#include <string.h>
#include <stdlib.h>

// ===== tiny JSON scanner (schema-specific, lenient) =====
//
// The config files are small flat objects with one array (memory-map) and two
// nested objects (cartridge, disks). We don't need a full parser — just:
//   * find a key's value within an object span
//   * read that value as a quoted string or a brace/bracket-delimited span
//   * iterate the objects inside an array
// Key collisions with value text are possible in theory but don't occur with
// this schema's key set, so a bounded strstr is good enough and tiny.

// Find `"key"` within [p,end); return a pointer just past the following ':'
// (skipping whitespace), or NULL.
static const char *j_find(const char *p, const char *end, const char *key) {
  char pat[40];
  int n = snprintf(pat, sizeof pat, "\"%s\"", key);
  if (n <= 0 || n >= (int)sizeof pat) return NULL;
  for (const char *q = p; q + n <= end; q++) {
    if (memcmp(q, pat, n) == 0) {
      const char *c = q + n;
      while (c < end && (*c == ' ' || *c == '\t' || *c == '\r' || *c == '\n')) c++;
      if (c < end && *c == ':') {
        c++;
        while (c < end && (*c == ' ' || *c == '\t' || *c == '\r' || *c == '\n')) c++;
        return c;
      }
    }
  }
  return NULL;
}

// Read a quoted string value at v into out. Returns false if v isn't a string.
static bool j_str(const char *v, const char *end, char *out, int outlen) {
  if (!v || v >= end || *v != '"') return false;
  v++;
  int i = 0;
  while (v < end && *v != '"' && i < outlen - 1) out[i++] = *v++;
  out[i] = 0;
  return (v < end && *v == '"');
}

// Given v pointing at an opening delimiter (open/close e.g. '{'/'}' or '['/']'),
// return the span [*sSpan,*eSpan) covering the inside (exclusive of the
// delimiters), handling nesting and strings. Returns false if not found.
static bool j_span(const char *v, const char *end, char open, char close,
                   const char **sSpan, const char **eSpan) {
  if (!v || v >= end || *v != open) return false;
  const char *s = v + 1;
  int depth = 1;
  bool inStr = false;
  for (const char *q = s; q < end; q++) {
    if (inStr) {
      if (*q == '\\') { q++; continue; }
      if (*q == '"') inStr = false;
      continue;
    }
    if (*q == '"') { inStr = true; continue; }
    if (*q == open) depth++;
    else if (*q == close) { depth--; if (depth == 0) { *sSpan = s; *eSpan = q; return true; } }
  }
  return false;
}

// Iterate objects `{...}` inside an array span [p,end). On entry *cur == p.
// Fills [*os,*oe) with the next object's inner span and advances *cur past it.
// Returns false when no more objects.
static bool j_next_obj(const char **cur, const char *end,
                       const char **os, const char **oe) {
  const char *q = *cur;
  while (q < end && *q != '{') q++;
  if (q >= end) return false;
  if (!j_span(q, end, '{', '}', os, oe)) return false;
  *cur = *oe + 1;
  return true;
}

// Read an entire small file into buf (NUL-terminated). Returns length or -1.
static int read_file(const char *path, char *buf, int buflen) {
  FIL f;
  if (f_open(&f, path, FA_READ) != FR_OK) return -1;
  UINT rd = 0;
  FRESULT r = f_read(&f, buf, buflen - 1, &rd);
  f_close(&f);
  if (r != FR_OK) return -1;
  buf[rd] = 0;
  return (int)rd;
}

bool config_fpga_path(char *out, int outlen) {
  static char root[512];
  int n = read_file("/config.json", root, sizeof root);
  if (n < 0) return false;
  const char *rend = root + n;
  char machine[CFG_PATH_LEN];
  if (!j_str(j_find(root, rend, "default"), rend, machine, sizeof machine))
    return false;
#ifdef BOARD_WUKONG
  snprintf(out, outlen, "%s/core.bit", machine);   // Xilinx Artix-7 (.bit)
#else
  snprintf(out, outlen, "%s/core.rbf", machine);   // Altera Cyclone (.rbf)
#endif
  return true;
}

bool config_load(boot_config_t *cfg) {
  memset(cfg, 0, sizeof *cfg);

  static char root[512];
  if (read_file("/config.json", root, sizeof root) < 0) return false;
  const char *rend = root + strlen(root);

  char machine[CFG_PATH_LEN];
  if (!j_str(j_find(root, rend, "default"), rend, machine, sizeof machine))
    return false;   // no default machine
  strncpy(cfg->machine, machine, CFG_PATH_LEN - 1);
  strcpy(cfg->cartDir, "cartridge");   // defaults if the machine config omits them
  strcpy(cfg->diskDir, "disks");

  // Machine config
  static char mc[2048];
  char mcPath[CFG_PATH_LEN];
  // machine is like "/atari/800"; its config is "<machine>/config.json"
  snprintf(mcPath, sizeof mcPath, "%s/config.json", machine);
  if (read_file(mcPath, mc, sizeof mc) < 0) return false;
  const char *mend = mc + strlen(mc);

  // --- memory-map[] : file entries are OS ROM blocks ---
  const char *mm = j_find(mc, mend, "memory-map");
  const char *ms, *me;
  if (mm && j_span(mm, mend, '[', ']', &ms, &me)) {
    int mlen = (int)(me - ms);
    if (mlen > (int)sizeof cfg->memMap - 1) mlen = sizeof cfg->memMap - 1;
    memcpy(cfg->memMap, ms, mlen); cfg->memMap[mlen] = 0;   // inner text, for save
    const char *cur = ms, *os, *oe;
    while (cfg->osCount < CFG_MAX_OS_BLOCKS && j_next_obj(&cur, me, &os, &oe)) {
      char startStr[16], fileStr[CFG_PATH_LEN];
      bool hasFile = j_str(j_find(os, oe, "file"), oe, fileStr, sizeof fileStr);
      if (!hasFile) continue;   // {start,type} RAM window — not a load
      if (!j_str(j_find(os, oe, "start"), oe, startStr, sizeof startStr)) continue;
      uint32_t start = (uint32_t)strtol(startStr, NULL, 16);
      cfg_os_block_t *b = &cfg->os[cfg->osCount++];
      snprintf(b->path, sizeof b->path, "%s/%s", machine, fileStr);
      b->romAddr = start & 0x3FFF;   // rom-space addr (D800->0x1800, E000->0x2000)
    }
  }

  // --- cartridge : { directory, default } ---
  const char *cartV = j_find(mc, mend, "cartridge");
  const char *cs, *ce;
  if (cartV && j_span(cartV, mend, '{', '}', &cs, &ce)) {
    char def[CFG_NAME_LEN];
    j_str(j_find(cs, ce, "directory"), ce, cfg->cartDir, CFG_NAME_LEN);
    if (j_str(j_find(cs, ce, "default"), ce, def, sizeof def) && def[0])
      config_select_cart(cfg, def);   // fills cartName/Path/addr/mode/hasCart
  }

  // --- disks : { directory, drives:[names] } ---
  const char *diskV = j_find(mc, mend, "disks");
  const char *ds, *de;
  if (diskV && j_span(diskV, mend, '{', '}', &ds, &de)) {
    j_str(j_find(ds, de, "directory"), de, cfg->diskDir, CFG_NAME_LEN);
    const char *dr = j_find(ds, de, "drives");
    const char *as, *ae;
    if (dr && j_span(dr, de, '[', ']', &as, &ae)) {
      const char *q = as;
      while (cfg->diskCount < CFG_MAX_DISKS && q < ae) {
        while (q < ae && *q != '"') q++;
        if (q >= ae) break;
        char name[CFG_NAME_LEN];
        if (!j_str(q, ae, name, sizeof name)) break;
        q++;
        while (q < ae && *q != '"') { if (*q == '\\') q++; q++; }
        if (q < ae) q++;
        if (config_select_disk(cfg, cfg->diskCount, name)) cfg->diskCount++;
      }
    }
  }

  cfg->valid = true;
  return true;
}

// ===== supervisor live-edit helpers =====

int config_list_subdirs(const char *machine, const char *subdir,
                        char names[][CFG_NAME_LEN], int max) {
  char path[CFG_PATH_LEN];
  snprintf(path, sizeof path, "%s/%s", machine, subdir);
  DIR dir;
  if (f_opendir(&dir, path) != FR_OK) return 0;
  FILINFO fno;
  int n = 0;
  while (n < max && f_readdir(&dir, &fno) == FR_OK && fno.fname[0]) {
    if (fno.fattrib & AM_DIR) {
      strncpy(names[n], fno.fname, CFG_NAME_LEN - 1);
      names[n][CFG_NAME_LEN - 1] = 0;
      n++;
    }
  }
  f_closedir(&dir);
  return n;
}

bool config_select_cart(boot_config_t *cfg, const char *name) {
  if (!name || name[0] == 0 || strcmp(name, "none") == 0) {
    cfg->hasCart = false; cfg->cartName[0] = 0; cfg->cartPath[0] = 0;
    cfg->cartMode = 0; cfg->cartAddr = 0xA000;
    return true;
  }
  char cpath[CFG_PATH_LEN];
  snprintf(cpath, sizeof cpath, "%s/%s/%s/config.json", cfg->machine, cfg->cartDir, name);
  static char cc[512];
  if (read_file(cpath, cc, sizeof cc) < 0) return false;
  const char *cend = cc + strlen(cc);
  char file[CFG_PATH_LEN], type[16];
  if (!j_str(j_find(cc, cend, "file"), cend, file, sizeof file)) return false;
  bool is16 = j_str(j_find(cc, cend, "type"), cend, type, sizeof type)
              && strcmp(type, "16K") == 0;
  snprintf(cfg->cartPath, sizeof cfg->cartPath, "%s/%s/%s/%s",
           cfg->machine, cfg->cartDir, name, file);
  strncpy(cfg->cartName, name, CFG_NAME_LEN - 1); cfg->cartName[CFG_NAME_LEN - 1] = 0;
  cfg->cartAddr = is16 ? 0x8000 : 0xA000;
  cfg->cartMode = is16 ? 0x21   : 0x01;
  cfg->hasCart  = true;
  return true;
}

bool config_select_disk(boot_config_t *cfg, int drive, const char *name) {
  if (drive < 0 || drive >= CFG_MAX_DISKS) return false;
  if (!name || name[0] == 0 || strcmp(name, "none") == 0) {
    cfg->diskPath[drive][0] = 0; cfg->diskName[drive][0] = 0;
    return true;
  }
  char dpath[CFG_PATH_LEN];
  snprintf(dpath, sizeof dpath, "%s/%s/%s/config.json", cfg->machine, cfg->diskDir, name);
  static char dc[512];
  if (read_file(dpath, dc, sizeof dc) < 0) return false;
  const char *dcend = dc + strlen(dc);
  char file[CFG_PATH_LEN];
  if (!j_str(j_find(dc, dcend, "file"), dcend, file, sizeof file)) return false;
  snprintf(cfg->diskPath[drive], CFG_PATH_LEN, "%s/%s/%s/%s",
           cfg->machine, cfg->diskDir, name, file);
  strncpy(cfg->diskName[drive], name, CFG_NAME_LEN - 1);
  cfg->diskName[drive][CFG_NAME_LEN - 1] = 0;
  return true;
}

bool config_save(const boot_config_t *cfg) {
  // Rewrite <machine>/config.json, preserving memory-map, updating the cart
  // default and disks.drives from the live selection.
  static char out[1536];
  int n = 0;
  n += snprintf(out + n, sizeof out - n, "{\n  \"memory-map\" : [%s],\n", cfg->memMap);
  n += snprintf(out + n, sizeof out - n,
                "  \"cartridge\" : {\n    \"directory\" : \"%s\",\n    \"default\" : \"%s\"\n  },\n",
                cfg->cartDir, cfg->hasCart ? cfg->cartName : "");
  n += snprintf(out + n, sizeof out - n,
                "  \"disks\" : {\n    \"directory\" : \"%s\",\n    \"drives\" : [",
                cfg->diskDir);
  bool first = true;
  for (int i = 0; i < CFG_MAX_DISKS; i++) {
    if (cfg->diskName[i][0]) {
      n += snprintf(out + n, sizeof out - n, "%s\"%s\"", first ? "" : ", ", cfg->diskName[i]);
      first = false;
    }
  }
  n += snprintf(out + n, sizeof out - n, "]\n  }\n}\n");
  if (n <= 0 || n >= (int)sizeof out) return false;

  char mcPath[CFG_PATH_LEN];
  snprintf(mcPath, sizeof mcPath, "%s/config.json", cfg->machine);
  FIL f;
  if (f_open(&f, mcPath, FA_WRITE | FA_CREATE_ALWAYS) != FR_OK) return false;
  UINT wr = 0;
  FRESULT r = f_write(&f, out, n, &wr);
  f_close(&f);
  return r == FR_OK && (int)wr == n;
}
