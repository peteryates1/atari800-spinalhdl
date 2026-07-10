#include "rm2.h"
#include "pico/stdlib.h"
#include "hardware/gpio.h"

// Passthrough pin map (see Atari800Rp2040HdmiLgTop RP2040<->RM2 wiring).
#define RM2_SCK      20
#define RM2_MOSI     21
#define RM2_MISO     22
#define RM2_CS       23
#define RM2_WIFI_ON  5
#define RM2_BT_ON    4
#define RM2_IRQ_N    24

// CYW43439 gSPI (from the Pico SDK cyw43-driver).
#define TEST_PATTERN            0xFEEDBEADu
#define BUS_FUNCTION            0
#define SPI_READ_TEST_REGISTER  0x14

static inline void sdly(void) { busy_wait_us_32(1); }   // ~hundreds of kHz, safe

static void rm2_pins_init(void) {
    gpio_init(RM2_SCK);     gpio_set_dir(RM2_SCK, GPIO_OUT);     gpio_put(RM2_SCK, 0);
    gpio_init(RM2_MOSI);    gpio_set_dir(RM2_MOSI, GPIO_OUT);    gpio_put(RM2_MOSI, 0);
    gpio_init(RM2_CS);      gpio_set_dir(RM2_CS, GPIO_OUT);      gpio_put(RM2_CS, 1);   // deasserted
    gpio_init(RM2_WIFI_ON); gpio_set_dir(RM2_WIFI_ON, GPIO_OUT); gpio_put(RM2_WIFI_ON, 0);
    gpio_init(RM2_BT_ON);   gpio_set_dir(RM2_BT_ON, GPIO_OUT);   gpio_put(RM2_BT_ON, 0);
    gpio_init(RM2_MISO);    gpio_set_dir(RM2_MISO, GPIO_IN);
    gpio_init(RM2_IRQ_N);   gpio_set_dir(RM2_IRQ_N, GPIO_IN);
}

// Full-duplex SPI mode 0 byte (MSB first): drive MOSI while CLK low, chip
// samples on the rising edge; we sample MISO while CLK is high.
static uint8_t spi_xfer(uint8_t out) {
    uint8_t in = 0;
    for (int i = 7; i >= 0; i--) {
        gpio_put(RM2_MOSI, (out >> i) & 1);
        sdly();
        in = (uint8_t)((in << 1) | (gpio_get(RM2_MISO) & 1));   // sample before rising edge
        gpio_put(RM2_SCK, 1);
        sdly();
        gpio_put(RM2_SCK, 0);
    }
    return in;
}

// pack_cmd + the reset-time 16-bit-half swap the driver uses before the bus is
// configured (cyw43_put/get_swap32).
static uint32_t pack_cmd(bool wr, bool inc, uint32_t fn, uint32_t addr, uint32_t sz) {
    return ((uint32_t)wr << 31) | ((uint32_t)inc << 30) | (fn << 28) |
           ((addr & 0x1ffff) << 11) | sz;
}

bool rm2_smoke_test(uint32_t *out_val) {
    rm2_pins_init();

    gpio_put(RM2_WIFI_ON, 1);   // power the chip
    sleep_ms(50);               // power-up + crystal settle

    uint32_t cmd = pack_cmd(false, true, BUS_FUNCTION, SPI_READ_TEST_REGISTER, 4);
    uint8_t tx[8] = {
        (uint8_t)(cmd >> 8), (uint8_t)cmd, (uint8_t)(cmd >> 24), (uint8_t)(cmd >> 16), // put_swap32
        0, 0, 0, 0,
    };

    for (int tries = 0; tries < 10; tries++) {
        uint8_t rx[8];
        gpio_put(RM2_CS, 0);
        for (int i = 0; i < 8; i++) rx[i] = spi_xfer(tx[i]);
        gpio_put(RM2_CS, 1);

        // get_swap32(&rx[4])
        uint32_t v = rx[5] | ((uint32_t)rx[4] << 8) | ((uint32_t)rx[7] << 16) | ((uint32_t)rx[6] << 24);
        if (out_val) *out_val = v;
        if (v == TEST_PATTERN) return true;
        sleep_ms(1);
    }
    return false;
}
