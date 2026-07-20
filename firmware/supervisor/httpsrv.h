// Minimal HTTP server (raw lwIP TCP) for the on-network SD manager: browse the SD,
// upload carts/disks, delete. Started when WiFi comes up, stopped when it goes down.
// Only built on _w boards (HAVE_WIFI). See httpsrv.c.
#pragma once

void httpsrv_start(void);   // listen on :80 (call once WiFi has an IP)
void httpsrv_stop(void);    // stop listening + drop connections
