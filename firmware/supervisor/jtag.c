#include "jtag.h"
#include "pico/stdlib.h"
#include "hardware/gpio.h"
#include "boot.h"   // cdc_printf

#define PIN_TMS 0
#define PIN_TCK 1
#define PIN_TDI 2
#define PIN_TDO 3

// Cyclone 10 LP 10CL025 shares its JTAG IDCODE with the equivalent Cyclone IV E
// (22K LE) die. Used as a sanity check only; the raw value is always printed.
#define IDCODE_10CL025 0x020F30DDu

static bool s_inited = false;

// ~1 us half-period -> ~500 kHz TCK. Plenty for bring-up; PIO will replace this
// for real programming throughput.
static inline void tck_delay(void) { busy_wait_us_32(1); }

void jtag_init(void) {
    if (s_inited) return;
    gpio_init(PIN_TMS); gpio_set_dir(PIN_TMS, GPIO_OUT); gpio_put(PIN_TMS, 1);
    gpio_init(PIN_TCK); gpio_set_dir(PIN_TCK, GPIO_OUT); gpio_put(PIN_TCK, 0);
    gpio_init(PIN_TDI); gpio_set_dir(PIN_TDI, GPIO_OUT); gpio_put(PIN_TDI, 0);
    gpio_init(PIN_TDO); gpio_set_dir(PIN_TDO, GPIO_IN);  gpio_pull_up(PIN_TDO);
    s_inited = true;
}

// One TCK cycle: drive TMS/TDI, rising edge (target samples TMS/TDI), sample
// TDO while high, falling edge (target updates TDO). Returns the sampled TDO.
static inline bool jtag_pulse(bool tms, bool tdi) {
    gpio_put(PIN_TMS, tms);
    gpio_put(PIN_TDI, tdi);
    tck_delay();
    gpio_put(PIN_TCK, 1);
    tck_delay();
    bool tdo = gpio_get(PIN_TDO);
    gpio_put(PIN_TCK, 0);
    return tdo;
}

// Clock out `n` TMS bits (LSB-first), TDI held low — used for TAP navigation.
static void jtag_tms_seq(uint32_t bits, int n) {
    for (int i = 0; i < n; i++) jtag_pulse((bits >> i) & 1u, 0);
}

uint32_t jtag_read_idcode(void) {
    jtag_init();

    // Test-Logic-Reset: 5 clocks with TMS=1. Loads IDCODE into the DR.
    jtag_tms_seq(0x1F, 5);
    // TLR -> Run-Test/Idle -> Select-DR -> Capture-DR -> Shift-DR
    // TMS sequence (LSB-first): 0,1,0,0
    jtag_tms_seq(0b0010, 4);

    // In Shift-DR: TDO presents IDCODE[0]. Read 32 bits LSB-first; drive TMS=1
    // on the final bit to leave via Exit1-DR.
    uint32_t id = 0;
    for (int i = 0; i < 32; i++) {
        if (gpio_get(PIN_TDO)) id |= (1u << i);
        jtag_pulse(i == 31 ? 1 : 0, 0);
    }

    // Exit1-DR -> Update-DR -> Run-Test/Idle (TMS: 1,0), then park in TLR.
    jtag_tms_seq(0b01, 2);
    jtag_tms_seq(0x1F, 5);
    return id;
}

void jtag_idcode_print(void) {
    uint32_t id = jtag_read_idcode();
    const char *verdict =
        (id == IDCODE_10CL025)            ? "OK" :
        (id == 0 || id == 0xFFFFFFFFu)    ? "-- no TAP (check J10 wiring / GND)" :
                                            "?? unexpected device";
    cdc_printf("jtag: IDCODE = %08lx (expect %08lx 10CL025) %s\r\n",
               (unsigned long)id, (unsigned long)IDCODE_10CL025, verdict);
}
