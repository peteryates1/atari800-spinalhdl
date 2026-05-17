#!/usr/bin/env python3
"""V1.1 input change logger.

Reads 2-byte packets from the input_test FPGA bridge and prints one line per
detected state change (press or release), with which signal changed.

Useful for verifying that each physical switch / joystick direction maps to
the expected bit.
"""

import argparse
import sys
import time

import serial


SIGNALS = [
    ("RST", 0, 7),
    ("OPT", 0, 6),
    ("SEL", 0, 5),
    ("STA", 0, 4),
    ("J1T", 0, 3),
    ("J1R", 0, 2),
    ("J1L", 0, 1),
    ("J1D", 0, 0),
    ("J1U", 1, 7),
    ("J2T", 1, 6),
    ("J2R", 1, 5),
    ("J2L", 1, 4),
    ("J2D", 1, 3),
    ("J2U", 1, 2),
]


def decode(pkt):
    return {name: bool(pkt[idx] & (1 << bit)) for name, idx, bit in SIGNALS}


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("port", nargs="?", default="/dev/ttyUSB0")
    ap.add_argument("baud", nargs="?", type=int, default=115_200)
    args = ap.parse_args()

    s = serial.Serial(args.port, args.baud, timeout=0.3)
    print(f"Reading from {args.port} @ {args.baud}. Press inputs; Ctrl-C to quit.\n")

    last = None
    while True:
        try:
            pkt = s.read(2)
        except KeyboardInterrupt:
            print()
            return
        if len(pkt) != 2:
            continue
        state = decode(pkt)
        if last is None:
            # initial snapshot
            pressed_now = [k for k, v in state.items() if v]
            t = time.strftime("%H:%M:%S")
            if pressed_now:
                print(f"[{t}] initial pressed: {' '.join(pressed_now)}")
            else:
                print(f"[{t}] initial: all released")
            last = state
            continue
        for name in (n for n, _, _ in SIGNALS):
            if state[name] != last[name]:
                t = time.strftime("%H:%M:%S")
                evt = "PRESS  " if state[name] else "release"
                print(f"[{t}] {evt}  {name}")
        last = state


if __name__ == "__main__":
    main()
