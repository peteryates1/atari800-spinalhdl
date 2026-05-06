/*
 * Pi Pico USB HID boot protocol keyboard test fixture.
 *
 * Connects to CH376T as a USB keyboard. Listens on UART (GPIO0/1 via
 * debug probe) for test commands. Sends precisely timed keypresses.
 *
 * UART commands (115200 8N1):
 *   T [count] [press_ms] [release_ms] [keycode]
 *     Send 'count' keypresses. Defaults: 50 150 300 0x16 (S key)
 *   S
 *     Print status (mounted, etc.)
 *
 * Output:
 *   MOUNTED       — USB host configured the device
 *   SENDING N     — starting N keypresses
 *   DONE N        — finished, sent N presses
 *   UNMOUNTED     — USB host deconfigured
 */
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include "pico/stdlib.h"
#include "bsp/board.h"
#include "tusb.h"

// HID keycodes (USB HID Usage Table)
#define HID_KEY_S  0x16
#define HID_KEY_G  0x0A
#define HID_KEY_F  0x09

static bool mounted = false;

// Boot protocol keyboard report: 8 bytes
typedef struct {
    uint8_t modifier;
    uint8_t reserved;
    uint8_t keycode[6];
} kbd_report_t;

static void send_key_press(uint8_t keycode) {
    kbd_report_t report = {0};
    report.keycode[0] = keycode;
    tud_hid_report(0, &report, sizeof(report));
}

static void send_key_release(void) {
    kbd_report_t report = {0};
    tud_hid_report(0, &report, sizeof(report));
}

// Run a keypress test: send count presses with given timing
static void run_test(int count, int press_ms, int release_ms, uint8_t keycode) {
    if (!mounted) {
        printf("ERROR: not mounted\n");
        return;
    }

    printf("SENDING %d key=0x%02X press=%dms release=%dms\n",
           count, keycode, press_ms, release_ms);

    for (int i = 0; i < count; i++) {
        // Key down
        send_key_press(keycode);
        // Must call tud_task() during the wait to process USB
        for (int ms = 0; ms < press_ms; ms++) {
            tud_task();
            sleep_ms(1);
        }

        // Key up
        send_key_release();
        for (int ms = 0; ms < release_ms; ms++) {
            tud_task();
            sleep_ms(1);
        }
    }

    printf("DONE %d\n", count);
}

// Parse and execute UART command
static void process_command(char *line) {
    if (line[0] == 'T' || line[0] == 't') {
        int count = 50, press_ms = 150, release_ms = 300;
        uint8_t keycode = HID_KEY_S;

        // Parse optional args: T [count] [press_ms] [release_ms] [keycode_hex]
        char *p = line + 1;
        while (*p == ' ') p++;
        if (*p) { count = atoi(p); while (*p && *p != ' ') p++; while (*p == ' ') p++; }
        if (*p) { press_ms = atoi(p); while (*p && *p != ' ') p++; while (*p == ' ') p++; }
        if (*p) { release_ms = atoi(p); while (*p && *p != ' ') p++; while (*p == ' ') p++; }
        if (*p) { keycode = (uint8_t)strtol(p, NULL, 0); }

        run_test(count, press_ms, release_ms, keycode);
    } else if (line[0] == 'S' || line[0] == 's') {
        printf("STATUS: mounted=%d\n", mounted);
    } else {
        printf("UNKNOWN: %s\n", line);
        printf("Commands: T [count] [press_ms] [release_ms] [keycode]\n");
        printf("          S (status)\n");
    }
}

// ---- TinyUSB callbacks ----

void tud_mount_cb(void) {
    mounted = true;
    gpio_put(PICO_DEFAULT_LED_PIN, 1);
    printf("MOUNTED\n");
}

void tud_umount_cb(void) {
    mounted = false;
    gpio_put(PICO_DEFAULT_LED_PIN, 0);
    printf("UNMOUNTED\n");
}

// Required HID callbacks
uint16_t tud_hid_get_report_cb(uint8_t instance, uint8_t report_id,
                                hid_report_type_t report_type,
                                uint8_t *buffer, uint16_t reqlen) {
    (void)instance; (void)report_id; (void)report_type; (void)buffer; (void)reqlen;
    return 0;
}

void tud_hid_set_report_cb(uint8_t instance, uint8_t report_id,
                            hid_report_type_t report_type,
                            uint8_t const *buffer, uint16_t bufsize) {
    (void)instance; (void)report_id; (void)report_type; (void)buffer; (void)bufsize;
}

// ---- Main ----

int main(void) {
    board_init();
    stdio_init_all();
    tusb_init();

    gpio_init(PICO_DEFAULT_LED_PIN);
    gpio_set_dir(PICO_DEFAULT_LED_PIN, GPIO_OUT);

    printf("\nPico KBD Test ready\n");
    printf("Commands: T [count] [press_ms] [release_ms] [keycode]  S (status)\n");

    char line[80];
    int line_pos = 0;

    while (true) {
        tud_task();

        // Read UART characters
        int c = getchar_timeout_us(0);
        if (c != PICO_ERROR_TIMEOUT) {
            if (c == '\r' || c == '\n') {
                if (line_pos > 0) {
                    line[line_pos] = '\0';
                    process_command(line);
                    line_pos = 0;
                }
            } else if (line_pos < (int)sizeof(line) - 1) {
                line[line_pos++] = (char)c;
            }
        }
    }
}
