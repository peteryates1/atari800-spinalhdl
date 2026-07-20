#include "blaster.h"
#include "pico/stdlib.h"
#include "hardware/gpio.h"
#include "hardware/structs/sio.h"
#include "hardware/pio.h"
#include "blaster_jtag.pio.h"

// This board's JTAG TAP (base J10 -> QMTech module). Same GPIOs as jtag.c.
#ifdef BOARD_COLORLIGHT
// Colorlight base board: JTAG on GPIO22-25 (to J5 + module pads). NOTE the board
// routes TCK=22, TMS=23 (TMS/TCK swapped vs GPIO0-3) — the blaster PIO pin order
// may need adjusting at runtime.
#define TMS_PIN 23
#define TCK_PIN 22
#define TDI_PIN 24
#define TDO_PIN 25
#elif defined(BOARD_WUKONG)
// Wukong: JTAG matches the dirtyJtag wiring — TCK=GP0, TDO=GP1, TMS=GP2, TDI=GP3.
#define TCK_PIN 0
#define TDO_PIN 1
#define TMS_PIN 2
#define TDI_PIN 3
#else
#define TMS_PIN 0
#define TCK_PIN 1
#define TDI_PIN 2
#define TDO_PIN 3
#endif

// PIO shift TCK rate = 120 MHz sysclk / (clkdiv * 4). Divider 5 -> 6 MHz, the
// real USB-Blaster rate; conservative for the J10 flying leads (the delay-free
// tens-of-MHz shift failed, but 6 MHz should hold). Raise the divider if a chain
// scan / program ever fails; the bit-bang fallback (~500 kHz) always works.
#define BLASTER_TCK_CLKDIV 5.0f

// PIO shift engine (falls back to bit-bang if it can't be placed alongside the
// PIO-USB host).
static PIO  s_pio;
static uint s_sm;
static bool s_pio_ok = false;

#define TCK_MASK (1u << TCK_PIN)
#define TDI_MASK (1u << TDI_PIN)
#define TDO_MASK (1u << TDO_PIN)

// Driven output pins (TCK, TMS, TDI). JTAG-only: no nCE/nCS/DATAOUT/nSTATUS.
#define OUT_PIN_MASK ((1u << TCK_PIN) | (1u << TMS_PIN) | (1u << TDI_PIN))

// Blaster protocol byte decode.
#define SHIFT_MODE_FLAG(b)  (!!((b) & 0x80))
#define READ_FLAG(b)        (!!((b) & 0x40))
#define OE_FLAG(b)          (!!((b) & 0x20))
#define PAYLOAD(b)             ((b) & 0x3F)

// rx byte -> signal (bitbang mode):
//   0x01 TCK   0x02 TMS   0x10 TDI   0x20 output-enable/LED   0x40 read
// tx byte (read-back): 0x01 = TDO. (DATAOUT/nSTATUS bit 0x02 not wired -> 0.)

static bool s_initialized = false;
static bool s_output_enabled = false;
static int  s_shift_bytes_left = 0;
static bool s_shift_read_set;

// Try to place the JTAG shift program on a PIO with a free SM (PIO-USB already
// owns parts of pio0/pio1). Returns true and leaves the SM running (stalled on
// its empty TX FIFO) on success. Pins stay under SIO control until a shift.
static bool blaster_pio_try(PIO pio) {
    if (!pio_can_add_program(pio, &blaster_jtag_program)) return false;
    int sm = pio_claim_unused_sm(pio, false);
    if (sm < 0) return false;
    uint offset = pio_add_program(pio, &blaster_jtag_program);
    s_pio = pio;
    s_sm  = (uint)sm;
    blaster_jtag_program_init(pio, (uint)sm, offset,
                              TDI_PIN, TDO_PIN, TCK_PIN, BLASTER_TCK_CLKDIV);
    // Hand TCK/TDI back to SIO so bit-bang navigation still works; shift mode
    // switches them to PIO on entry.
    gpio_set_function(TCK_PIN, GPIO_FUNC_SIO);
    gpio_set_function(TDI_PIN, GPIO_FUNC_SIO);
    return true;
}

static void blaster_init(void) {
    gpio_init(TMS_PIN);
    gpio_init(TCK_PIN);
    gpio_init(TDI_PIN);
    gpio_init(TDO_PIN);
    // Dedicated JTAG lines: drive TCK/TMS/TDI as outputs unconditionally (like
    // jtag.c). The Blaster "output-enable" bit only tracks LED/state below — we
    // never high-Z the bus, otherwise the chain reads as broken.
    gpio_set_dir_masked(OUT_PIN_MASK, OUT_PIN_MASK);
    gpio_put_masked(OUT_PIN_MASK, 0);
    gpio_set_dir(TDO_PIN, GPIO_IN);
    gpio_pull_up(TDO_PIN);

    // Fast path: PIO shift (bit-bang navigation is unchanged). PIO-USB runs on
    // pio0(TX)/pio1(RX); try either for a spare SM + instruction slot.
    s_pio_ok = blaster_pio_try(pio0) || blaster_pio_try(pio1);

    s_initialized = true;
}

static inline void delay_5_cycles(void) {
    __asm__ volatile("nop\n\t nop\n\t nop\n\t nop\n\t nop");
}

static inline void output_enable(bool enable) {
    if (s_output_enabled == enable) return;
    s_output_enabled = enable;
    // Drive the output pins, or float them (high-Z) when disabled.
    gpio_set_dir_masked(OUT_PIN_MASK, enable ? 0xFFFFFFFFu : 0);
}

static inline uint32_t protocol_to_gpio(uint8_t data) {
    uint32_t v = 0;
    if (data & 0x01) v |= (1u << TCK_PIN);   // bit0 -> TCK
    if (data & 0x02) v |= (1u << TMS_PIN);   // bit1 -> TMS
    if (data & 0x10) v |= (1u << TDI_PIN);   // bit4 -> TDI
    return v;
}

// One bit-bang step: sample TDO (state from the previous byte), then apply the
// new output levels atomically.
static inline uint8_t bitbang(uint8_t data) {
    uint8_t ret = (uint8_t)(!!gpio_get(TDO_PIN));   // bit0 = TDO
    delay_5_cycles();
    gpio_put_masked(OUT_PIN_MASK, protocol_to_gpio(data));
    return ret;
}

// TCK half-period. The J10 flying-lead wiring won't tolerate the reference's
// delay-free (tens of MHz) shift; ~1 us/edge matches jtag.c's proven-reliable
// ~500 kHz on this exact wiring. PIO will restore speed later with a tuned
// divider.
static inline void jtck_delay(void) { busy_wait_us_32(1); }

// Shift one byte LSB-first onto TDI with TCK toggling; return the 8 TDO bits.
static inline uint8_t shift_bitbang(uint8_t data) {
    uint8_t ret = 0;
    for (int i = 0; i < 8; ++i) {
        // Drive TDI (LSB first), let it settle, then sample the current TDO bit.
        if (data & 1) sio_hw->gpio_set = TDI_MASK;
        else          sio_hw->gpio_clr = TDI_MASK;
        jtck_delay();
        ret >>= 1;
        if (sio_hw->gpio_in & TDO_MASK) ret |= 0x80;
        // Clock: rising edge shifts, hold, falling edge.
        sio_hw->gpio_set = TCK_MASK;
        jtck_delay();
        sio_hw->gpio_clr = TCK_MASK;
        data >>= 1;
    }
    return ret;
}

// Switch TCK/TDI to PIO control for a run of shift bytes.
static inline void shift_enter_pio(void) {
    pio_gpio_init(s_pio, TCK_PIN);
    pio_gpio_init(s_pio, TDI_PIN);
}

// Hand TCK/TDI back to SIO for bit-bang TAP navigation.
static inline void shift_exit_pio(void) {
    while (!pio_sm_is_rx_fifo_empty(s_pio, s_sm)) (void)s_pio->rxf[s_sm];
    gpio_set_function(TCK_PIN, GPIO_FUNC_SIO);
    gpio_set_function(TDI_PIN, GPIO_FUNC_SIO);
    gpio_set_dir(TCK_PIN, true);
    gpio_set_dir(TDI_PIN, true);
}

// Shift one byte through the PIO SM (autopull/autopush at 8 bits). Right-shift
// IN lands the 8 bits at [31:24], so read the full word and take the top byte.
static inline uint8_t shift_pio(uint8_t data) {
    *(io_rw_8 *)&s_pio->txf[s_sm] = data;
    while (pio_sm_is_rx_fifo_empty(s_pio, s_sm)) tight_loop_contents();
    return (uint8_t)(s_pio->rxf[s_sm] >> 24);
}

void blaster_reset(void) {
    if (!s_initialized) blaster_init();
    s_shift_bytes_left = 0;
    output_enable(false);
    gpio_put_masked(OUT_PIN_MASK, 0);
}

int blaster_process(uint8_t rxBuf[], int rxCount, uint8_t txBuf[]) {
    int txCount = 0;

    for (int i = 0; i < rxCount; ++i) {
        uint8_t b = rxBuf[i];

        if (s_shift_bytes_left > 0) {           // shift mode active
            uint8_t input = s_pio_ok ? shift_pio(b) : shift_bitbang(b);
            if (s_shift_read_set) txBuf[txCount++] = input;
            if (--s_shift_bytes_left == 0 && s_pio_ok) shift_exit_pio();
        } else if (SHIFT_MODE_FLAG(b)) {        // enter shift mode
            s_shift_read_set = READ_FLAG(b);
            s_shift_bytes_left = PAYLOAD(b);
            gpio_put(TCK_PIN, false);
            if (s_pio_ok) shift_enter_pio();
        } else {                                // bit-bang mode
            output_enable(OE_FLAG(b));
            uint8_t input = bitbang(b);
            if (READ_FLAG(b)) txBuf[txCount++] = input;
        }
    }

    return txCount;
}
