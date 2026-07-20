// On-demand WiFi (station mode) for the supervisor — the foundation for the HTTP
// SD manager. Off until wifi_on() is called. Credentials come from /wifi.txt on the
// SD card: line 1 = SSID, line 2 = password (empty password -> open network).
// Only built on Pico W / Pico 2 W (HAVE_WIFI, set in CMake for _w boards).
#pragma once
#include <stdbool.h>

bool        wifi_on(void);    // read /wifi.txt, init cyw43, join (blocking, ~15s). true = link up.
void        wifi_off(void);   // disconnect + deinit cyw43 (frees the PIO SM + power).
void        wifi_poll(void);  // pump cyw43 + lwIP; call each main-loop iteration.
bool        wifi_is_up(void); // joined with an IP
const char *wifi_ip(void);    // dotted IP, "0.0.0.0" when down
const char *wifi_ssid(void);  // SSID from /wifi.txt (or "")
