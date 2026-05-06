/*
 * USB descriptors for HID boot protocol keyboard.
 * CH376T requires: bInterfaceSubClass=1 (boot), bInterfaceProtocol=1 (keyboard).
 */
#include "tusb.h"

// HID report descriptor — standard boot protocol keyboard
static const uint8_t desc_hid_report[] = {
    TUD_HID_REPORT_DESC_KEYBOARD()
};

// Device descriptor
static const tusb_desc_device_t desc_device = {
    .bLength            = sizeof(tusb_desc_device_t),
    .bDescriptorType    = TUSB_DESC_DEVICE,
    .bcdUSB             = 0x0110,   // USB 1.1
    .bDeviceClass       = 0x00,     // defined at interface
    .bDeviceSubClass    = 0x00,
    .bDeviceProtocol    = 0x00,
    .bMaxPacketSize0    = CFG_TUD_ENDPOINT0_SIZE,
    .idVendor           = 0x1209,   // pid.codes test VID
    .idProduct          = 0x0001,
    .bcdDevice          = 0x0100,
    .iManufacturer      = 1,
    .iProduct           = 2,
    .iSerialNumber      = 3,
    .bNumConfigurations = 1,
};

uint8_t const *tud_descriptor_device_cb(void) {
    return (uint8_t const *)&desc_device;
}

// Configuration descriptor
#define CONFIG_TOTAL_LEN  (TUD_CONFIG_DESC_LEN + TUD_HID_DESC_LEN)

static const uint8_t desc_configuration[] = {
    // Config descriptor: index=1, num_interfaces=1, string=0, total_len, attributes, power
    TUD_CONFIG_DESCRIPTOR(1, 1, 0, CONFIG_TOTAL_LEN, TUSB_DESC_CONFIG_ATT_REMOTE_WAKEUP, 100),
    // HID interface: itf_num=0, string=0, boot_protocol=keyboard, report_desc_len, ep_in, ep_size, poll_interval
    TUD_HID_DESCRIPTOR(0, 0, HID_ITF_PROTOCOL_KEYBOARD, sizeof(desc_hid_report), 0x81, CFG_TUD_HID_EP_BUFSIZE, 10),
};

uint8_t const *tud_descriptor_configuration_cb(uint8_t index) {
    (void)index;
    return desc_configuration;
}

// HID report descriptor callback
uint8_t const *tud_hid_descriptor_report_cb(uint8_t instance) {
    (void)instance;
    return desc_hid_report;
}

// String descriptors
static const char *string_desc_arr[] = {
    (const char[]){0x09, 0x04},  // 0: English
    "PicoTest",                   // 1: Manufacturer
    "KbdTest",                    // 2: Product
    "000001",                     // 3: Serial
};

static uint16_t _desc_str[32];

uint16_t const *tud_descriptor_string_cb(uint8_t index, uint16_t langid) {
    (void)langid;
    uint8_t chr_count;

    if (index == 0) {
        memcpy(&_desc_str[1], string_desc_arr[0], 2);
        chr_count = 1;
    } else {
        if (index >= sizeof(string_desc_arr) / sizeof(string_desc_arr[0])) return NULL;
        const char *str = string_desc_arr[index];
        chr_count = strlen(str);
        if (chr_count > 31) chr_count = 31;
        for (uint8_t i = 0; i < chr_count; i++) {
            _desc_str[1 + i] = str[i];
        }
    }

    _desc_str[0] = (TUSB_DESC_STRING << 8) | (2 * chr_count + 2);
    return _desc_str;
}
