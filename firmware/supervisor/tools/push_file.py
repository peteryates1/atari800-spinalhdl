#!/usr/bin/env python3
# Push a local file onto the supervisor's SD card over USB (CDC), bypassing the
# MSC drive entirely (no host write-back cache, no eject footgun). The firmware
# 'U' command receives "<path>\n<length>\n<raw bytes>" and writes it straight to
# SD. Used by the board Makefile 'push-core' target.
#
#   push_file.py <local-file> <sd-path> [serial-port]
#   push_file.py Atari800WukongTop.bit /atari/800/core.bit
#
# The serial port defaults to the first Atari800 supervisor CDC in
# /dev/serial/by-id. Requires pyserial.
import sys, time, glob, os

def find_port():
    for pat in ("/dev/serial/by-id/*Supervisor*", "/dev/serial/by-id/*Atari800*"):
        m = sorted(glob.glob(pat))
        if m:
            return m[0]
    return None

def main():
    if len(sys.argv) < 3:
        print("usage: push_file.py <local-file> <sd-path> [serial-port]", file=sys.stderr)
        return 2
    local, sdpath = sys.argv[1], sys.argv[2]
    port = sys.argv[3] if len(sys.argv) > 3 else find_port()
    if not port:
        print("push: no supervisor serial port found (pass one explicitly)", file=sys.stderr)
        return 1
    try:
        import serial
    except ImportError:
        print("push: pyserial not installed (pip install pyserial)", file=sys.stderr)
        return 1

    data = open(local, "rb").read()
    total = len(data)
    print(f"push: {local} -> {sdpath} ({total} bytes) via {port}")

    p = serial.Serial(port, 115200, timeout=2)
    p.dtr = True
    time.sleep(0.3)
    p.reset_input_buffer()

    # Command + header, then the raw bytes.
    p.write(b"U")
    p.write((sdpath + "\n").encode())
    p.write((str(total) + "\n").encode())
    p.flush()
    # Let the firmware open the file and print "receiving ...".
    time.sleep(0.2)

    sent = 0
    CH = 4096
    last_pct = -1
    isatty = sys.stdout.isatty()
    t0 = time.time()
    while sent < total:
        n = p.write(data[sent:sent + CH])   # blocks under USB flow-control if SD is slower
        sent += n if n else 0
        pct = sent * 100 // total
        if pct != last_pct and (isatty or pct % 10 == 0):   # every 1% on a tty, else every 10%
            sys.stdout.write(f"\rpush: {sent}/{total} ({pct}%)" + ("" if isatty else "\n"))
            sys.stdout.flush()
            last_pct = pct
    p.flush()
    dt = time.time() - t0
    print(f"\rpush: sent {sent} bytes in {dt:.1f}s ({sent/1024/max(dt,0.001):.0f} KB/s)      ")

    # Drain the firmware's result line(s).
    end = time.time() + 4
    out = b""
    while time.time() < end:
        chunk = p.read(4096)
        if chunk:
            out += chunk
            end = time.time() + 0.5
    p.close()
    text = out.decode(errors="replace").strip()
    if text:
        print(text)
    return 0 if ("DONE" in text) else 1

if __name__ == "__main__":
    sys.exit(main())
