#!/usr/bin/env python3
"""
CH376T SPI tester — talks to ch376_test_top FPGA design.
Binary protocol: 'T'<byte> = SPI transfer, 'C'<0|1> = CS, 'D'<lo><hi> = divider.

Usage: python3 ch376_spi_test.py [/dev/ttyUSB0] [500000]
"""
import serial, sys, time

PORT = sys.argv[1] if len(sys.argv) > 1 else '/dev/ttyUSB0'
BAUD = int(sys.argv[2]) if len(sys.argv) > 2 else 500000

ser = None

def open_port():
    global ser
    ser = serial.Serial(PORT, BAUD, timeout=1)
    ser.reset_input_buffer()
    time.sleep(0.1)
    ser.reset_input_buffer()

def spi_cs(on):
    """Assert (on=True) or deassert CS."""
    ser.write(bytes([ord('C'), 1 if on else 0]))
    ser.flush()

def spi_byte(tx):
    """Transfer one byte. Returns MISO byte."""
    ser.write(bytes([ord('T'), tx & 0xFF]))
    ser.flush()
    r = ser.read(1)
    if len(r) != 1:
        print(f"  SPI timeout!")
        return 0xFF
    return r[0]

def spi_xfer(tx_bytes):
    """CS-framed SPI transfer. Returns list of MISO bytes."""
    spi_cs(True)
    time.sleep(0.001)  # CS setup
    rx = []
    for b in tx_bytes:
        rx.append(spi_byte(b))
    spi_cs(False)
    return rx

def spi_cmd(cmd_byte, data_bytes=[], read_count=0):
    """Send CH376 command + data, read response bytes. CS-framed."""
    spi_cs(True)
    time.sleep(0.001)
    spi_byte(cmd_byte)          # command
    time.sleep(0.002)           # CH376T post-command delay (>1.5us)
    for b in data_bytes:
        spi_byte(b)
    rx = [spi_byte(0xFF) for _ in range(read_count)]
    spi_cs(False)
    return rx

def set_div(div):
    """Set SPI clock divider."""
    ser.write(bytes([ord('D'), div & 0xFF, (div >> 8) & 0xFF]))
    ser.flush()
    r = ser.read(1)
    freq_khz = 50000 / (2 * (div + 1))
    print(f"  SPI div={div}  clock={freq_khz:.0f} kHz  ack={'D' if r == b'D' else repr(r)}")

def read_miso():
    """Read MISO pin state (no clock)."""
    ser.write(b'P')
    ser.flush()
    r = ser.read(1)
    return r[0] if r else -1

def reset_ch376():
    """Hardware reset via RST pin."""
    print("  HW reset...")
    ser.write(b'R')
    ser.flush()
    r = ser.read(1)
    time.sleep(0.15)  # wait for CH376T init
    print(f"  ack={'R' if r == b'R' else repr(r)}")

def fmt(data):
    return ' '.join(f'{b:02X}' for b in data)

def check_exist(test_val):
    expected = (~test_val) & 0xFF
    rx = spi_cmd(0x06, [test_val], 1)
    got = rx[0] if rx else -1
    ok = "OK" if got == expected else "FAIL"
    print(f"  CHECK_EXIST(0x{test_val:02X}): got 0x{got:02X} exp 0x{expected:02X}  {ok}")
    return got == expected

def get_ic_ver():
    rx = spi_cmd(0x01, [], 1)
    ver = rx[0] & 0x3F if rx else -1
    print(f"  GET_IC_VER: raw=0x{rx[0]:02X} ver={ver}")
    return ver

# =========================================================
def main():
    print(f"CH376T SPI test — {PORT} @ {BAUD}")
    open_port()

    # Wait for boot reset to complete (~84ms)
    time.sleep(0.2)

    print("\n--- MISO pin state (idle) ---")
    m = read_miso()
    print(f"  MISO = {m}")

    print("\n--- Raw SPI: send 0xFF with CS (should see pull-up) ---")
    rx = spi_xfer([0xFF, 0xFF, 0xFF, 0xFF])
    print(f"  TX: FF FF FF FF")
    print(f"  RX: {fmt(rx)}")

    print("\n--- Slow clock (div=499, ~50 kHz) ---")
    set_div(499)

    print("\n--- CHECK_EXIST ---")
    check_exist(0xA5)
    check_exist(0x55)
    check_exist(0x00)
    check_exist(0xFF)

    print("\n--- GET_IC_VER ---")
    get_ic_ver()

    print("\n--- Software reset (CMD 0x05) + retry ---")
    spi_cmd(0x05)  # RESET_ALL
    time.sleep(0.15)
    check_exist(0xA5)
    get_ic_ver()

    print("\n--- Hardware reset + retry ---")
    reset_ch376()
    check_exist(0xA5)
    get_ic_ver()

    print("\n--- Faster clock (div=49, ~500 kHz) ---")
    set_div(49)
    check_exist(0xA5)

    print("\n--- Fast clock (div=24, ~1 MHz) ---")
    set_div(24)
    check_exist(0xA5)

    print("\n--- Very slow clock (div=2499, ~10 kHz) ---")
    set_div(2499)
    check_exist(0xA5)

    print("\n--- Raw dump: CE(0xA5) cmd + 6 dummy reads ---")
    set_div(499)
    spi_cs(True)
    time.sleep(0.001)
    r_cmd = spi_byte(0x06)
    time.sleep(0.005)           # 5ms post-command
    r_dat = spi_byte(0xA5)
    time.sleep(0.002)
    r = [spi_byte(0xFF) for _ in range(6)]
    spi_cs(False)
    print(f"  cmd=0x{r_cmd:02X} dat=0x{r_dat:02X} dummies={fmt(r)}")

    print("\nDone.")
    ser.close()

if __name__ == '__main__':
    main()
