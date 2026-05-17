#!/usr/bin/env python3
"""V1.1 MCP3208 paddle-ADC live monitor.

Reads 10-byte packets from the adc_test FPGA bridge and prints the four
12-bit channel readings refreshed in place.

Packet:  0xFF 0xFF  <ch0_hi> <ch0_lo>  <ch1_hi> <ch1_lo>  <ch2_hi> <ch2_lo>  <ch3_hi> <ch3_lo>
"""

import argparse
import sys
import time

import serial


def find_sync(s, timeout=2.0):
    deadline = time.time() + timeout
    prev = 0
    while time.time() < deadline:
        b = s.read(1)
        if len(b) != 1:
            continue
        if prev == 0xFF and b[0] == 0xFF:
            return True
        prev = b[0]
    return False


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("port", nargs="?", default="/dev/ttyUSB0")
    ap.add_argument("baud", nargs="?", type=int, default=115_200)
    args = ap.parse_args()

    s = serial.Serial(args.port, args.baud, timeout=0.5)
    print(f"Reading {args.port} @ {args.baud}. Move paddles; Ctrl-C to quit.\n")
    print("CH0    CH1    CH2    CH3")

    if not find_sync(s):
        print("Couldn't lock to sync prefix 0xFF 0xFF — aborting.")
        return 1

    try:
        while True:
            payload = s.read(8)
            if len(payload) != 8:
                # try to resync
                if not find_sync(s):
                    print("\nLost sync.")
                    return 1
                continue
            chans = []
            for i in range(0, 8, 2):
                hi = payload[i] & 0x0F
                lo = payload[i + 1]
                chans.append((hi << 8) | lo)
            line = f"{chans[0]:4d}   {chans[1]:4d}   {chans[2]:4d}   {chans[3]:4d}"
            sys.stdout.write("\r" + line)
            sys.stdout.flush()

            # consume next sync for the following packet
            b = s.read(2)
            if b != b"\xff\xff":
                # try harder
                find_sync(s)
    except KeyboardInterrupt:
        print()
        return 0


if __name__ == "__main__":
    sys.exit(main())
