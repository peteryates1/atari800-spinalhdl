#!/usr/bin/env python3
"""Serial keyboard/joystick relay for Atari 800 + JOP supervisor.

Sends keystrokes to AtariSupervisor via UART.

Controls:
  F1 = Start,  F2 = Select,  F3 = Option,  F4 = Cold Reset
  Arrow keys = Joystick directions,  Right Alt or \\ = Fire
  Normal keys = Atari keyboard input
  Ctrl+C / Esc = Quit

Protocol (to JOP):
  'K' <lo> <hi>  keyboard: lo[5:0]=scancode, lo[6]=shift, lo[7]=ctrl
                           hi: bit0=pressed, bit1=break
  'C' <bits>     console: bit0=option, bit1=select, bit2=start
  'J' <bits>     joystick 1: [4:0]=up,down,left,right,fire (active-low)
  'R'            cold reset
"""

import sys
import os
import tty
import termios
import select
import struct
import serial
import time

# PC key -> Atari scan code mapping
# Atari scan codes from keyboard matrix
KEY_MAP = {
    'a': 0x3F, 'b': 0x15, 'c': 0x12, 'd': 0x3A, 'e': 0x2A,
    'f': 0x38, 'g': 0x3D, 'h': 0x39, 'i': 0x0D, 'j': 0x01,
    'k': 0x05, 'l': 0x00, 'm': 0x25, 'n': 0x23, 'o': 0x08,
    'p': 0x0A, 'q': 0x2F, 'r': 0x28, 's': 0x3E, 't': 0x2D,
    'u': 0x0B, 'v': 0x10, 'w': 0x2E, 'x': 0x16, 'y': 0x2B,
    'z': 0x17,
    'A': 0x3F, 'B': 0x15, 'C': 0x12, 'D': 0x3A, 'E': 0x2A,
    'F': 0x38, 'G': 0x3D, 'H': 0x39, 'I': 0x0D, 'J': 0x01,
    'K': 0x05, 'L': 0x00, 'M': 0x25, 'N': 0x23, 'O': 0x08,
    'P': 0x0A, 'Q': 0x2F, 'R': 0x28, 'S': 0x3E, 'T': 0x2D,
    'U': 0x0B, 'V': 0x10, 'W': 0x2E, 'X': 0x16, 'Y': 0x2B,
    'Z': 0x17,
    '1': 0x1F, '2': 0x1E, '3': 0x1A, '4': 0x18, '5': 0x1D,
    '6': 0x1B, '7': 0x33, '8': 0x35, '9': 0x30, '0': 0x32,
    '\r': 0x0C, '\n': 0x0C,  # Enter/Return
    '\x1b': 0x1C,             # Escape (raw, not sequence)
    '\x7f': 0x34,             # Backspace (Delete key)
    '\x08': 0x34,             # Backspace
    '\t': 0x2C,               # Tab
    ' ': 0x21,                # Space
    '-': 0x06, '=': 0x07,
    ';': 0x02, ',': 0x20, '.': 0x22, '/': 0x26,
    '!': 0x1F, '@': 0x1E, '#': 0x1A, '$': 0x18, '%': 0x1D,
    '^': 0x1B, '&': 0x33, '*': 0x35, '(': 0x30, ')': 0x32,
    '_': 0x06, '+': 0x07,
    ':': 0x02, '<': 0x20, '>': 0x22, '?': 0x26,
}

# Characters that imply shift
SHIFT_CHARS = set('ABCDEFGHIJKLMNOPQRSTUVWXYZ!@#$%^&*()_+:<>?')


def send_key(ser, scancode, pressed, shift=False, ctrl=False, brk=False):
    lo = (scancode & 0x3F) | (0x40 if shift else 0) | (0x80 if ctrl else 0)
    hi = (1 if pressed else 0) | (2 if brk else 0)
    pkt = b'K' + bytes([lo, hi])
    sys.stderr.write(f"TX: K {lo:02X} {hi:02X}\n")
    ser.write(pkt)


def send_console(ser, option=False, select=False, start=False):
    bits = (1 if option else 0) | (2 if select else 0) | (4 if start else 0)
    ser.write(b'C' + bytes([bits]))


def send_joystick(ser, up=False, down=False, left=False, right=False, fire=False):
    # Active-low: 0 = pressed, 1 = released
    # Bit order matches JoystickPort.packed: [4]=fire, [3]=right, [2]=left, [1]=down, [0]=up
    bits = ((0 if fire else 1) << 4) | ((0 if right else 1) << 3) | \
           ((0 if left else 1) << 2) | ((0 if down else 1) << 1) | \
           (0 if up else 1)
    ser.write(b'J' + bytes([bits]))


def read_escape_seq(fd):
    """Read an escape sequence from terminal. Returns descriptive string."""
    # We already consumed \x1b. Check for [ (CSI)
    if not select.select([fd], [], [], 0.05)[0]:
        return 'ESC'
    ch = os.read(fd, 1)
    if ch == b'[':
        seq = b''
        while True:
            if not select.select([fd], [], [], 0.05)[0]:
                break
            c = os.read(fd, 1)
            seq += c
            if c and c[0] >= 0x40:  # final byte
                break
        if seq == b'A': return 'UP'
        if seq == b'B': return 'DOWN'
        if seq == b'C': return 'RIGHT'
        if seq == b'D': return 'LEFT'
        # F1-F4: \x1b[OP through \x1b[OS  or \x1b[[A through \x1b[[D
        # or \x1bOP through \x1bOS
        if seq == b'[A' or seq == b'11~': return 'F1'
        if seq == b'[B' or seq == b'12~': return 'F2'
        if seq == b'[C' or seq == b'13~': return 'F3'
        if seq == b'[D' or seq == b'14~': return 'F4'
        if seq == b'15~': return 'F5'
        return f'CSI_{seq.decode("ascii", errors="replace")}'
    elif ch == b'O':
        c = os.read(fd, 1) if select.select([fd], [], [], 0.05)[0] else b''
        if c == b'P': return 'F1'
        if c == b'Q': return 'F2'
        if c == b'R': return 'F3'
        if c == b'S': return 'F4'
        return f'O_{c.decode("ascii", errors="replace")}'
    return 'ESC'


def main():
    port = sys.argv[1] if len(sys.argv) > 1 else '/dev/ttyUSB1'
    baud = int(sys.argv[2]) if len(sys.argv) > 2 else 2000000

    ser = serial.Serial(port, baud, timeout=0)

    # Joystick state
    joy_up = joy_down = joy_left = joy_right = joy_fire = False

    fd = sys.stdin.fileno()
    old_settings = termios.tcgetattr(fd)

    print(f"Atari Keyboard Relay on {port} @ {baud}")
    print("F1/F5=Start F2=Select F3=Option F4=Reset")
    print("Arrows=Joystick  \\=Fire  Ctrl+C=Quit")
    print("---")

    try:
        tty.setraw(fd)

        while True:
            # Check for keyboard input
            if select.select([fd], [], [], 0.02)[0]:
                ch = os.read(fd, 1)

                if ch == b'\x03':  # Ctrl+C
                    break

                if ch == b'\x1b':
                    key = read_escape_seq(fd)
                    if key == 'ESC':
                        break
                    elif key == 'UP':
                        joy_up = True
                        send_joystick(ser, joy_up, joy_down, joy_left, joy_right, joy_fire)
                    elif key == 'DOWN':
                        joy_down = True
                        send_joystick(ser, joy_up, joy_down, joy_left, joy_right, joy_fire)
                    elif key == 'LEFT':
                        joy_left = True
                        send_joystick(ser, joy_up, joy_down, joy_left, joy_right, joy_fire)
                    elif key == 'RIGHT':
                        joy_right = True
                        send_joystick(ser, joy_up, joy_down, joy_left, joy_right, joy_fire)
                    elif key == 'F1' or key == 'F5':
                        send_console(ser, start=True)
                        time.sleep(0.15)
                        send_console(ser)
                    elif key == 'F2':
                        send_console(ser, select=True)
                        time.sleep(0.15)
                        send_console(ser)
                    elif key == 'F3':
                        send_console(ser, option=True)
                        time.sleep(0.15)
                        send_console(ser)
                    elif key == 'F4':
                        ser.write(b'R')
                    continue

                c = ch.decode('ascii', errors='replace')

                # Backslash = fire button
                if c == '\\':
                    joy_fire = True
                    send_joystick(ser, joy_up, joy_down, joy_left, joy_right, joy_fire)
                    continue

                # Normal key
                if c in KEY_MAP:
                    shift = c in SHIFT_CHARS
                    ctrl = False
                    scancode = KEY_MAP[c]
                    send_key(ser, scancode, True, shift=shift, ctrl=ctrl)
                    time.sleep(0.10)
                    send_key(ser, scancode, False)

            else:
                # No key pressed — release joystick directions
                if joy_up or joy_down or joy_left or joy_right or joy_fire:
                    joy_up = joy_down = joy_left = joy_right = joy_fire = False
                    send_joystick(ser, False, False, False, False, False)

            # Read and display any JOP output
            data = ser.read(256)
            if data:
                # Restore terminal briefly to print
                termios.tcsetattr(fd, termios.TCSADRAIN, old_settings)
                sys.stdout.write(data.decode('ascii', errors='replace'))
                sys.stdout.flush()
                tty.setraw(fd)

    except KeyboardInterrupt:
        pass
    finally:
        termios.tcsetattr(fd, termios.TCSADRAIN, old_settings)
        # Release all
        send_key(ser, 0, False)
        send_console(ser)
        send_joystick(ser, False, False, False, False, False)
        ser.close()
        print("\nDone.")


if __name__ == '__main__':
    main()
