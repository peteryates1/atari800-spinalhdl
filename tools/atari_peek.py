#!/usr/bin/env python3
"""
atari_peek.py — peek Atari 800 RAM via JOP supervisor 'M' command.

JOP supervisor accepts a binary 'M' command:
    'M' <addrLo> <addrHi> <lenLo> <lenHi>
and replies with:
    M<addr>:<len>\n
    AAAA: BB BB BB ...\n
    ...
    END\n

Only Atari CPU addresses $4000-$BFFF are SDRAM-backed and readable.
Other addresses (internal BRAM: $0000-$3FFF, OS ROM: $C000-$FFFF) print "??".

Usage:
    tools/atari_peek.py [--port /dev/ttyUSB0] [--baud 500000] <addr> [<len>]
    tools/atari_peek.py 4000 100      # dump 256 bytes from $4000
    tools/atari_peek.py 9C00          # dump 256 bytes from $9C00 (default len)
    tools/atari_peek.py --dl          # dump typical DL area (scan + decode hint)

If running concurrently with atari_keyboard.py, stop that first — both use
the same UART.
"""

import argparse
import sys
import time

try:
    import serial
except ImportError:
    print("pyserial not installed: pip install pyserial", file=sys.stderr)
    sys.exit(1)


def parse_hex(s: str) -> int:
    s = s.strip()
    if s.startswith("$"):
        s = s[1:]
    if s.startswith("0x") or s.startswith("0X"):
        s = s[2:]
    return int(s, 16)


def peek(ser: serial.Serial, addr: int, length: int) -> str:
    # Drain any pending output first
    ser.reset_input_buffer()
    pkt = bytes([
        ord('M'),
        addr & 0xFF,
        (addr >> 8) & 0xFF,
        length & 0xFF,
        (length >> 8) & 0xFF,
    ])
    # Pace bytes: JOP main loop can be blocked 20+ ms by SIO disk reads,
    # and the UART RX path is 1-byte buffered. 50 ms per byte is safe.
    for b in pkt:
        ser.write(bytes([b]))
        ser.flush()
        time.sleep(0.05)

    # Read until we see "END\n" or timeout
    deadline = time.time() + 5.0
    buf = bytearray()
    while time.time() < deadline:
        chunk = ser.read(4096)
        if chunk:
            buf.extend(chunk)
            if b"END\n" in buf:
                break
        else:
            if buf:
                # No more data coming and we have something — give it a moment
                time.sleep(0.05)
                tail = ser.read(4096)
                if tail:
                    buf.extend(tail)
                if b"END\n" in buf:
                    break
                if time.time() > deadline - 0.5:
                    break

    text = buf.decode(errors="replace")
    # Trim anything before the M<addr>: header (may have stray logs from supervisor)
    hdr = f"M{addr:04X}:"
    i = text.find(hdr)
    if i >= 0:
        text = text[i:]
    # Stop at END\n
    j = text.find("END\n")
    if j >= 0:
        text = text[: j + 4]
    return text


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--port", default="/dev/ttyUSB0")
    ap.add_argument("--baud", type=int, default=500000)
    ap.add_argument("addr", help="start address (hex, e.g. 4000 or $4000)")
    ap.add_argument("length", nargs="?", default="100",
                    help="byte count (hex, default 100 = 256)")
    args = ap.parse_args()

    addr = parse_hex(args.addr)
    length = parse_hex(args.length)
    if length <= 0 or length > 0x1000:
        print(f"length must be 1..1000 hex (got {length:X})", file=sys.stderr)
        sys.exit(2)

    ser = serial.Serial(args.port, args.baud, timeout=0.2)
    out = peek(ser, addr, length)
    sys.stdout.write(out)
    if not out.endswith("\n"):
        sys.stdout.write("\n")


if __name__ == "__main__":
    main()
