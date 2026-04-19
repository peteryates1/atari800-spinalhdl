#!/usr/bin/env python3
"""
CH376T USB keyboard tester — talks to ch376_test_top FPGA design.
Uses CH376's built-in high-level USB host commands (matching FPGC6 approach).

Usage: python3 ch376_keyboard.py [/dev/ttyUSB0] [500000]
"""
import serial, sys, time

PORT = sys.argv[1] if len(sys.argv) > 1 else '/dev/ttyUSB0'
BAUD = int(sys.argv[2]) if len(sys.argv) > 2 else 500000

ser = None

# ---- CH376 command codes ----
CMD_GET_IC_VER    = 0x01
CMD_SET_USB_SPEED = 0x04
CMD_RESET_ALL     = 0x05
CMD_CHECK_EXIST   = 0x06
CMD_SET_RETRY     = 0x0B
CMD_SET_USB_ADDR  = 0x13  # set target device address for transactions
CMD_SET_USB_MODE  = 0x15
CMD_SET_ENDP6     = 0x1C  # set IN endpoint toggle
CMD_SET_ENDP7     = 0x1D  # set OUT endpoint toggle
CMD_GET_STATUS    = 0x22
CMD_RD_USB_DATA   = 0x28  # read USB data (host mode)
CMD_WR_HOST_DATA  = 0x2C  # write data to host TX buffer
CMD_CLR_STALL     = 0x41
CMD_SET_ADDRESS   = 0x45  # high-level: send USB SET_ADDRESS
CMD_SET_CONFIG    = 0x49  # high-level: send USB SET_CONFIGURATION
CMD_ISSUE_TOKEN   = 0x4F  # simple token: 1 byte = (ep<<4)|PID

# ---- USB interrupt status codes ----
USB_INT_SUCCESS   = 0x14
USB_INT_CONNECT   = 0x15
USB_INT_DISCONNECT= 0x16

# ---- USB PIDs ----
PID_IN    = 0x09

# ---- HID keycodes to ASCII (partial) ----
HID_KEYMAP = {
    0x04: 'a', 0x05: 'b', 0x06: 'c', 0x07: 'd', 0x08: 'e', 0x09: 'f',
    0x0A: 'g', 0x0B: 'h', 0x0C: 'i', 0x0D: 'j', 0x0E: 'k', 0x0F: 'l',
    0x10: 'm', 0x11: 'n', 0x12: 'o', 0x13: 'p', 0x14: 'q', 0x15: 'r',
    0x16: 's', 0x17: 't', 0x18: 'u', 0x19: 'v', 0x1A: 'w', 0x1B: 'x',
    0x1C: 'y', 0x1D: 'z', 0x1E: '1', 0x1F: '2', 0x20: '3', 0x21: '4',
    0x22: '5', 0x23: '6', 0x24: '7', 0x25: '8', 0x26: '9', 0x27: '0',
    0x28: 'RET', 0x29: 'ESC', 0x2A: 'BS', 0x2B: 'TAB', 0x2C: 'SPC',
    0x2D: '-', 0x2E: '=', 0x2F: '[', 0x30: ']', 0x31: '\\',
    0x33: ';', 0x34: "'", 0x35: '`', 0x36: ',', 0x37: '.', 0x38: '/',
    0x39: 'CAPS', 0x3A: 'F1', 0x3B: 'F2', 0x3C: 'F3', 0x3D: 'F4',
    0x3E: 'F5', 0x3F: 'F6', 0x40: 'F7', 0x41: 'F8', 0x42: 'F9',
    0x43: 'F10', 0x44: 'F11', 0x45: 'F12',
    0x4F: 'RIGHT', 0x50: 'LEFT', 0x51: 'DOWN', 0x52: 'UP',
}

# ---- Low-level SPI/UART bridge ----

def open_port():
    global ser
    ser = serial.Serial(PORT, BAUD, timeout=1)
    ser.reset_input_buffer()
    time.sleep(0.1)
    ser.reset_input_buffer()

def spi_cs(on):
    ser.write(bytes([ord('C'), 1 if on else 0]))
    ser.flush()

def spi_byte(tx):
    ser.write(bytes([ord('T'), tx & 0xFF]))
    ser.flush()
    r = ser.read(1)
    if len(r) != 1:
        return 0xFF
    return r[0]

def spi_cmd(cmd_byte, data_bytes=[], read_count=0):
    """Send CH376 command + data, read response bytes."""
    spi_cs(True)
    time.sleep(0.001)
    spi_byte(cmd_byte)
    time.sleep(0.002)
    for b in data_bytes:
        spi_byte(b)
    rx = [spi_byte(0xFF) for _ in range(read_count)]
    spi_cs(False)
    return rx

def set_div(div):
    ser.write(bytes([ord('D'), div & 0xFF, (div >> 8) & 0xFF]))
    ser.flush()
    r = ser.read(1)
    freq_khz = 50000 / (2 * (div + 1))
    print(f"  SPI div={div}  clock={freq_khz:.0f} kHz")

def read_int_pin():
    """Read INT# pin state. Returns 0 (active/asserted) or 1 (idle)."""
    ser.write(b'I')
    ser.flush()
    r = ser.read(1)
    return r[0] if r else -1

def wait_int_hw(timeout_count=1):
    """Wait for INT# to go low using FPGA hardware (up to ~84ms per count).
    Returns True if INT# fired, False on timeout."""
    for _ in range(timeout_count):
        ser.write(b'W')
        ser.flush()
        r = ser.read(1)
        if r == b'W':
            return True
    return False

def fmt(data):
    return ' '.join(f'{b:02X}' for b in data)

# ---- CH376 helpers ----

def check_exist(test_val):
    expected = (~test_val) & 0xFF
    rx = spi_cmd(CMD_CHECK_EXIST, [test_val], 1)
    got = rx[0] if rx else -1
    ok = "OK" if got == expected else "FAIL"
    print(f"  CHECK_EXIST(0x{test_val:02X}): got 0x{got:02X} exp 0x{expected:02X}  {ok}")
    return got == expected

def get_status():
    """GET_STATUS — returns interrupt status byte."""
    rx = spi_cmd(CMD_GET_STATUS, [], 1)
    return rx[0] if rx else 0xFF

def wait_interrupt(timeout_counts=24):
    """Wait for INT# pin, then read GET_STATUS. ~84ms per count."""
    if wait_int_hw(timeout_counts):
        return get_status()
    return None

def drain_interrupts():
    """Drain all pending interrupt events."""
    drained = []
    for _ in range(10):
        if read_int_pin() != 0:
            break
        st = get_status()
        drained.append(st)
        time.sleep(0.01)
    return drained

def set_usb_mode(mode):
    """SET_USB_MODE. Returns True if result == 0x51."""
    rx = spi_cmd(CMD_SET_USB_MODE, [mode], 1)
    result = rx[0] if rx else 0xFF
    mode_names = {0x05: 'host/no-SOF', 0x06: 'host/SOF', 0x07: 'host/reset'}
    name = mode_names.get(mode, f'0x{mode:02X}')
    print(f"  SET_USB_MODE({name}): result=0x{result:02X} {'OK' if result == 0x51 else 'FAIL'}")
    return result == 0x51

def read_usb_data():
    """RD_USB_DATA (0x28) — read data from CH376 host buffer.
    Returns (length, data_bytes)."""
    spi_cs(True)
    time.sleep(0.001)
    spi_byte(CMD_RD_USB_DATA)
    time.sleep(0.002)
    length = spi_byte(0xFF)
    data = [spi_byte(0xFF) for _ in range(length)]
    spi_cs(False)
    return length, data

def decode_hid_report(report):
    """Decode standard 8-byte HID keyboard boot protocol report."""
    if not report or len(report) < 8:
        return "(short report)"
    modifiers = report[0]
    keys = report[2:8]

    mod_names = []
    if modifiers & 0x01: mod_names.append("L-Ctrl")
    if modifiers & 0x02: mod_names.append("L-Shift")
    if modifiers & 0x04: mod_names.append("L-Alt")
    if modifiers & 0x08: mod_names.append("L-GUI")
    if modifiers & 0x10: mod_names.append("R-Ctrl")
    if modifiers & 0x20: mod_names.append("R-Shift")
    if modifiers & 0x40: mod_names.append("R-Alt")
    if modifiers & 0x80: mod_names.append("R-GUI")

    pressed = []
    for k in keys:
        if k == 0:
            continue
        if k == 1:
            pressed.append("ROLLOVER")
        else:
            name = HID_KEYMAP.get(k, f"0x{k:02X}")
            if modifiers & 0x22:
                name = name.upper()
            pressed.append(name)

    mod_str = '+'.join(mod_names) if mod_names else ''
    key_str = '+'.join(pressed) if pressed else '(none)'
    if mod_str and pressed:
        return f"{mod_str}+{key_str}"
    elif mod_str:
        return mod_str
    else:
        return key_str

# =========================================================
def main():
    print(f"CH376T USB keyboard test — {PORT} @ {BAUD}")
    open_port()

    time.sleep(0.2)  # wait for boot reset

    print("\n--- Verify CH376T ---")
    set_div(499)  # 50 kHz — safe after reset
    if not check_exist(0xA5):
        print("CH376T not responding!")
        return

    # Software reset
    print("\n--- Reset CH376T ---")
    spi_cmd(CMD_RESET_ALL)
    time.sleep(0.1)
    check_exist(0xA5)

    # Speed up SPI
    print("\n--- Speed up SPI ---")
    set_div(49)  # 500 kHz
    check_exist(0xA5)

    # Step 1: Enter host mode WITHOUT SOF (mode 0x05)
    # This is how FPGC6 does it — mode 5 first, wait for connection
    print("\n--- Set USB host mode (0x05 = host, no SOF) ---")
    if not set_usb_mode(0x05):
        print("Failed to enter host mode!")
        return

    print("\n--- Waiting for USB device... (plug in keyboard) ---")
    print("  Press Ctrl-C to abort")

    try:
        while True:
            int_pin = read_int_pin()
            if int_pin == 0:
                st = get_status()
                if st == USB_INT_CONNECT:
                    print(f"  Device connected! (status=0x{st:02X})")
                    break
                elif st == USB_INT_DISCONNECT:
                    print(f"  Disconnect event")
                else:
                    print(f"  Status: 0x{st:02X}")
            time.sleep(0.05)
    except KeyboardInterrupt:
        print("\nAborted.")
        ser.close()
        return

    # Step 2+3: Bus reset + enumerate (with retries for flaky connections)
    enumerated = False
    for attempt in range(5):
        if attempt > 0:
            print(f"\n--- Retry {attempt}/4 ---")
            # Wait for device to reconnect
            set_usb_mode(0x05)  # host, no SOF
            time.sleep(0.1)
            drain_interrupts()
            st = wait_interrupt(24)
            if st != USB_INT_CONNECT:
                print(f"  No reconnect (status=0x{st:02X})" if st else "  No reconnect (timeout)")
                continue

        # Bus reset
        print("\n--- Bus reset ---")
        set_usb_mode(0x07)  # assert SE0
        time.sleep(0.02)    # hold 20ms
        set_usb_mode(0x06)  # SOF

        # Wait for reconnect, drain all events until INT# is stable idle
        st = wait_interrupt(24)
        if st:
            status_name = {0x15: 'CONNECT', 0x16: 'DISCONNECT'}.get(st, f'0x{st:02X}')
            print(f"  post-reset: {status_name}")
            if st == USB_INT_DISCONNECT:
                st = wait_interrupt(24)
                if st:
                    print(f"  then: {status_name}")

        # Aggressive drain
        time.sleep(0.02)
        while read_int_pin() == 0:
            s = get_status()
            print(f"  drain: 0x{s:02X}")
            time.sleep(0.01)

        # Try low-speed first (keyboard was low-speed in successful run)
        spi_cmd(CMD_SET_USB_ADDR, [0x00])
        spi_cmd(CMD_SET_USB_SPEED, [0x02])  # low-speed 1.5 Mbps
        time.sleep(0.01)
        while read_int_pin() == 0:
            s = get_status()
            print(f"  speed-drain: 0x{s:02X}")
            time.sleep(0.01)

        print(f"  SET_ADDRESS(1) [attempt {attempt}]...")
        spi_cmd(CMD_SET_ADDRESS, [0x01])
        st = wait_interrupt(12)
        if st == USB_INT_SUCCESS:
            print(f"  SET_ADDRESS: OK!")
            enumerated = True
            break
        else:
            print(f"  SET_ADDRESS: 0x{st:02X}" if st else "  SET_ADDRESS: timeout")
            drain_interrupts()

    if not enumerated:
        print("\n  Enumeration failed after all retries!")
        check_exist(0xA5)
        ser.close()
        return

    spi_cmd(CMD_SET_USB_ADDR, [0x01])

    # SET_CONFIG: CH376 sends USB SET_CONFIGURATION(1) internally
    print("\n--- SET_CONFIG(1) via CH376 built-in ---")
    spi_cmd(CMD_SET_CONFIG, [0x01])
    st = wait_interrupt()
    print(f"  SET_CONFIG result: 0x{st:02X}" if st else "  SET_CONFIG timeout")
    if st != USB_INT_SUCCESS:
        print("  SET_CONFIG failed!")
        ser.close()
        return

    print("\n--- Keyboard enumerated! ---")

    # Step 5: Poll keyboard on endpoint 1
    # Token byte = (endpoint << 4) | PID_IN = (1 << 4) | 0x09 = 0x19
    TOKEN_EP1_IN = 0x19

    print("  Polling endpoint 1 for HID reports...")
    print("  Press keys on USB keyboard. Ctrl-C to exit.\n")

    toggle = 0  # DATA0/DATA1 toggle for endpoint 1
    prev_report = None
    try:
        while True:
            # Set IN toggle
            spi_cmd(CMD_SET_ENDP6, [0xC0 if toggle else 0x80])
            # Issue IN token to endpoint 1
            spi_cmd(CMD_ISSUE_TOKEN, [TOKEN_EP1_IN])
            # Wait for result
            st = wait_interrupt(2)  # ~168ms timeout
            if st == USB_INT_SUCCESS:
                toggle ^= 1  # flip toggle on success
                n, data = read_usb_data()
                if data != prev_report:
                    decoded = decode_hid_report(data)
                    print(f"  [{fmt(data)}]  {decoded}")
                    prev_report = list(data)
            elif st == 0x2A:
                pass  # NAK — no new data, normal
            elif st is not None:
                print(f"  Poll error: 0x{st:02X}")
                if st == 0x2E:  # STALL
                    spi_cmd(CMD_CLR_STALL, [0x81])  # clear stall on EP1 IN
                    wait_interrupt(2)
            time.sleep(0.01)  # small delay between polls
    except KeyboardInterrupt:
        print("\n\nDone.")

    ser.close()

if __name__ == '__main__':
    main()
