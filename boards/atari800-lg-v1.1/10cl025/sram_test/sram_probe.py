#!/usr/bin/env python3
"""V1.1 SRAM exerciser — talks to the sram_test FPGA bridge.

Tests, in order:
  1.  Sanity: write+read a few bytes at addr 0.
  2.  Data-walking at addr 0 (0x01, 0x02, ..., 0x80, then 0xFE, 0xFD, ...,
      0x7F) — catches stuck/shorted data bits.
  3.  Address-walking: write a unique value at addresses 0, 1, 2, 4, 8, ...,
      0x10000 (powers of two), then read back — catches stuck/shorted address
      bits.
  4.  Full-fill patterns: 0x00, 0xFF, 0x55, 0xAA over the entire 128 KB,
      verifying with a single hardware K command per chunk.
  5.  Address-as-data: write low byte of address to each location, verify
      (catches address-line and bit-flip faults that the constant fills miss).

Bridge protocol (matches sram_test.sv, 500000 baud, 8N1):
  'A' a0 a1 a2          set 17-bit current address. Reply: 'A'
  'P'                   get current address.        Reply: a0 a1 a2
  'W' d                 write+inc.                  Reply: 'W'
  'R'                   read+inc.                   Reply: <byte>
  'F' c0 c1 d           fill (c+1) bytes from addr. Reply: 'F'
  'K' c0 c1 e           check (c+1) bytes match.    Reply: <miscount>
  'S'                   status.                     Reply: <byte>
  'X'                   clear mismatch flag.        Reply: 'X'
"""

import argparse
import random
import sys
import time

import serial


SIZE = 1 << 17  # 128 KB


def cmd(s, data):
    s.write(bytes(data))
    s.flush()


def rd(s, n, label="?"):
    got = s.read(n)
    if len(got) != n:
        raise RuntimeError(f"{label}: expected {n} bytes, got {len(got)}: {got!r}")
    return got


def set_addr(s, addr):
    cmd(s, [ord("A"), addr & 0xFF, (addr >> 8) & 0xFF, (addr >> 16) & 0x01])
    assert rd(s, 1, "A") == b"A"


def get_addr(s):
    cmd(s, [ord("P")])
    b = rd(s, 3, "P")
    return b[0] | (b[1] << 8) | ((b[2] & 1) << 16)


def write_byte(s, d):
    cmd(s, [ord("W"), d & 0xFF])
    assert rd(s, 1, "W") == b"W"


def read_byte(s):
    cmd(s, [ord("R")])
    return rd(s, 1, "R")[0]


def fill(s, count, d):
    """Fill `count` bytes starting at current address with d. count <= 65536."""
    assert 1 <= count <= 65536
    c = count - 1
    cmd(s, [ord("F"), c & 0xFF, (c >> 8) & 0xFF, d & 0xFF])
    assert rd(s, 1, "F") == b"F"


def check(s, count, expected):
    """Verify `count` bytes starting at current address all == expected. Returns
    saturated mismatch count (0..255). count <= 65536."""
    assert 1 <= count <= 65536
    c = count - 1
    cmd(s, [ord("K"), c & 0xFF, (c >> 8) & 0xFF, expected & 0xFF])
    return rd(s, 1, "K")[0]


def clear_flag(s):
    cmd(s, [ord("X")])
    assert rd(s, 1, "X") == b"X"


# ---------------------------------------------------------------------------
# Tests
# ---------------------------------------------------------------------------

def test_sanity(s):
    print("\n--- sanity write/read at addr 0..7 ---")
    set_addr(s, 0)
    for i, d in enumerate([0xDE, 0xAD, 0xBE, 0xEF, 0x12, 0x34, 0x56, 0x78]):
        write_byte(s, d)
    set_addr(s, 0)
    for i, d in enumerate([0xDE, 0xAD, 0xBE, 0xEF, 0x12, 0x34, 0x56, 0x78]):
        got = read_byte(s)
        flag = "OK" if got == d else "FAIL"
        print(f"  addr {i:5d}: wrote 0x{d:02X}  read 0x{got:02X}  [{flag}]")


def test_data_walking(s):
    print("\n--- data-walking at addr 0 (catches stuck data bits) ---")
    patterns = [1 << i for i in range(8)] + [(~(1 << i)) & 0xFF for i in range(8)]
    fails = 0
    for d in patterns:
        set_addr(s, 0)
        write_byte(s, d)
        set_addr(s, 0)
        got = read_byte(s)
        flag = "OK" if got == d else f"FAIL (xor 0x{got ^ d:02X})"
        print(f"  pattern 0x{d:02X}  read 0x{got:02X}  [{flag}]")
        if got != d:
            fails += 1
    print(f"  -> {fails} fail(s)")
    return fails


def test_address_walking(s):
    """Write addr-derived values at addresses 0, 1, 2, 4, ..., 2^16. Then
    verify. Catches stuck or shorted address bits."""
    print("\n--- address-walking (writes at addresses 0 + powers of two) ---")
    addrs = [0] + [1 << i for i in range(17)]
    addrs = [a for a in addrs if a < SIZE]
    # Use a value derived from address so each location is unique.
    expected = {a: (a & 0xFF) ^ ((a >> 8) & 0xFF) ^ ((a >> 16) & 0xFF) ^ 0xA5
                for a in addrs}

    # First fill EVERYTHING with 0x00 so unintended writes show up as 0x00.
    print("  pre-clearing memory to 0x00...")
    set_addr(s, 0)
    fill(s, 65536, 0x00)
    set_addr(s, 65536)
    fill(s, 65536, 0x00)

    for a in addrs:
        set_addr(s, a)
        write_byte(s, expected[a])

    fails = 0
    for a in sorted(addrs):
        set_addr(s, a)
        got = read_byte(s)
        if got != expected[a]:
            print(f"  addr 0x{a:05X}: wrote 0x{expected[a]:02X}  read 0x{got:02X}  FAIL")
            fails += 1
    print(f"  -> {fails} fail(s) of {len(addrs)} addresses")
    return fails


def test_full_fill(s, pattern):
    print(f"\n--- full 128KB fill+check with 0x{pattern:02X} ---")
    clear_flag(s)
    set_addr(s, 0)
    fill(s, 65536, pattern)
    fill(s, 65536, pattern)
    set_addr(s, 0)
    bad1 = check(s, 65536, pattern)
    bad2 = check(s, 65536, pattern)
    total = bad1 + bad2
    # Status to check the sticky mismatch flag too.
    cmd(s, [ord("S")])
    st = rd(s, 1, "S")[0]
    print(f"  mismatch count (saturated): {total}   status=0x{st:02X}")
    return total


def test_addr_as_data(s):
    print("\n--- address-as-data over 128KB ---")
    # Write each location's value = LSB(addr) XOR MID(addr).
    # We need to drive this from the host (no hw command for it), so we
    # do chunked writes.
    CHUNK = 4096
    print("  writing...")
    t0 = time.time()
    for base in range(0, SIZE, CHUNK):
        set_addr(s, base)
        # Build the chunk's command stream: alternating 'W' <data> bytes.
        buf = bytearray()
        for i in range(CHUNK):
            a = base + i
            d = (a & 0xFF) ^ ((a >> 8) & 0xFF) ^ ((a >> 16) & 0xFF)
            buf += bytes([ord("W"), d])
        s.write(buf)
        s.flush()
        # Consume the CHUNK 'W' acks
        acks = rd(s, CHUNK, "W*chunk")
        assert acks == b"W" * CHUNK
    print(f"  write took {time.time()-t0:.1f}s")

    print("  reading + verifying...")
    t0 = time.time()
    fails = 0
    bad_examples = []
    for base in range(0, SIZE, CHUNK):
        set_addr(s, base)
        s.write(b"R" * CHUNK)
        s.flush()
        got = rd(s, CHUNK, "R*chunk")
        for i, b in enumerate(got):
            a = base + i
            exp = (a & 0xFF) ^ ((a >> 8) & 0xFF) ^ ((a >> 16) & 0xFF)
            if b != exp:
                fails += 1
                if len(bad_examples) < 8:
                    bad_examples.append((a, exp, b))
    print(f"  read took {time.time()-t0:.1f}s")
    print(f"  -> {fails} fail(s)")
    for a, exp, got in bad_examples:
        print(f"     addr 0x{a:05X}: wrote 0x{exp:02X}  read 0x{got:02X}  "
              f"xor 0x{exp ^ got:02X}")
    return fails


# ---------------------------------------------------------------------------
def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("port", nargs="?", default="/dev/ttyUSB0")
    ap.add_argument("baud", nargs="?", type=int, default=500_000)
    ap.add_argument("--quick", action="store_true",
                    help="skip the slow address-as-data sweep")
    args = ap.parse_args()

    s = serial.Serial(args.port, args.baud, timeout=0.5)
    s.reset_input_buffer()

    # Bridge sync: send a few harmless 0x00 in case the FPGA was mid-command.
    s.write(b"\x00" * 16)
    s.flush()
    time.sleep(0.05)
    s.reset_input_buffer()

    # Probe: set/read addr round-trip
    set_addr(s, 0xABCDE & (SIZE - 1))
    a = get_addr(s)
    print(f"addr round-trip: wrote 0x{0xABCDE & (SIZE-1):05X}, read 0x{a:05X}  "
          f"[{'OK' if a == (0xABCDE & (SIZE - 1)) else 'FAIL'}]")
    if a != (0xABCDE & (SIZE - 1)):
        print("ERROR: bridge address round-trip failed — aborting.")
        return 1

    total_fails = 0
    test_sanity(s)
    total_fails += test_data_walking(s)
    total_fails += test_address_walking(s)
    for pat in (0x00, 0xFF, 0x55, 0xAA):
        total_fails += test_full_fill(s, pat)
    if not args.quick:
        total_fails += test_addr_as_data(s)

    print()
    if total_fails == 0:
        print("ALL SRAM TESTS PASSED ✓")
    else:
        print(f"SRAM TESTS: {total_fails} total failure(s)")
    return 0 if total_fails == 0 else 1


if __name__ == "__main__":
    sys.exit(main())
