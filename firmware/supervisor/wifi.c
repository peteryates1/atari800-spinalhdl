#ifdef HAVE_WIFI
#include "wifi.h"
#include "httpsrv.h"
#include "boot.h"            // cdc_printf
#include "pico/cyw43_arch.h"
#include "lwip/netif.h"
#include "lwip/ip4_addr.h"
#include "lwip/timeouts.h"
#include "lib/fatfs/source/ff.h"
#include <string.h>

static bool s_inited = false;      // cyw43_arch_init done (PIO SM claimed)
static bool s_up     = false;      // joined + IP
static char s_ssid[64];
static char s_pass[64];

// /wifi.txt: line 1 = SSID, line 2 = password. Returns true if an SSID was read.
static bool read_creds(void) {
    static FATFS fs;
    f_mount(&fs, "", 1);
    FIL f;
    if (f_open(&f, "/wifi.txt", FA_READ) != FR_OK) return false;
    char buf[160]; UINT rd = 0;
    f_read(&f, buf, sizeof buf - 1, &rd);
    f_close(&f);
    buf[rd] = 0;
    s_ssid[0] = s_pass[0] = 0;

    char *nl = strpbrk(buf, "\r\n");
    if (!nl) { strncpy(s_ssid, buf, sizeof s_ssid - 1); return s_ssid[0] != 0; }
    *nl = 0;
    strncpy(s_ssid, buf, sizeof s_ssid - 1);
    char *p = nl + 1;
    while (*p == '\r' || *p == '\n') p++;
    char *nl2 = strpbrk(p, "\r\n");
    if (nl2) *nl2 = 0;
    strncpy(s_pass, p, sizeof s_pass - 1);
    return s_ssid[0] != 0;
}

bool wifi_on(void) {
    if (s_up) return true;
    if (!read_creds()) {
        cdc_printf("wifi: no /wifi.txt (line1 SSID, line2 password)\r\n");
        return false;
    }
    if (!s_inited) {
        cdc_printf("wifi: init cyw43...\r\n");
        if (cyw43_arch_init()) { cdc_printf("wifi: cyw43 init FAILED\r\n"); return false; }
        s_inited = true;
    }
    cyw43_arch_enable_sta_mode();
    uint32_t auth = s_pass[0] ? CYW43_AUTH_WPA2_AES_PSK : CYW43_AUTH_OPEN;
    cdc_printf("wifi: joining \"%s\"...\r\n", s_ssid);
    int r = cyw43_arch_wifi_connect_timeout_ms(s_ssid, s_pass, auth, 15000);
    if (r) { cdc_printf("wifi: join FAILED (%d)\r\n", r); return false; }
    s_up = true;
    cdc_printf("wifi: connected, IP %s\r\n", wifi_ip());
    httpsrv_start();            // SD manager web UI at http://<ip>/
    return true;
}

void wifi_off(void) {
    if (!s_inited) return;
    httpsrv_stop();
    // Disconnect but keep cyw43 initialised — cyw43_arch_deinit() in poll mode can
    // hang the cooperative loop (it left the console dead once). "off" = not joined,
    // not serving; wifi_poll() stays a cheap no-op poll. wifi_on() re-joins.
    if (s_up) cyw43_arch_disable_sta_mode();
    s_up = false;
    cdc_printf("wifi: off\r\n");
}

void wifi_poll(void) {
    if (!s_inited) return;
    cyw43_arch_poll();
    sys_check_timeouts();
}

bool        wifi_is_up(void)  { return s_up; }
const char *wifi_ssid(void)   { return s_ssid; }

const char *wifi_ip(void) {
    static char ip[20];
    if (!s_up) return "0.0.0.0";
    ip4addr_ntoa_r(netif_ip4_addr(&cyw43_state.netif[CYW43_ITF_STA]), ip, sizeof ip);
    return ip;
}
#endif // HAVE_WIFI
