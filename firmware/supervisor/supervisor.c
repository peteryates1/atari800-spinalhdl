#include "supervisor.h"
#include "config.h"
#include "boot.h"
#include <string.h>

// ===== state =====
static bool          active;
static boot_config_t live;                 // live selection (copy; SD untouched)

static enum { PICK_NONE, PICK_CART, PICK_DISK } pending;
static int  pendingDrive;                  // drive index while PICK_DISK
static char names[16][CFG_NAME_LEN];       // last-listed folder names
static int  nameCount;

bool sup_active(void) { return active; }

static void sup_enter(void);
void sup_open(void) { if (!active) sup_enter(); }

// ===== rendering (console backend; render-agnostic logic) =====
static void print_menu(void) {
  cdc_printf("\r\n==== SUPERVISOR (Atari paused) ====\r\n");
  cdc_printf(" Cart : %s%s\r\n",
             live.hasCart ? live.cartName : "<none>",
             live.hasCart ? (live.cartMode == 0x21 ? "  [16K]" : "  [8K]") : "");
  for (int i = 0; i < CFG_MAX_DISKS; i++)
    cdc_printf(" D%d:  : %s\r\n", i + 1,
               live.diskName[i][0] ? live.diskName[i] : "<empty>");
  cdc_printf("-----------------------------------\r\n");
  cdc_printf(" [c] cart   [1-4] disk in drive\r\n");
  cdc_printf(" [b] boot   [q] resume\r\n");
  cdc_printf(" [s] save as default   [r] reload config\r\n");
  cdc_printf("> ");
}

static void list_folders(const char *subdir) {
  nameCount = config_list_subdirs(live.machine, subdir, names, 16);
  if (nameCount == 0) { cdc_printf("  (none found in %s)\r\n", subdir); return; }
  for (int i = 0; i < nameCount; i++) {
    char tag = (i < 10) ? (char)('0' + i) : (char)('a' + i - 10);
    cdc_printf("  %c) %s\r\n", tag, names[i]);
  }
  cdc_printf("  [n] none   [x] cancel\r\n> ");
}

// ===== transitions =====
static void sup_enter(void) {
  config_load(&live);                      // fresh from SD, reflects current config
  fpga_send_control(0x10);                 // halt/pause the Atari
  active = true;
  pending = PICK_NONE;
  print_menu();
}

static void sup_resume(void) {
  active = false;
  pending = PICK_NONE;
  fpga_send_control(0x00);                  // release halt — resume the frozen Atari
  cdc_printf("\r\nsupervisor: resumed\r\n");
}

static void sup_boot(void) {
  active = false;
  pending = PICK_NONE;
  cdc_printf("\r\nsupervisor: booting selection...\r\n");
  boot_run(&live);                          // applies live cart/disks + reset
}

// Map a listing tag char ('0'..'9','a'..'f') to an index, or -1.
static int tag_index(char c) {
  if (c >= '0' && c <= '9') return c - '0';
  if (c >= 'a' && c <= 'f') return 10 + (c - 'a');
  return -1;
}

void sup_feed_key(char c) {
  if (!active) return;

  if (pending != PICK_NONE) {
    if (c == 'x' || c == 0x1b) { pending = PICK_NONE; print_menu(); return; }
    if (c == 'n') {
      if (pending == PICK_CART) config_select_cart(&live, NULL);
      else                      config_select_disk(&live, pendingDrive, NULL);
      pending = PICK_NONE; print_menu(); return;
    }
    int idx = tag_index(c);
    if (idx >= 0 && idx < nameCount) {
      bool ok = (pending == PICK_CART) ? config_select_cart(&live, names[idx])
                                       : config_select_disk(&live, pendingDrive, names[idx]);
      if (!ok) cdc_printf("  (failed to load %s)\r\n", names[idx]);
      pending = PICK_NONE; print_menu();
    }
    return;
  }

  switch (c) {
    case 'c': case 'C':
      pending = PICK_CART; cdc_printf("\r\nchoose cart:\r\n"); list_folders(live.cartDir);
      break;
    case '1': case '2': case '3': case '4':
      pendingDrive = c - '1'; pending = PICK_DISK;
      cdc_printf("\r\nchoose disk for D%d:\r\n", pendingDrive + 1);
      list_folders(live.diskDir);
      break;
    case 'b': case 'B': sup_boot();   break;
    case 'q': case 'Q': case 0x1b: sup_resume(); break;
    case 's': case 'S':
      cdc_printf("\r\nsupervisor: %s\r\n",
                 config_save(&live) ? "saved as default" : "SAVE FAILED");
      print_menu();
      break;
    case 'r': case 'R':
      config_load(&live); cdc_printf("\r\nsupervisor: reloaded config\r\n"); print_menu();
      break;
    case '?': case 'h': print_menu(); break;
    default: break;
  }
}

// ===== HID hotkey + key translation =====

// Translate a HID keyboard usage id to the ASCII we feed the menu.
static char hid_to_ascii(uint8_t kc) {
  if (kc >= 0x04 && kc <= 0x1d) return (char)('a' + (kc - 0x04));  // a..z
  if (kc >= 0x1e && kc <= 0x26) return (char)('1' + (kc - 0x1e));  // 1..9
  if (kc == 0x27) return '0';
  if (kc == 0x29) return 0x1b;                                     // ESC
  return 0;
}

bool sup_hid_report(const uint8_t report[8]) {
  static bool prevHotkey;
  static uint8_t lastKey;

  bool alt = (report[0] & 0x44) != 0;        // left(0x04) or right(0x40) Alt
  bool f12 = false;
  for (int i = 2; i < 8; i++) if (report[i] == 0x45) f12 = true;   // F12 usage
  bool hotkey = alt && f12;

  if (hotkey && !prevHotkey) {               // rising edge: toggle
    if (active) sup_resume(); else sup_enter();
    lastKey = 0;
  }
  prevHotkey = hotkey;

  if (!active) return false;                  // pass through to the Atari

  // Active: translate the first non-modifier key press into a menu command.
  if (!hotkey) {
    uint8_t kc = 0;
    for (int i = 2; i < 8; i++) {
      if (report[i] && report[i] != 0x45) { kc = report[i]; break; }
    }
    if (kc == 0) lastKey = 0;
    else if (kc != lastKey) {
      lastKey = kc;
      char a = hid_to_ascii(kc);
      if (a) sup_feed_key(a);
    }
  }
  return true;                                // consumed while active
}
