// Standalone RM2 (CYW43439) bring-up test — NOTHING but the pico-sdk cyw43 driver,
// so there's no USB/blaster/PIO contention. cyw43_arch_init() powers REG_ON, reads the
// 0xFEEDBEAD test register over the half-duplex gSPI, and uploads the chip firmware; a 0
// return means the RM2 is alive and correctly wired. Pins are overridden in CMake to the
// stamp-direct map (CLK=25, DATA=24 bidi, CS=23, REG_ON=22).
//
// No CDC/UART needed: the result is stashed in RAM globals; read them over the debug probe:
//   openocd ... -c "init; halt; mdw &g_rm2_marker; mdw &g_rm2_init_result; ..."
//   g_rm2_marker: 0xC0DE0001 = init OK   0xC0DEBAD0 = init FAILED
//   g_rm2_init_result: the raw cyw43_arch_init() return code (0 = ok, <0 = error)
#include "pico/stdlib.h"
#include "pico/cyw43_arch.h"

volatile uint32_t g_rm2_marker      = 0xC0DE0000u;   // 0000 = not run yet
volatile int32_t  g_rm2_init_result = -12345;

int main(void) {
    sleep_ms(200);                       // let the 3V3 rail / chip settle
    int r = cyw43_arch_init();
    g_rm2_init_result = r;
    g_rm2_marker = (r == 0) ? 0xC0DE0001u : 0xC0DEBAD0u;
    while (true) {
        tight_loop_contents();
        sleep_ms(500);
    }
}
