#!/usr/bin/env python3
"""Raw debug: send 'M' peek command and print everything received for 3 sec."""
import serial, time, sys

port = '/dev/ttyUSB0'
baud = 500000
addr = int(sys.argv[1], 16) if len(sys.argv) > 1 else 0x8000
length = int(sys.argv[2], 16) if len(sys.argv) > 2 else 0x0010

s = serial.Serial(port, baud, timeout=0.2)
time.sleep(0.1)
s.reset_input_buffer()

pkt = bytes([ord('M'), addr & 0xFF, (addr >> 8) & 0xFF,
             length & 0xFF, (length >> 8) & 0xFF])
print(f"SENT: {pkt.hex()}  (M {addr:04X} {length:04X})", file=sys.stderr)
# Pace bytes: JOP main loop can be blocked for 20+ ms by SIO disk reads,
# and UART RX is 1-byte buffered. 50 ms per byte is safe.
for b in pkt:
    s.write(bytes([b]))
    s.flush()
    time.sleep(0.05)

buf = b''
deadline = time.time() + 3.0
while time.time() < deadline:
    chunk = s.read(4096)
    if chunk:
        buf += chunk

print(f"GOT {len(buf)} bytes:", file=sys.stderr)
print(repr(buf))
try:
    print("---DECODED---")
    print(buf.decode('utf-8', errors='replace'))
except Exception as e:
    print(f"decode err: {e}", file=sys.stderr)
