// Standalone RM2 (CYW43439) Wi-Fi connectivity test: associate to an AP, DHCP an address,
// then ICMP-ping the gateway (LAN) and 8.8.8.8 (internet). No console needed — every result
// lands in RAM globals read over the debug probe. Pins overridden in CMake (CLK25/DATA24/
// CS23/ON22); credentials from wifi_creds.h (git-ignored).
//
//   openocd ... -c "init; dump_image out.bin 0x<g_stage> N; shutdown"  then decode:
//   g_stage      : 0xB0..02 init done, 03 connecting, 04 connected, 05 got IP, 06 pinged, 0D0E all done
//                  0xB0..FF init fail, 0xB0..C<rc> connect fail (rc = -err low nibble)
//   g_ip/g_gw/g_netmask : DHCP lease (u32, host order via ip4_addr_get_u32)
//   g_ping_gw_sent/recv, g_ping_ext_sent/recv : ICMP echo counts
#include <string.h>
#include "pico/stdlib.h"
#include "pico/cyw43_arch.h"
#include "cyw43.h"          // cyw43_wifi_pm, CYW43_DEFAULT_PM (to surface ensure_up's error)
#include "lwip/netif.h"
#include "lwip/raw.h"
#include "lwip/icmp.h"
#include "lwip/inet_chksum.h"
#include "lwip/ip4_addr.h"
#include "wifi_creds.h"     // WIFI_SSID, WIFI_PASSWORD

volatile uint32_t g_stage         = 0xB0000000u;
volatile int32_t  g_ensure_up_rc  = -777;    // cyw43_ensure_up() result (firmware/CLM bring-up)
volatile int32_t  g_connect_rc    = -999;
volatile uint32_t g_ip            = 0, g_gw = 0, g_netmask = 0;
volatile uint32_t g_ping_gw_sent  = 0, g_ping_gw_recv  = 0;
volatile uint32_t g_ping_ext_sent = 0, g_ping_ext_recv = 0;

#define PING_ID        0xAFAFu
#define PING_DATA_SIZE 32
static uint16_t s_seq;
static volatile uint32_t *s_recv_ctr;   // active reply counter

// ICMP echo-reply handler (runs in the lwIP background context).
static u8_t ping_recv(void *arg, struct raw_pcb *pcb, struct pbuf *p, const ip_addr_t *addr) {
    (void)arg; (void)pcb; (void)addr;
    if (p->tot_len >= (u16_t)(PBUF_IP_HLEN + sizeof(struct icmp_echo_hdr))) {
        if (pbuf_remove_header(p, PBUF_IP_HLEN) == 0) {
            struct icmp_echo_hdr *ie = (struct icmp_echo_hdr *)p->payload;
            if (ie->type == ICMP_ER && ie->id == lwip_htons(PING_ID)) {
                if (s_recv_ctr) (*s_recv_ctr)++;
                pbuf_free(p);
                return 1;                 // consumed
            }
            pbuf_add_header(p, PBUF_IP_HLEN);
        }
    }
    return 0;
}

static void ping_run(const ip_addr_t *target, volatile uint32_t *sent,
                     volatile uint32_t *recv, int count) {
    s_recv_ctr = recv;
    cyw43_arch_lwip_begin();
    struct raw_pcb *pcb = raw_new(IP_PROTO_ICMP);
    raw_recv(pcb, ping_recv, NULL);
    raw_bind(pcb, IP_ADDR_ANY);
    cyw43_arch_lwip_end();

    for (int i = 0; i < count; i++) {
        struct pbuf *p = pbuf_alloc(PBUF_IP, sizeof(struct icmp_echo_hdr) + PING_DATA_SIZE, PBUF_RAM);
        if (p) {
            struct icmp_echo_hdr *ie = (struct icmp_echo_hdr *)p->payload;
            ICMPH_TYPE_SET(ie, ICMP_ECHO);
            ICMPH_CODE_SET(ie, 0);
            ie->chksum = 0;
            ie->id     = lwip_htons(PING_ID);
            ie->seqno  = lwip_htons(++s_seq);
            char *d = (char *)ie + sizeof(struct icmp_echo_hdr);
            for (int k = 0; k < PING_DATA_SIZE; k++) d[k] = (char)k;
            ie->chksum = inet_chksum(ie, sizeof(struct icmp_echo_hdr) + PING_DATA_SIZE);
            cyw43_arch_lwip_begin();
            raw_sendto(pcb, p, target);
            cyw43_arch_lwip_end();
            pbuf_free(p);
            (*sent)++;
        }
        sleep_ms(1000);                   // background context services the reply
    }

    cyw43_arch_lwip_begin();
    raw_remove(pcb);
    cyw43_arch_lwip_end();
    s_recv_ctr = NULL;
}

int main(void) {
    stdio_init_all();
    if (cyw43_arch_init()) { g_stage = 0xB00000FFu; while (true) tight_loop_contents(); }
    g_stage = 0xB0000002u;

    // Force the full chip bring-up (firmware/CLM upload) and capture its error, since
    // cyw43_wifi_set_up() (inside enable_sta_mode) swallows it. This is what fails at -EPERM.
    g_ensure_up_rc = cyw43_wifi_pm(&cyw43_state, CYW43_DEFAULT_PM);

    cyw43_arch_enable_sta_mode();
    g_stage = 0xB0000003u;
    int rc = cyw43_arch_wifi_connect_timeout_ms(WIFI_SSID, WIFI_PASSWORD,
                                                CYW43_AUTH_WPA2_AES_PSK, 30000);
    g_connect_rc = rc;
    if (rc) { g_stage = 0xB00000C0u | (uint32_t)((-rc) & 0xF); while (true) { sleep_ms(200); } }
    g_stage = 0xB0000004u;

    struct netif *nif = &cyw43_state.netif[CYW43_ITF_STA];
    for (int i = 0; i < 100 && ip4_addr_isany_val(*netif_ip4_addr(nif)); i++) sleep_ms(100);
    g_ip      = ip4_addr_get_u32(netif_ip4_addr(nif));
    g_gw      = ip4_addr_get_u32(netif_ip4_gw(nif));
    g_netmask = ip4_addr_get_u32(netif_ip4_netmask(nif));
    g_stage   = 0xB0000005u;

    ip_addr_t gw;  ip_addr_set_ip4_u32(&gw, g_gw);
    ip_addr_t ext; IP4_ADDR(&ext, 8, 8, 8, 8);
    if (g_gw) ping_run(&gw, &g_ping_gw_sent, &g_ping_gw_recv, 5);
    ping_run(&ext, &g_ping_ext_sent, &g_ping_ext_recv, 5);
    g_stage = 0xB0000D0Eu;                // done

    while (true) { sleep_ms(500); }
}
