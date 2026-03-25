"""CH376S UART test for Raspberry Pi Pico (MicroPython).

Wiring:
  CH376S P3    Pico
  ----------   ----
  TXD          GP13 (UART0 RX)
  RXD          GP12 (UART0 TX)
  GND          GND
  RSTI         GP4  (GPIO output, active HIGH reset)
  INT#         GP5  (GPIO input, active low)

All jumpers removed = UART/SER mode (J1=OFF, J6=OFF, J5=OFF).
Default baud: 9600, 8N1.

Upload to Pico running MicroPython, then run:
  import ch376s_uart_test
  ch376s_uart_test.main()
"""

from machine import Pin, UART
import time


# --- Pin setup ---
rst_pin = Pin(4, Pin.OUT, value=0)   # RSTI: active HIGH reset, start normal (low)
int_pin = Pin(5, Pin.IN, Pin.PULL_UP)  # INT#: active low

# UART0 at 9600 baud (CH376S default after reset)
uart = UART(0, baudrate=9600, tx=Pin(12), rx=Pin(13), timeout=500)

# --- CH376S commands ---
CMD_GET_IC_VER  = 0x01
CMD_RESET_ALL   = 0x05
CMD_CHECK_EXIST = 0x06
CMD_SET_USB_MODE = 0x15
CMD_GET_STATUS  = 0x22


def ch376_cmd(cmd, data=None):
    """Send command via UART: 0x57 0xAB <cmd> [data...]"""
    pkt = bytes([0x57, 0xAB, cmd])
    if data is not None:
        if isinstance(data, int):
            pkt += bytes([data])
        else:
            pkt += bytes(data)
    uart.write(pkt)


def ch376_read(n=1, timeout_ms=500):
    """Read n bytes from UART with timeout."""
    deadline = time.ticks_add(time.ticks_ms(), timeout_ms)
    result = b''
    while len(result) < n:
        remaining = time.ticks_diff(deadline, time.ticks_ms())
        if remaining <= 0:
            break
        chunk = uart.read(n - len(result))
        if chunk:
            result += chunk
        else:
            time.sleep_ms(1)
    return result


def test_reset():
    """Hardware reset via RSTI pin (active HIGH)."""
    print("=== Hardware reset ===")
    rst_pin.value(1)        # assert reset (HIGH)
    time.sleep_ms(50)
    rst_pin.value(0)        # release reset (LOW)
    time.sleep_ms(100)      # wait >35ms for CH376S to initialize
    # Drain any bytes sent during reset
    uart.read()
    print("  Reset complete")


def test_int_pin():
    """Read INT# pin state."""
    val = int_pin.value()
    print(f"  INT#: {'HIGH (inactive)' if val else 'LOW (active)'}")


def test_check_exist():
    """CHECK_EXIST: send test byte, expect bitwise complement back."""
    print("=== CHECK_EXIST (CMD 0x06) ===")
    for test_val in [0xA5, 0x55, 0x00, 0xFF, 0x57]:
        expected = (~test_val) & 0xFF
        uart.read()  # drain
        ch376_cmd(CMD_CHECK_EXIST, test_val)
        resp = ch376_read(1, 500)
        if resp:
            r = resp[0]
            ok = "OK" if r == expected else "FAIL"
            print(f"  TX 0x{test_val:02X} expect 0x{expected:02X}: "
                  f"rx=0x{r:02X}  {ok}")
        else:
            print(f"  TX 0x{test_val:02X} expect 0x{expected:02X}: "
                  f"NO RESPONSE")


def test_get_version():
    """GET_IC_VER: read chip version."""
    print("=== GET_IC_VER (CMD 0x01) ===")
    uart.read()  # drain
    ch376_cmd(CMD_GET_IC_VER)
    resp = ch376_read(1, 500)
    if resp:
        r = resp[0]
        ver = r & 0x3F
        print(f"  raw=0x{r:02X} version={ver}")
    else:
        print("  NO RESPONSE")


def test_raw_rx():
    """Check if any unsolicited bytes arrive (e.g. after reset)."""
    print("=== Raw RX check (500ms) ===")
    data = ch376_read(16, 500)
    if data:
        hex_str = ' '.join(f'0x{b:02X}' for b in data)
        print(f"  Received {len(data)} bytes: {hex_str}")
    else:
        print("  No data")


def main():
    print("\n*** CH376S UART Test ***\n")

    test_int_pin()

    print("\n--- Hardware reset ---")
    test_reset()
    test_int_pin()
    test_raw_rx()

    test_check_exist()
    test_get_version()

    print("\n--- Software reset + retry ---")
    ch376_cmd(CMD_RESET_ALL)
    time.sleep_ms(200)
    uart.read()  # drain

    test_int_pin()
    test_check_exist()
    test_get_version()

    print("\nDone.")


if __name__ == "__main__":
    main()
