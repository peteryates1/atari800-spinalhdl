#!/usr/bin/env python3
"""V1.1 CH376T dual-bus probe.

Bridge protocol (matches ch376_test.sv, 500000 baud, 8N1):
    'B' <0|1>      -> 'B'              select bus (0=KB, 1=SD)
    'T' <byte>     -> <miso_byte>      transfer one SPI byte
    'C' <0|1>      -> -                CS asserted=1 / deasserted=0
    'D' <lo> <hi>  -> 'D'              set SPI clock divider
    'P'            -> 0/1              read MISO pin level
    'I'            -> 0/1              read INT# pin level (0 = active)
    'R'            -> 'R'              hardware reset of selected CH376T (~84 ms)
    'L'            -> 0/1              PLL lock status

Per CH376T datasheet:
    CMD_GET_IC_VER     0x01
    CMD_RESET_ALL      0x05  (~50 ms recovery)
    CMD_CHECK_EXIST    0x06  arg X -> ~X
    CMD_SET_USB_MODE   0x15  arg mode -> 0x51 success / 0x5F abort
    CMD_GET_STATUS     0x22
    CMD_DISK_CONNECT   0x30  SD-mode: probe SD card -> INT then GET_STATUS
    CMD_DISK_MOUNT     0x31  SD-mode: mount FAT     -> INT then GET_STATUS
    CMD_RD_USB_DATA0   0x27  read FAT_DISK_INFO

    USB_INT_SUCCESS    0x14
    USB_INT_CONNECT    0x15
    USB_INT_DISCONNECT 0x16
"""

import sys
import time

import serial


def cmd(s, data):
    s.write(bytes(data))
    s.flush()


def rd(s, n, label):
    got = s.read(n)
    if len(got) != n:
        raise RuntimeError(f"{label}: expected {n} bytes, got {len(got)}: {got!r}")
    return got


def select_bus(s, bus):
    cmd(s, [ord("B"), bus])
    assert rd(s, 1, "B") == b"B"


def cs(s, asserted):
    cmd(s, [ord("C"), 1 if asserted else 0])


def xfer(s, byte):
    cmd(s, [ord("T"), byte & 0xFF])
    return rd(s, 1, f"T {byte:02X}")[0]


def reset_all(s):
    cs(s, True);  xfer(s, 0x05);  cs(s, False)
    time.sleep(0.06)


def check_exist(s, val=0x55):
    # 3-byte SPI transaction: write CMD, write ARG, then read the response.
    # (For IC_VER / GET_STATUS the response comes on byte #2 because there is
    # no argument; CHECK_EXIST has an argument so the response lands on #3.)
    cs(s, True)
    xfer(s, 0x06)
    xfer(s, val)
    r = xfer(s, 0x00)
    cs(s, False)
    return r == ((~val) & 0xFF)


def set_usb_mode(s, mode):
    cs(s, True)
    xfer(s, 0x15)
    xfer(s, mode)
    time.sleep(0.005)              # CH376T needs ~20us, we give 5ms
    r = xfer(s, 0x00)
    cs(s, False)
    return r


def get_status(s):
    cs(s, True)
    xfer(s, 0x22)
    r = xfer(s, 0x00)
    cs(s, False)
    return r


def get_ic_ver(s):
    cs(s, True)
    xfer(s, 0x01)
    r = xfer(s, 0x00)
    cs(s, False)
    return r


def int_low(s):
    cmd(s, [ord("I")])
    return rd(s, 1, "I")[0] == 0


def wait_int(s, timeout_ms=500):
    end = time.time() + timeout_ms / 1000.0
    while time.time() < end:
        if int_low(s):
            return True
        time.sleep(0.002)
    return False


def disk_connect(s, timeout_ms=500):
    cs(s, True);  xfer(s, 0x30);  cs(s, False)
    if not wait_int(s, timeout_ms):
        return None
    return get_status(s)


def disk_mount(s, timeout_ms=2000):
    cs(s, True);  xfer(s, 0x31);  cs(s, False)
    if not wait_int(s, timeout_ms):
        return None
    return get_status(s)


def read_usb_data0(s):
    cs(s, True)
    xfer(s, 0x27)
    length = xfer(s, 0x00)
    data = bytes(xfer(s, 0x00) for _ in range(length))
    cs(s, False)
    return data


def banner(name):
    print()
    print(f"=== {name} ===")


def probe_kb(s):
    banner("CH376T_KB (USB host)")
    select_bus(s, 0)
    time.sleep(0.05)

    print(f"IC_VER         : 0x{get_ic_ver(s):02X}")

    ok55 = check_exist(s, 0x55)
    okAA = check_exist(s, 0xAA)
    print(f"CHECK_EXIST    : 0x55->{ 'OK' if ok55 else 'FAIL'}, 0xAA->{ 'OK' if okAA else 'FAIL'}")

    reset_all(s)
    print(f"IC_VER (post-R): 0x{get_ic_ver(s):02X}")

    # Mode 0x06: USB host, generate SOF (required for full/low-speed devices).
    rmode = set_usb_mode(s, 0x06)
    print(f"SET_USB_MODE 06: 0x{rmode:02X}  ({'success' if rmode == 0x51 else 'abort'})")

    # After mode switch, CH376T should signal device connect via INT# if a
    # device is plugged in.
    if wait_int(s, 1500):
        status = get_status(s)
        names = {0x15: "USB_INT_CONNECT",
                 0x16: "USB_INT_DISCONNECT",
                 0x14: "USB_INT_SUCCESS"}
        label = names.get(status & 0x1F, "unknown")
        print(f"INT after mode : status=0x{status:02X} ({label})")
        if (status & 0x1F) == 0x15:
            print("                  USB device detected on KB port ✓")
    else:
        print("INT after mode : timeout — no USB device detected on KB port")


def hw_reset(s):
    """Pulse RST# on the selected CH376T (the bridge's 'R' command)."""
    cmd(s, [ord("R")])
    rd(s, 1, "R")


def set_spi_divider(s, div):
    cmd(s, [ord("D"), div & 0xFF, (div >> 8) & 0xFF])
    rd(s, 1, "D")


ERR_NAMES = {
    0x14: "USB_INT_SUCCESS",
    0x15: "USB_INT_CONNECT",
    0x16: "USB_INT_DISCONNECT",
    0x17: "USB_INT_BUF_OVER",
    0x1D: "USB_INT_DISK_READ",
    0x1F: "USB_INT_DISK_ERR",
    0x41: "ERR_OPEN_DIR",
    0x42: "ERR_MISS_FILE",
    0x82: "ERR_DISK_DISCON",
    0x84: "ERR_LARGE_SECTOR",
    0x92: "ERR_TYPE_ERROR",
    0xA1: "ERR_BPB_ERROR",
}


def status_name(code):
    return ERR_NAMES.get(code, "?")


def probe_sd(s):
    banner("CH376T_SD (SD card)")
    select_bus(s, 1)

    # Slow the FPGA-to-CH376T SPI to ~100 kHz (sdiv=249 → 50e6/(2*250) = 100k).
    # Just to rule out FPGA-side SPI timing as a factor.
    set_spi_divider(s, 249)
    print("SPI clock      : ~100 kHz (sdiv=249)")

    # Hard reset the chip via its RST# pin before doing anything else.
    hw_reset(s)
    time.sleep(0.05)

    print(f"IC_VER         : 0x{get_ic_ver(s):02X}")

    ok55 = check_exist(s, 0x55)
    okAA = check_exist(s, 0xAA)
    print(f"CHECK_EXIST    : 0x55->{ 'OK' if ok55 else 'FAIL'}, 0xAA->{ 'OK' if okAA else 'FAIL'}")

    reset_all(s)  # software reset to default state
    time.sleep(0.1)

    # Mode 0x03: SD-card host mode.
    rmode = set_usb_mode(s, 0x03)
    print(f"SET_USB_MODE 03: 0x{rmode:02X}  ({'success' if rmode == 0x51 else 'abort'})")
    time.sleep(0.2)  # give the chip 200 ms to settle into SD mode

    # Surface any pending interrupt before mounting.
    if int_low(s):
        st = get_status(s)
        print(f"Pending INT    : 0x{st:02X}  ({status_name(st)})")
    else:
        print("Pending INT    : none")

    # Try DISK_MOUNT up to 3 times with progressively longer waits.
    for attempt in range(1, 4):
        mstat = disk_mount(s, 5000)
        if mstat is None:
            print(f"DISK_MOUNT #{attempt}  : timeout")
        else:
            label = status_name(mstat)
            print(f"DISK_MOUNT #{attempt}  : 0x{mstat:02X}  ({label})")
            if mstat == 0x14:
                break
        time.sleep(0.2)
    else:
        return  # all attempts failed

    info = read_usb_data0(s)
    print(f"FAT_DISK_INFO  : {info.hex()}  ({len(info)} bytes)")


def main():
    port = sys.argv[1] if len(sys.argv) > 1 else "/dev/ttyUSB0"
    baud = int(sys.argv[2]) if len(sys.argv) > 2 else 500_000

    s = serial.Serial(port, baud, timeout=0.5)
    s.reset_input_buffer()

    # Bridge may be mid-command from a previous run. Send harmless 0x00 bytes
    # — these will either be eaten by the IDLE FSM or finish a stuck multi-byte
    # command (worst case: latches a CS state we'll re-set below). Then drain.
    s.write(b"\x00" * 8)
    s.flush()
    time.sleep(0.05)
    s.reset_input_buffer()

    cmd(s, [ord("L")])
    locked = rd(s, 1, "L")[0]
    print(f"PLL locked     : {locked}")
    if not locked:
        print("WARNING: PLL not locked — CH376Ts have no clock; bail.")
        return

    probe_kb(s)
    probe_sd(s)


if __name__ == "__main__":
    main()
