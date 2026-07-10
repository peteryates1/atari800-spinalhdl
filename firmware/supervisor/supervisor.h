// Supervisor mode: an interactive menu to pause the Atari and live-edit what
// to boot (cart / disks), then reboot — without touching the SD config unless
// "save as default" is chosen. Entered by Alt-F12 on the USB keyboard.
//
// Rendering is currently the USB serial console; the menu logic here is
// render-agnostic so an on-screen (HDMI/SDRAM framebuffer) backend can be
// added later behind the same interface.
#ifndef SUPERVISOR_H
#define SUPERVISOR_H

#include <stdbool.h>
#include <stdint.h>

// True while the menu is active (Atari paused).
bool sup_active(void);

// Open the menu (pause the Atari) if not already active. Used by the serial
// console '~' trigger; the USB keyboard uses the Alt-F12 hotkey instead.
void sup_open(void);

// Feed one menu keystroke (ASCII). Drives navigation/selection while active.
void sup_feed_key(char c);

// Inspect a raw 8-byte HID boot report before it is forwarded to the Atari.
// Detects the Alt-F12 hotkey (toggles supervisor mode) and, while active,
// translates keys into menu commands. Returns true if the report was consumed
// (the caller must then NOT forward it to the Atari).
bool sup_hid_report(const uint8_t report[8]);

#endif // SUPERVISOR_H
