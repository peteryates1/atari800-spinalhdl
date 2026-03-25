"""CH376S SPI test for Raspberry Pi Pico (MicroPython).

Wiring:
  CH376S    Pico
  ------    ----
  sck       GP2  (SPI0 SCK)
  sdi       GP3  (SPI0 TX / MOSI)
  sdo       GP0  (SPI0 RX / MISO)
  scs       GP1  (SPI0 CSn)
  rsti      GP4  (GPIO output)
  int#      GP5  (GPIO input, active low)

Upload to Pico running MicroPython, then run:
  import ch376s_test
  ch376s_test.main()
"""

from machine import Pin, SPI
import time


# --- Pin setup ---
rst_pin = Pin(4, Pin.OUT, value=0)   # RSTI: active HIGH reset, start in normal mode (low)
int_pin = Pin(5, Pin.IN, Pin.PULL_UP)  # INT#: active low
cs_pin  = Pin(1, Pin.OUT, value=1)   # SCS: active low, start deasserted

# SPI0: 1 MHz for init, mode 0 (CPOL=0, CPHA=0), MSB first
spi = SPI(0, baudrate=1_000_000, polarity=0, phase=0,
          sck=Pin(2), mosi=Pin(3), miso=Pin(0))

# --- CH376S commands ---
CMD_RESET_ALL   = 0x05
CMD_CHECK_EXIST = 0x06
CMD_GET_IC_VER  = 0x01
CMD_SET_USB_MODE = 0x15
CMD_GET_STATUS  = 0x22


def cs_assert():
    cs_pin.value(0)

def cs_deassert():
    cs_pin.value(1)

def spi_xfer(tx_byte):
    """Send one byte, return received byte."""
    buf = bytearray(1)
    spi.write_readinto(bytes([tx_byte]), buf)
    return buf[0]

def ch376_cmd(cmd):
    """Send CH376S command header + command byte."""
    cs_assert()
    time.sleep_us(2)         # CS setup time
    spi_xfer(0x57)
    time.sleep_us(2)
    spi_xfer(0xAB)
    time.sleep_us(2)
    spi_xfer(cmd)
    time.sleep_us(2)         # command processing time

def ch376_end():
    cs_deassert()

def ch376_cmd_data(cmd, data):
    ch376_cmd(cmd)
    spi_xfer(data)
    ch376_end()


def test_raw_spi():
    """Test raw SPI: send bytes with CS deasserted, expect 0xFF (MISO pull-up)."""
    print("=== Raw SPI test (CS deasserted) ===")
    cs_deassert()
    for val in [0x00, 0x55, 0xAA, 0xFF]:
        r = spi_xfer(val)
        print(f"  TX 0x{val:02X} -> RX 0x{r:02X}  {'OK' if r == 0xFF else 'UNEXPECTED'}")


def test_reset():
    """Hardware reset via RSTI pin (active HIGH)."""
    print("=== Hardware reset ===")
    rst_pin.value(1)        # assert reset (HIGH)
    time.sleep_ms(50)
    rst_pin.value(0)        # release reset (LOW)
    time.sleep_ms(100)      # wait >35ms for CH376S to initialize
    print("  Reset complete (50ms high, 100ms wait)")


def test_sw_reset():
    """Software reset via CMD_RESET_ALL."""
    print("=== Software reset (CMD 0x05) ===")
    ch376_cmd(CMD_RESET_ALL)
    ch376_end()
    time.sleep_ms(100)
    print("  Reset sent, waited 100ms")


def test_check_exist():
    """CHECK_EXIST: send test byte, expect bitwise complement back."""
    print("=== CHECK_EXIST (CMD 0x06) ===")
    for test_val in [0xA5, 0x55, 0x00, 0xFF]:
        expected = (~test_val) & 0xFF
        ch376_cmd(CMD_CHECK_EXIST)
        r1 = spi_xfer(test_val)   # send test byte, capture simultaneous rx
        time.sleep_us(2)
        r2 = spi_xfer(0xFF)       # read 1 byte later
        time.sleep_us(2)
        r3 = spi_xfer(0xFF)       # read 2 bytes later
        ch376_end()
        ok = "OK" if r1 == expected else ("OK(r2)" if r2 == expected else
             ("OK(r3)" if r3 == expected else "FAIL"))
        print(f"  TX 0x{test_val:02X} expect 0x{expected:02X}: "
              f"r1=0x{r1:02X} r2=0x{r2:02X} r3=0x{r3:02X}  {ok}")


def test_raw_bus():
    """Send raw bytes and dump all responses to see echo/shift pattern."""
    print("=== Raw bus dump (CS asserted, 8 bytes) ===")
    cs_assert()
    time.sleep_us(2)
    tx = [0x57, 0xAB, 0x06, 0xA5, 0xFF, 0xFF, 0xFF, 0xFF]
    for i, t in enumerate(tx):
        r = spi_xfer(t)
        time.sleep_us(2)
        print(f"  [{i}] TX=0x{t:02X}  RX=0x{r:02X}")
    ch376_end()


def test_get_version():
    """GET_IC_VER: read chip version."""
    print("=== GET_IC_VER (CMD 0x01) ===")
    ch376_cmd(CMD_GET_IC_VER)
    r1 = spi_xfer(0xFF)
    r2 = spi_xfer(0xFF)
    ch376_end()
    ver = r1 & 0x3F
    print(f"  r1=0x{r1:02X} (ver={ver}) r2=0x{r2:02X}")


def test_int_pin():
    """Read INT# pin state."""
    val = int_pin.value()
    print(f"=== INT# pin: {'HIGH (inactive)' if val else 'LOW (active)'} ===")


def main():
    print("\n*** CH376S SPI Test ***\n")

    test_int_pin()
    test_raw_spi()

    print("\n--- Hardware reset ---")
    test_reset()
    test_raw_bus()

    test_int_pin()
    test_check_exist()
    test_get_version()

    print("\n--- Software reset + retry ---")
    test_sw_reset()

    test_int_pin()
    test_check_exist()
    test_get_version()

    # Try slower SPI
    print("\n--- Retry at 400 kHz ---")
    spi.init(baudrate=400_000)
    test_sw_reset()
    test_check_exist()
    test_get_version()

    # Try different SPI modes
    for mode, (cpol, cpha) in enumerate([(0,0), (0,1), (1,0), (1,1)]):
        print(f"\n--- SPI Mode {mode} (CPOL={cpol}, CPHA={cpha}) at 1 MHz ---")
        spi.init(baudrate=1_000_000, polarity=cpol, phase=cpha)
        test_reset()
        test_check_exist()
        test_get_version()

    print("\nDone.")


if __name__ == "__main__":
    main()
