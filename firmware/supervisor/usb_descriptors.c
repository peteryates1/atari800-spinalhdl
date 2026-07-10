/*
 * The MIT License (MIT)
 *
 * Copyright (c) 2019 Ha Thach (tinyusb.org)
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 *
 */

#include "bsp/board_api.h"
#include "tusb.h"

// Composite device, always presenting the Altera USB-Blaster identity so
// Quartus / jtagd recognise the cable, with the supervisor CDC console riding
// alongside (like an FTDI part that exposes both a vendor JTAG channel and a
// serial channel):
//   interface 0 = vendor-specific  -> FTDI USB-Blaster (FPGA JTAG programming)
//   interface 1 = CDC control      -> console
//   interface 2 = CDC data         -> console
// bDeviceClass stays 0 (per-interface), matching a real Blaster, so jtagd
// still claims interface 0; Linux cdc_acm binds the CDC pair via the union
// descriptor. No mode switching — one stable VID/PID for the whole session.
#define USB_VID   0x09fb   // Altera
#define USB_PID   0x6001   // Blaster

//--------------------------------------------------------------------+
// Device Descriptor
//--------------------------------------------------------------------+
tusb_desc_device_t const desc_device = {
    .bLength            = sizeof(tusb_desc_device_t),
    .bDescriptorType    = TUSB_DESC_DEVICE,
    .bcdUSB             = 0x0110,
    .bDeviceClass       = 0,     // per-interface (like a real USB-Blaster)
    .bDeviceSubClass    = 0,
    .bDeviceProtocol    = 0,
    .bMaxPacketSize0    = CFG_TUD_ENDPOINT0_SIZE,
    .idVendor           = USB_VID,
    .idProduct          = USB_PID,
    .bcdDevice          = 0x0400,
    .iManufacturer      = 0x01,
    .iProduct           = 0x02,
    .iSerialNumber      = 0x03,
    .bNumConfigurations = 0x01
};

uint8_t const* tud_descriptor_device_cb(void) {
  return (uint8_t const*) &desc_device;
}

//--------------------------------------------------------------------+
// Configuration Descriptor
//--------------------------------------------------------------------+
enum {
  ITF_NUM_VENDOR = 0,
  ITF_NUM_CDC,
  ITF_NUM_CDC_DATA,
  ITF_NUM_TOTAL
};

// Endpoints: vendor (Blaster) first, then CDC on distinct numbers.
#define EPNUM_VENDOR_IN   0x81
#define EPNUM_VENDOR_OUT  0x02
#define EPNUM_CDC_NOTIF   0x83
#define EPNUM_CDC_OUT     0x04
#define EPNUM_CDC_IN      0x84

// Vendor interface: class/subclass/protocol all 255, matching a real Blaster.
#define TUD_BLASTER_VENDOR_DESCRIPTOR(_itfnum, _stridx, _epout, _epin, _epsize) \
    9, TUSB_DESC_INTERFACE, _itfnum, 0, 2, TUSB_CLASS_VENDOR_SPECIFIC, TUSB_CLASS_VENDOR_SPECIFIC, TUSB_CLASS_VENDOR_SPECIFIC, _stridx, \
    7, TUSB_DESC_ENDPOINT, _epin, TUSB_XFER_BULK, U16_TO_U8S_LE(_epsize), 0, \
    7, TUSB_DESC_ENDPOINT, _epout, TUSB_XFER_BULK, U16_TO_U8S_LE(_epsize), 0

#define CONFIG_TOTAL_LEN  (TUD_CONFIG_DESC_LEN + TUD_VENDOR_DESC_LEN + TUD_CDC_DESC_LEN)

uint8_t const desc_fs_configuration[] = {
    // Config number, interface count, string index, total length, attribute, power in mA
    TUD_CONFIG_DESCRIPTOR(1, ITF_NUM_TOTAL, 0, CONFIG_TOTAL_LEN, 0x80, 150),

    // Vendor (USB-Blaster) interface
    TUD_BLASTER_VENDOR_DESCRIPTOR(ITF_NUM_VENDOR, 0, EPNUM_VENDOR_OUT, EPNUM_VENDOR_IN, CFG_TUD_VENDOR_EPSIZE),

    // CDC console interface
    TUD_CDC_DESCRIPTOR(ITF_NUM_CDC, 4, EPNUM_CDC_NOTIF, 8, EPNUM_CDC_OUT, EPNUM_CDC_IN, 64),
};

uint8_t const* tud_descriptor_configuration_cb(uint8_t index) {
  (void) index;
  return desc_fs_configuration;
}

//--------------------------------------------------------------------+
// String Descriptors
//--------------------------------------------------------------------+
enum {
  STRID_LANGID = 0,
  STRID_MANUFACTURER,
  STRID_PRODUCT,
  STRID_SERIAL,
};

char const* string_desc_arr[] = {
    (const char[]) {0x09, 0x04}, // 0: English (0x0409)
    "Pico",                        // 1: Manufacturer
    "USB Blaster",                 // 2: Product
    NULL,                          // 3: Serial (unique ID)
    "Blaster Console",             // 4: CDC interface
};

static uint16_t _desc_str[32 + 1];

uint16_t const* tud_descriptor_string_cb(uint8_t index, uint16_t langid) {
  (void) langid;
  size_t chr_count;

  switch (index) {
    case STRID_LANGID:
      memcpy(&_desc_str[1], string_desc_arr[0], 2);
      chr_count = 1;
      break;

    case STRID_SERIAL:
      chr_count = board_usb_get_serial(_desc_str + 1, 32);
      break;

    default:
      // Note: the 0xEE index string is a Microsoft OS 1.0 Descriptors.
      if (!(index < sizeof(string_desc_arr) / sizeof(string_desc_arr[0]))) return NULL;

      const char* str = string_desc_arr[index];

      chr_count = strlen(str);
      size_t const max_count = sizeof(_desc_str) / sizeof(_desc_str[0]) - 1;
      if (chr_count > max_count) chr_count = max_count;

      for (size_t i = 0; i < chr_count; i++) {
        _desc_str[1 + i] = str[i];
      }
      break;
  }

  // first byte is length (including header), second byte is string type
  _desc_str[0] = (uint16_t) ((TUSB_DESC_STRING << 8) | (2 * chr_count + 2));

  return _desc_str;
}
