#include "supervisor.h"
#include "config.h"
#include "boot.h"
#include "fbtext.h"
#ifdef HAVE_WIFI
#include "wifi.h"
#endif
#include <string.h>
#include <stdio.h>

// ===== state =====
static bool          active;
static boot_config_t live;                 // live selection (copy; SD untouched)

static enum { PICK_NONE, PICK_CART, PICK_DISK } pending;
static int  pendingDrive;                  // drive index while PICK_DISK
static char names[16][CFG_NAME_LEN];       // last-listed folder names
static int  nameCount;
static bool g_turbo = false;               // 6502 turbo ('C' ctrl bit 6); persists pause/resume

bool sup_active(void) { return active; }

static void sup_enter(void);
void sup_open(void) { if (!active) sup_enter(); }

// ===== on-screen backend: mirror the current menu state onto HDMI =====
// Rendered into the SDRAM supervisor framebuffer; shown while supDisplay is set
// (only called while active, so the Atari capture is frozen -> no conflict).
static void fb_render(void) {
  if (!active) return;
  char buf[FBT_COLS + 1];
  fbtext_colors(0x0F, 0x00);
  fbtext_clear();
  fbtext_colors(0x3E, 0x00); fbtext_puts(0, 1, "ATARI 800 SUPERVISOR");

  if (pending == PICK_NONE) {
    fbtext_colors(0x0F, 0x00);
    snprintf(buf, sizeof buf, "Cart: %s%s",
             live.hasCart ? live.cartName : "<none>",
             live.hasCart ? (live.cartMode == 0x21 ? " [16K]" : " [8K]") : "");
    fbtext_puts(2, 1, buf);
    for (int i = 0; i < CFG_MAX_DISKS; i++) {
      snprintf(buf, sizeof buf, "D%d: %s", i + 1,
               live.diskName[i][0] ? live.diskName[i] : "<empty>");
      fbtext_puts(3 + i, 1, buf);
    }
    fbtext_colors(0x2C, 0x00);
    fbtext_puts(9,  1, "[c] cart   [1-4] disk");
    fbtext_puts(10, 1, "[b] boot   [q] resume");
    fbtext_puts(11, 1, "[s] save   [r] reload");
    snprintf(buf, sizeof buf, "[t] turbo: %s", g_turbo ? "ON" : "off");
    fbtext_puts(12, 1, buf);
#ifdef HAVE_WIFI
    snprintf(buf, sizeof buf, "[w] wifi: %s %s", wifi_is_up() ? "ON" : "off",
             wifi_is_up() ? wifi_ip() : "");
    fbtext_puts(13, 1, buf);
#endif
  } else {
    fbtext_colors(0x0F, 0x00);
    fbtext_puts(2, 1, pending == PICK_CART ? "Choose cart:" : "Choose disk:");
    int r = 3;
    for (int i = 0; i < nameCount && r < FBT_ROWS - 1; i++, r++) {
      char tag = (i < 10) ? (char)('0' + i) : (char)('a' + i - 10);
      snprintf(buf, sizeof buf, " %c) %s", tag, names[i]);
      fbtext_puts(r, 1, buf);
    }
    fbtext_colors(0x2C, 0x00);
    fbtext_puts(FBT_ROWS - 1, 1, "[n] none   [x] cancel");
  }
  fbtext_flush();
}

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
  cdc_printf(" [t] turbo: %s\r\n", g_turbo ? "ON" : "off");
#ifdef HAVE_WIFI
  cdc_printf(" [w] wifi: %s  %s\r\n", wifi_is_up() ? "ON " : "off", wifi_is_up() ? wifi_ip() : "");
#endif
  cdc_printf("> ");
  fb_render();
}

static void list_folders(const char *subdir) {
  nameCount = config_list_subdirs(live.machine, subdir, names, 16);
  if (nameCount == 0) { cdc_printf("  (none found in %s)\r\n", subdir); return; }
  for (int i = 0; i < nameCount; i++) {
    char tag = (i < 10) ? (char)('0' + i) : (char)('a' + i - 10);
    cdc_printf("  %c) %s\r\n", tag, names[i]);
  }
  cdc_printf("  [n] none   [x] cancel\r\n> ");
  fb_render();
}

// ===== transitions =====
static void sup_enter(void) {
  config_load(&live);                      // fresh from SD, reflects current config
  active = true;
  pending = PICK_NONE;
  fpga_send_control(0x30 | (g_turbo ? 0x40 : 0));   // halt(pause)+supDisplay(+turbo held)
  print_menu();                            // console + on-screen (fb_render)
}

static void sup_resume(void) {
  active = false;
  pending = PICK_NONE;
  fpga_send_control(0x00 | (g_turbo ? 0x40 : 0));   // release halt (keep turbo held)
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
    case 't': case 'T':
      g_turbo = !g_turbo;
      fpga_send_control(0x30 | (g_turbo ? 0x40 : 0));   // still paused; update held turbo bit
      cdc_printf("\r\nsupervisor: turbo %s\r\n", g_turbo ? "ON" : "off");
      print_menu();
      break;
#ifdef HAVE_WIFI
    case 'w': case 'W':
      if (wifi_is_up()) wifi_off(); else wifi_on();
      print_menu();
      break;
#endif
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
