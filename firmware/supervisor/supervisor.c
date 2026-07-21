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
#ifdef HAVE_WIFI
static bool g_wifi_connecting = false;     // shown while the blocking wifi_on() runs
#endif

// ===== cart/disk picker: type-to-filter =====
static char s_filter[CFG_NAME_LEN];        // current filter text within a pick
#define PICK_LC(x) (((x) >= 'A' && (x) <= 'Z') ? (char)((x) + 32) : (x))
static bool pick_match(const char *name) {           // case-insensitive substring
  if (!s_filter[0]) return true;
  int nl = (int)strlen(s_filter);
  for (const char *p = name; *p; p++) {
    int i = 0;
    while (i < nl && p[i] && PICK_LC(p[i]) == PICK_LC(s_filter[i])) i++;
    if (i == nl) return true;
  }
  return false;
}
static int pick_nth(int k) {                         // names[] index of k-th match, or -1
  int seen = 0;
  for (int i = 0; i < nameCount; i++)
    if (pick_match(names[i])) { if (seen == k) return i; seen++; }
  return -1;
}

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
    snprintf(buf, sizeof buf, "[w] wifi: %s %s",
             g_wifi_connecting ? "connecting..." : (wifi_is_up() ? "ON" : "off"),
             (!g_wifi_connecting && wifi_is_up()) ? wifi_ip() : "");
    fbtext_puts(13, 1, buf);
#endif
  } else {
    fbtext_colors(0x0F, 0x00);
    fbtext_puts(2, 1, pending == PICK_CART ? "Choose cart:" : "Choose disk:");
    fbtext_colors(0x3E, 0x00);
    snprintf(buf, sizeof buf, "Filter: %s_", s_filter);
    fbtext_puts(3, 1, buf);
    fbtext_colors(0x0F, 0x00);
    fbtext_puts(4, 1, " 0) (none)");
    int r = 5, shown = 0;
    for (int i = 0; i < nameCount && r < FBT_ROWS - 1 && shown < 9; i++) {
      if (!pick_match(names[i])) continue;
      snprintf(buf, sizeof buf, " %d) %s", shown + 1, names[i]);
      fbtext_puts(r++, 1, buf); shown++;
    }
    fbtext_colors(0x2C, 0x00);
    fbtext_puts(FBT_ROWS - 1, 1, "type=filter digit=pick esc=back");
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
  cdc_printf(" [w] wifi: %s  %s\r\n",
             g_wifi_connecting ? "connecting..." : (wifi_is_up() ? "ON " : "off"),
             (!g_wifi_connecting && wifi_is_up()) ? wifi_ip() : "");
#endif
  cdc_printf("> ");
  fb_render();
}

// Redraw the (filtered) pick list on the console + on-screen. Called on entry and
// on every filter keystroke.
static void render_pick(void) {
  cdc_printf("\r\n%s  (filter: '%s')\r\n",
             pending == PICK_CART ? "choose cart" : "choose disk", s_filter);
  cdc_printf("  0) (none)\r\n");
  int shown = 0, total = 0;
  for (int i = 0; i < nameCount; i++) {
    if (!pick_match(names[i])) continue;
    total++;
    if (shown < 9) { cdc_printf("  %d) %s\r\n", shown + 1, names[i]); shown++; }
  }
  if (total > 9) cdc_printf("  (+%d more; type to narrow)\r\n", total - 9);
  cdc_printf("  [type=filter  digit=pick  bksp=edit  esc=back]\r\n> ");
  fb_render();
}

// Apply a pick: idx into names[], or -1 for "(none)".
static void do_pick(int idx) {
  const char *nm = (idx < 0) ? NULL : names[idx];
  bool ok = (pending == PICK_CART) ? config_select_cart(&live, nm)
                                   : config_select_disk(&live, pendingDrive, nm);
  if (!ok) cdc_printf("  (failed to load %s)\r\n", nm ? nm : "none");
  pending = PICK_NONE;
  print_menu();
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

void sup_feed_key(char c) {
  if (!active) return;

  if (pending != PICK_NONE) {
    if (c == 0x1b) {                       // ESC: clear the filter, else cancel the pick
      if (s_filter[0]) { s_filter[0] = 0; render_pick(); }
      else { pending = PICK_NONE; print_menu(); }
      return;
    }
    if (c == 0x08 || c == 0x7f) {          // backspace: edit the filter
      int l = (int)strlen(s_filter); if (l) s_filter[l - 1] = 0;
      render_pick(); return;
    }
    if (c == '\r' || c == '\n') {          // Enter: pick the top filtered item
      int idx = pick_nth(0); if (idx >= 0) do_pick(idx);
      return;
    }
    if (c >= '0' && c <= '9') {            // digit: 0 = none, 1-9 = filtered item
      if (c == '0') { do_pick(-1); return; }
      int idx = pick_nth(c - '1'); if (idx >= 0) do_pick(idx);
      return;
    }
    if (c >= ' ' && c < 0x7f) {            // printable: append to the filter
      int l = (int)strlen(s_filter);
      if (l < (int)sizeof(s_filter) - 1) { s_filter[l] = c; s_filter[l + 1] = 0; render_pick(); }
      return;
    }
    return;
  }

  switch (c) {
    case 'c': case 'C':
      pending = PICK_CART; s_filter[0] = 0;
      nameCount = config_list_subdirs(live.machine, live.cartDir, names, 16);
      render_pick();
      break;
    case '1': case '2': case '3': case '4':
      pendingDrive = c - '1'; pending = PICK_DISK; s_filter[0] = 0;
      nameCount = config_list_subdirs(live.machine, live.diskDir, names, 16);
      render_pick();
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
      if (wifi_is_up()) {
        wifi_off();
        print_menu();
      } else {
        g_wifi_connecting = true;
        print_menu();            // paint "connecting..." to the overlay + console first
        wifi_on();               // blocks a few seconds (cyw43 init + join)
        g_wifi_connecting = false;
        print_menu();            // final: ON + IP, or off if it failed
      }
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
  if (kc == 0x28) return '\r';                                     // Enter -> pick top match
  if (kc == 0x2a) return 0x08;                                     // Backspace -> edit filter
  if (kc == 0x2c) return ' ';                                      // Space -> filter char
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
