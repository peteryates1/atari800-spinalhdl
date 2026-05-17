#!/usr/bin/env python3
"""W9825 SDRAM smoke test for V1.1 + 10CL025 core."""

import argparse
import sys
import time

import serial


SIZE_WORDS = 1 << 24   # 16 M words = 32 MB

def cmd(s, data):
    s.write(bytes(data)); s.flush()

def rd(s, n, lbl):
    g = s.read(n)
    if len(g) != n:
        raise RuntimeError(f"{lbl}: expected {n} bytes, got {len(g)}: {g!r}")
    return g

def set_addr(s, a):
    cmd(s, [ord("A"), a & 0xFF, (a >> 8) & 0xFF, (a >> 16) & 0xFF])
    assert rd(s, 1, "A") == b"A"

def get_addr(s):
    cmd(s, [ord("P")])
    b = rd(s, 3, "P")
    return b[0] | (b[1] << 8) | (b[2] << 16)

def write_word(s, w):
    cmd(s, [ord("W"), w & 0xFF, (w >> 8) & 0xFF])
    assert rd(s, 1, "W") == b"W"

def read_word(s):
    cmd(s, [ord("R")])
    b = rd(s, 2, "R")
    return b[0] | (b[1] << 8)

def fill(s, count, w):
    assert 1 <= count <= (1 << 24)
    c = count - 1
    cmd(s, [ord("F"),
            c & 0xFF, (c >> 8) & 0xFF, (c >> 16) & 0xFF,
            w & 0xFF, (w >> 8) & 0xFF])
    assert rd(s, 1, "F") == b"F"

def check(s, count, expected):
    assert 1 <= count <= (1 << 24)
    c = count - 1
    cmd(s, [ord("K"),
            c & 0xFF, (c >> 8) & 0xFF, (c >> 16) & 0xFF,
            expected & 0xFF, (expected >> 8) & 0xFF])
    return rd(s, 1, "K")[0]

def status(s):
    cmd(s, [ord("S")])
    return rd(s, 1, "S")[0]

def clear_flag(s):
    cmd(s, [ord("X")])
    assert rd(s, 1, "X") == b"X"

# ---------------------------------------------------------------------------

def test_sanity(s):
    print("\n--- sanity write/read at 8 addresses ---")
    base = 0x000000
    set_addr(s, base)
    for w in [0xDEAD, 0xBEEF, 0x1234, 0x5678, 0xCAFE, 0xBABE, 0xF00D, 0xFACE]:
        write_word(s, w)
    set_addr(s, base)
    fails = 0
    for i, w in enumerate([0xDEAD, 0xBEEF, 0x1234, 0x5678, 0xCAFE, 0xBABE, 0xF00D, 0xFACE]):
        r = read_word(s)
        flag = "OK" if r == w else "FAIL"
        print(f"  addr {base+i:08X}: wrote 0x{w:04X}  read 0x{r:04X}  [{flag}]")
        if r != w: fails += 1
    return fails

def test_data_walking(s):
    print("\n--- data-walking at address 0 ---")
    base = 0
    fails = 0
    patterns = [1 << i for i in range(16)] + [(~(1 << i)) & 0xFFFF for i in range(16)]
    for w in patterns:
        set_addr(s, base); write_word(s, w)
        set_addr(s, base); r = read_word(s)
        if r != w:
            print(f"  0x{w:04X} -> 0x{r:04X}  FAIL (xor 0x{w^r:04X})")
            fails += 1
    print(f"  -> {fails} fail(s) of {len(patterns)}")
    return fails

def test_address_walking(s):
    print("\n--- address-walking (writes at addr 0 + powers of two up to 2^23) ---")
    addrs = [0] + [1 << i for i in range(24)]
    addrs = [a for a in addrs if a < SIZE_WORDS]
    expected = {a: ((a & 0xFFFF) ^ ((a >> 16) & 0xFFFF) ^ 0xA55A) & 0xFFFF for a in addrs}

    # Pre-clear region around each tested address (single bursts of length 1
    # are slow, so we just rely on each addr being unique and write/verify only
    # those addresses).
    for a in addrs:
        set_addr(s, a); write_word(s, expected[a])

    fails = 0
    for a in sorted(addrs):
        set_addr(s, a); r = read_word(s)
        if r != expected[a]:
            print(f"  addr 0x{a:08X}: wrote 0x{expected[a]:04X}  read 0x{r:04X}  FAIL")
            fails += 1
    print(f"  -> {fails} fail(s) of {len(addrs)}")
    return fails

def test_block_fill(s, base, count, pattern):
    print(f"\n--- {count} words fill+check 0x{pattern:04X} @ 0x{base:08X} ---")
    clear_flag(s)
    set_addr(s, base)
    fill(s, count, pattern)
    set_addr(s, base)
    miscount = check(s, count, pattern)
    st = status(s)
    print(f"  miscount={miscount}  status=0x{st:02X}")
    return miscount

# ---------------------------------------------------------------------------

def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("port", nargs="?", default="/dev/ttyUSB0")
    ap.add_argument("baud", nargs="?", type=int, default=500_000)
    ap.add_argument("--quick", action="store_true")
    args = ap.parse_args()

    s = serial.Serial(args.port, args.baud, timeout=2)
    s.reset_input_buffer()

    # Bridge sync
    s.write(b"\x00" * 16); s.flush()
    time.sleep(0.05); s.reset_input_buffer()

    # Status / init check
    print(f"Status byte: 0x{status(s):02X}  (bit2 = init_done, bit1 = mismatch, bit0 = busy)")
    set_addr(s, 0x123456)
    a = get_addr(s)
    print(f"addr round-trip: wrote 0x123456, read 0x{a:06X}  [{'OK' if a == 0x123456 else 'FAIL'}]")
    if a != 0x123456:
        print("Address round-trip failed; aborting.")
        return 1

    total = 0
    total += test_sanity(s)
    total += test_data_walking(s)
    total += test_address_walking(s)
    total += test_block_fill(s, 0,          1 << 16, 0xA55A)   # 64K words = 128KB
    total += test_block_fill(s, 0,          1 << 16, 0x5AA5)
    if not args.quick:
        total += test_block_fill(s, 0,          1 << 20, 0xFFFF)   # 1M words = 2MB
        total += test_block_fill(s, 1 << 23,    1 << 20, 0xCAFE)   # 2MB at upper half

    print()
    print(f"{'ALL SDRAM TESTS PASSED' if total == 0 else f'SDRAM: {total} failure(s)'}")
    return 0 if total == 0 else 1


if __name__ == "__main__":
    sys.exit(main())
