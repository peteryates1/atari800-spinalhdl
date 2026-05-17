#!/usr/bin/env python3
"""V1.1 switches + joysticks live monitor.

Reads 2-byte packets from the input_test FPGA bridge and prints a one-line
status that refreshes in place. Highlights currently pressed inputs.

Packet (matches input_test.sv):
    byte 0 bits: [sw_reset][sw_option][sw_select][sw_start]
                 [js1_trig][js1_right][js1_left][js1_down]
    byte 1 bits: [js1_up][js2_trig][js2_right][js2_left]
                 [js2_down][js2_up][0][0]
"""

import argparse
import sys
import time

import serial


# (name, byte_index, bit_position) — bit 7 is MSB of the byte.
SIGNALS = [
    ("RST",  0, 7),
    ("OPT",  0, 6),
    ("SEL",  0, 5),
    ("STA",  0, 4),
    ("J1T",  0, 3),
    ("J1R",  0, 2),
    ("J1L",  0, 1),
    ("J1D",  0, 0),
    ("J1U",  1, 7),
    ("J2T",  1, 6),
    ("J2R",  1, 5),
    ("J2L",  1, 4),
    ("J2D",  1, 3),
    ("J2U",  1, 2),
]

GREEN = "\033[1;32m"
DIM   = "\033[2m"
RESET = "\033[0m"


def decode(pkt):
    state = {}
    for name, idx, bit in SIGNALS:
        state[name] = bool(pkt[idx] & (1 << bit))
    return state


def fmt(state):
    parts = []
    for name, _, _ in SIGNALS:
        if state[name]:
            parts.append(f"{GREEN}{name}{RESET}")
        else:
            parts.append(f"{DIM}{name}{RESET}")
    return " ".join(parts)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("port", nargs="?", default="/dev/ttyUSB1")
    ap.add_argument("baud", nargs="?", type=int, default=115_200)
    args = ap.parse_args()

    s = serial.Serial(args.port, args.baud, timeout=0.3)

    print("Press any switch or move a joystick. Ctrl-C to exit.\n")
    print("Legend: RST=Reset OPT=Option SEL=Select STA=Start "
          "J1{U,D,L,R,T}=Joystick1 J2{U,D,L,R,T}=Joystick2\n")

    last_line = ""
    try:
        # Re-sync to packet boundary: read bytes until we get a multiple of 2.
        # The protocol has no explicit framing, so we rely on packet timing —
        # the FPGA sends 2 bytes back-to-back then waits ~50 ms. We just keep
        # reading 2 bytes at a time.
        while True:
            pkt = s.read(2)
            if len(pkt) != 2:
                continue
            state = decode(pkt)
            line = fmt(state)
            # Print in place
            sys.stdout.write("\r" + line)
            sys.stdout.flush()
            last_line = line
    except KeyboardInterrupt:
        print()


if __name__ == "__main__":
    main()
