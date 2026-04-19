#!/usr/bin/env python3
"""
CH376T SD card tester — talks to ch376_test_top FPGA design.
Uses CH376's built-in FAT filesystem commands to enumerate and read files.

Usage: python3 ch376_sdcard_test.py [/dev/ttyUSB0] [500000]
"""
import serial, sys, time, struct

PORT = sys.argv[1] if len(sys.argv) > 1 else '/dev/ttyUSB0'
BAUD = int(sys.argv[2]) if len(sys.argv) > 2 else 500000

ser = None

# ---- CH376 command codes ----
CMD_GET_IC_VER    = 0x01
CMD_RESET_ALL     = 0x05
CMD_CHECK_EXIST   = 0x06
CMD_SET_USB_MODE  = 0x15
CMD_GET_STATUS    = 0x22
CMD_RD_USB_DATA0  = 0x27
CMD_SET_FILE_NAME = 0x2F
CMD_DISK_CONNECT  = 0x30
CMD_DISK_MOUNT    = 0x31
CMD_FILE_OPEN     = 0x32
CMD_FILE_ENUM_GO  = 0x33
CMD_FILE_CLOSE    = 0x36
CMD_BYTE_READ     = 0x3A
CMD_BYTE_RD_GO    = 0x3B
CMD_DISK_CAPACITY = 0x3E
CMD_DISK_QUERY    = 0x3F

# ---- Interrupt status codes ----
USB_INT_SUCCESS   = 0x14
USB_INT_DISK_READ = 0x1D
ERR_OPEN_DIR      = 0x41
ERR_MISS_FILE     = 0x42
ERR_FOUND_NAME    = 0x43
ERR_DISK_DISCON   = 0x82

# ---- FAT directory entry attributes ----
ATTR_READ_ONLY = 0x01
ATTR_HIDDEN    = 0x02
ATTR_SYSTEM    = 0x04
ATTR_VOLUME_ID = 0x08
ATTR_DIRECTORY = 0x10
ATTR_ARCHIVE   = 0x20
ATTR_LONG_NAME = 0x0F  # combination used for LFN entries

# ---- Low-level SPI/UART bridge ----

def open_port():
    global ser
    ser = serial.Serial(PORT, BAUD, timeout=1)
    ser.reset_input_buffer()
    time.sleep(0.1)
    ser.reset_input_buffer()

def spi_cs(on):
    ser.write(bytes([ord('C'), 1 if on else 0]))
    ser.flush()

def spi_byte(tx):
    ser.write(bytes([ord('T'), tx & 0xFF]))
    ser.flush()
    r = ser.read(1)
    if len(r) != 1:
        return 0xFF
    return r[0]

def spi_cmd(cmd_byte, data_bytes=[], read_count=0):
    """Send CH376 command + data, read response bytes."""
    spi_cs(True)
    time.sleep(0.001)
    spi_byte(cmd_byte)
    time.sleep(0.002)
    for b in data_bytes:
        spi_byte(b)
    rx = [spi_byte(0xFF) for _ in range(read_count)]
    spi_cs(False)
    return rx

def set_div(div):
    ser.write(bytes([ord('D'), div & 0xFF, (div >> 8) & 0xFF]))
    ser.flush()
    r = ser.read(1)
    freq_khz = 50000 / (2 * (div + 1))
    print(f"  SPI div={div}  clock={freq_khz:.0f} kHz")

def read_int_pin():
    ser.write(b'I')
    ser.flush()
    r = ser.read(1)
    return r[0] if r else -1

def wait_int_hw(timeout_count=1):
    for _ in range(timeout_count):
        ser.write(b'W')
        ser.flush()
        r = ser.read(1)
        if r == b'W':
            return True
    return False

def fmt(data):
    return ' '.join(f'{b:02X}' for b in data)

# ---- CH376 helpers ----

def check_exist(test_val):
    expected = (~test_val) & 0xFF
    rx = spi_cmd(CMD_CHECK_EXIST, [test_val], 1)
    got = rx[0] if rx else -1
    ok = "OK" if got == expected else "FAIL"
    print(f"  CHECK_EXIST(0x{test_val:02X}): got 0x{got:02X} exp 0x{expected:02X}  {ok}")
    return got == expected

def get_status():
    rx = spi_cmd(CMD_GET_STATUS, [], 1)
    return rx[0] if rx else 0xFF

def wait_interrupt(timeout_counts=24):
    if wait_int_hw(timeout_counts):
        return get_status()
    return None

def drain_interrupts():
    drained = []
    for _ in range(10):
        if read_int_pin() != 0:
            break
        st = get_status()
        drained.append(st)
        time.sleep(0.01)
    return drained

def set_usb_mode(mode):
    rx = spi_cmd(CMD_SET_USB_MODE, [mode], 1)
    result = rx[0] if rx else 0xFF
    mode_names = {0x03: 'SD card host', 0x05: 'USB host/no-SOF', 0x06: 'USB host/SOF'}
    name = mode_names.get(mode, f'0x{mode:02X}')
    print(f"  SET_USB_MODE({name}): result=0x{result:02X} {'OK' if result == 0x51 else 'FAIL'}")
    return result == 0x51

def read_data():
    """RD_USB_DATA0 (0x27) — read data from CH376 buffer.
    Returns (length, data_bytes)."""
    spi_cs(True)
    time.sleep(0.001)
    spi_byte(CMD_RD_USB_DATA0)
    time.sleep(0.002)
    length = spi_byte(0xFF)
    data = [spi_byte(0xFF) for _ in range(length)]
    spi_cs(False)
    return length, data

def set_file_name(name_str):
    """SET_FILE_NAME — send filename string with null terminator."""
    name_bytes = name_str.encode('ascii') + b'\x00'
    spi_cs(True)
    time.sleep(0.001)
    spi_byte(CMD_SET_FILE_NAME)
    time.sleep(0.002)
    for b in name_bytes:
        spi_byte(b)
    spi_cs(False)

def status_name(st):
    names = {
        0x14: 'SUCCESS', 0x15: 'CONNECT', 0x16: 'DISCONNECT',
        0x1D: 'DISK_READ', 0x1E: 'DISK_WRITE', 0x1F: 'DISK_ERR',
        0x41: 'OPEN_DIR', 0x42: 'MISS_FILE', 0x43: 'FOUND_NAME',
        0x82: 'DISK_DISCON',
    }
    return names.get(st, f'0x{st:02X}')

def parse_fat_dir_entry(data):
    """Parse a 32-byte FAT directory entry. Returns dict or None for invalid."""
    if len(data) < 32:
        return None
    if data[0] == 0x00 or data[0] == 0xE5:
        return None  # empty or deleted
    attr = data[11]
    if attr == ATTR_LONG_NAME:
        return None  # LFN entry, skip

    name = bytes(data[0:8]).decode('ascii', errors='replace').rstrip()
    ext = bytes(data[8:11]).decode('ascii', errors='replace').rstrip()
    size = struct.unpack('<I', bytes(data[28:32]))[0]
    cluster = struct.unpack('<H', bytes(data[26:28]))[0]
    # date/time
    raw_time = struct.unpack('<H', bytes(data[22:24]))[0]
    raw_date = struct.unpack('<H', bytes(data[24:26]))[0]
    hour = (raw_time >> 11) & 0x1F
    minute = (raw_time >> 5) & 0x3F
    sec = (raw_time & 0x1F) * 2
    year = ((raw_date >> 9) & 0x7F) + 1980
    month = (raw_date >> 5) & 0x0F
    day = raw_date & 0x1F

    flags = []
    if attr & ATTR_DIRECTORY: flags.append('DIR')
    if attr & ATTR_READ_ONLY: flags.append('RO')
    if attr & ATTR_HIDDEN: flags.append('HID')
    if attr & ATTR_SYSTEM: flags.append('SYS')
    if attr & ATTR_VOLUME_ID: flags.append('VOL')

    filename = f"{name}.{ext}" if ext else name
    return {
        'name': name, 'ext': ext, 'filename': filename,
        'attr': attr, 'flags': flags,
        'size': size, 'cluster': cluster,
        'date': f'{year:04d}-{month:02d}-{day:02d}',
        'time': f'{hour:02d}:{minute:02d}:{sec:02d}',
    }

# =========================================================
def main():
    print(f"CH376T SD card test — {PORT} @ {BAUD}")
    open_port()
    time.sleep(0.2)

    # --- Verify CH376T ---
    print("\n--- Verify CH376T ---")
    set_div(499)  # 50 kHz
    if not check_exist(0xA5):
        print("CH376T not responding!")
        return

    # Hardware reset for clean state
    print("\n--- Hardware reset CH376T ---")
    ser.write(b'R')
    ser.flush()
    r = ser.read(1)
    print(f"  Reset ack: {chr(r[0]) if r else 'timeout'}")
    time.sleep(0.2)  # wait for CH376T to fully init
    check_exist(0xA5)

    # Speed up host SPI
    print("\n--- Speed up SPI ---")
    set_div(49)  # 500 kHz
    check_exist(0xA5)

    # Drain any boot interrupts
    drained = drain_interrupts()
    if drained:
        print(f"  Boot events: {' '.join(status_name(s) for s in drained)}")

    # --- Enter SD card host mode ---
    print("\n--- Set SD card host mode (0x03) ---")
    if not set_usb_mode(0x03):
        print("Failed to enter SD card mode!")
        return
    time.sleep(0.2)  # give CH376T time to switch mode

    # Drain any mode-switch interrupts
    drained = drain_interrupts()
    if drained:
        print(f"  Mode-switch events: {' '.join(status_name(s) for s in drained)}")

    # --- Check SD card (informational, doesn't work for SD per datasheet) ---
    print("\n--- DISK_CONNECT ---")
    spi_cmd(CMD_DISK_CONNECT)
    st = wait_interrupt(24)
    print(f"  DISK_CONNECT: {status_name(st)}" if st else "  DISK_CONNECT: timeout")
    drain_interrupts()

    # --- Mount filesystem (with retries and longer waits) ---
    print("\n--- DISK_MOUNT ---")
    mounted = False
    for attempt in range(5):
        if attempt > 0:
            print(f"  Retry {attempt}...")
            time.sleep(0.5)
            drain_interrupts()
        spi_cmd(CMD_DISK_MOUNT)
        st = wait_interrupt(60)  # long timeout — SD init can be slow
        print(f"  DISK_MOUNT: {status_name(st)}" if st else "  DISK_MOUNT: timeout")
        if st == USB_INT_SUCCESS:
            mounted = True
            break
        if st and st != ERR_DISK_DISCON:
            print(f"  Unexpected status, stopping retries")
            break

    if not mounted:
        # Debug: try CHECK_EXIST to confirm CH376T is still responsive
        print("\n  Debug: CH376T still alive?")
        check_exist(0xA5)
        # Try reading INT# pin
        print(f"  INT# pin: {read_int_pin()}")
        print("  Failed to mount filesystem!")
        return

    # Read mount info
    n, data = read_data()
    if n > 0:
        print(f"  Mount data ({n} bytes): {fmt(data[:min(n,32)])}")

    # --- Disk capacity ---
    print("\n--- DISK_CAPACITY ---")
    spi_cmd(CMD_DISK_CAPACITY)
    st = wait_interrupt()
    if st == USB_INT_SUCCESS:
        n, data = read_data()
        if n >= 4:
            total_sectors = struct.unpack('<I', bytes(data[0:4]))[0]
            capacity_mb = total_sectors * 512 / (1024 * 1024)
            print(f"  Total sectors: {total_sectors}  ({capacity_mb:.0f} MB)")
    else:
        print(f"  DISK_CAPACITY: {status_name(st)}" if st else "  timeout")

    # --- Disk query (free space) ---
    print("\n--- DISK_QUERY ---")
    spi_cmd(CMD_DISK_QUERY)
    st = wait_interrupt()
    if st == USB_INT_SUCCESS:
        n, data = read_data()
        if n >= 12:
            total = struct.unpack('<I', bytes(data[0:4]))[0]
            free = struct.unpack('<I', bytes(data[4:8]))[0]
            fat_type = data[8] if n > 8 else 0
            fat_names = {0x01: 'FAT12', 0x02: 'FAT16', 0x03: 'FAT32'}
            print(f"  Total sectors: {total}  Free sectors: {free}")
            print(f"  Filesystem: {fat_names.get(fat_type, f'0x{fat_type:02X}')}")
    else:
        print(f"  DISK_QUERY: {status_name(st)}" if st else "  timeout")

    # --- Enumerate root directory ---
    print("\n--- Root directory listing ---")
    set_file_name("/*")
    spi_cmd(CMD_FILE_OPEN)
    st = wait_interrupt()

    files_found = []
    entry_count = 0
    while st == USB_INT_DISK_READ:
        n, data = read_data()
        if n >= 32:
            entry = parse_fat_dir_entry(data)
            if entry:
                entry_count += 1
                flags_str = ' '.join(entry['flags']) if entry['flags'] else ''
                if entry['attr'] & ATTR_DIRECTORY:
                    print(f"  {entry['filename']:<14s}  <DIR>         {entry['date']} {entry['time']}  {flags_str}")
                else:
                    print(f"  {entry['filename']:<14s}  {entry['size']:>10d}  {entry['date']} {entry['time']}  {flags_str}")
                files_found.append(entry)
        spi_cmd(CMD_FILE_ENUM_GO)
        st = wait_interrupt()

    if st == ERR_MISS_FILE:
        print(f"  ({entry_count} entries)")
    elif st == ERR_OPEN_DIR:
        print(f"  (directory opened, {entry_count} entries)")
    else:
        print(f"  Enum ended: {status_name(st)}" if st else "  timeout")

    # --- Browse into subdirectories ---
    subdirs_to_browse = ['DISKS', 'CARTRI~1', 'OS']
    for subdir in subdirs_to_browse:
        if not any(f['name'] == subdir and (f['attr'] & ATTR_DIRECTORY) for f in files_found):
            continue

        # Close current dir, open subdirectory
        print(f"\n--- /{subdir}/ ---")
        set_file_name(f"/{subdir}")
        spi_cmd(CMD_FILE_OPEN)
        st = wait_interrupt()
        if st != ERR_OPEN_DIR:
            print(f"  Open dir: {status_name(st)}")
            continue

        # Enumerate with wildcard
        set_file_name("*")
        spi_cmd(CMD_FILE_OPEN)
        st = wait_interrupt()

        sub_files = []
        sub_count = 0
        while st == USB_INT_DISK_READ:
            n, data = read_data()
            if n >= 32:
                entry = parse_fat_dir_entry(data)
                if entry:
                    sub_count += 1
                    if entry['attr'] & ATTR_DIRECTORY:
                        print(f"  {entry['filename']:<14s}  <DIR>         {entry['date']} {entry['time']}")
                    else:
                        print(f"  {entry['filename']:<14s}  {entry['size']:>10d}  {entry['date']} {entry['time']}")
                    sub_files.append(entry)
            spi_cmd(CMD_FILE_ENUM_GO)
            st = wait_interrupt()

        print(f"  ({sub_count} entries)")

        # Try to read a small file from this directory
        read_target = None
        for f in sub_files:
            if not (f['attr'] & ATTR_DIRECTORY) and f['size'] > 0 and f['size'] <= 4096:
                read_target = f
                break

        if read_target:
            if read_target['ext']:
                fname = f"{read_target['name']}.{read_target['ext']}"
            else:
                fname = read_target['name']
            print(f"\n  Reading: {fname} ({read_target['size']} bytes)")

            set_file_name(fname)
            spi_cmd(CMD_FILE_OPEN)
            st = wait_interrupt()
            if st == USB_INT_SUCCESS:
                size = read_target['size']
                req = min(size, 4096)
                spi_cmd(CMD_BYTE_READ, [req & 0xFF, (req >> 8) & 0xFF])
                st = wait_interrupt()

                file_data = bytearray()
                while st == USB_INT_DISK_READ:
                    n, data = read_data()
                    file_data.extend(data)
                    spi_cmd(CMD_BYTE_RD_GO)
                    st = wait_interrupt()

                if st == USB_INT_SUCCESS:
                    print(f"  Read {len(file_data)} bytes OK")
                else:
                    print(f"  Read ended: {status_name(st)}, got {len(file_data)} bytes")

                if file_data:
                    try:
                        text = file_data.decode('ascii')
                        if all(c.isprintable() or c in '\r\n\t' for c in text):
                            print(f"  Content:")
                            for line in text.splitlines()[:20]:
                                print(f"    {line}")
                        else:
                            raise ValueError()
                    except (UnicodeDecodeError, ValueError):
                        print(f"  Hex dump (first 128 bytes):")
                        for i in range(0, min(len(file_data), 128), 16):
                            chunk = file_data[i:i+16]
                            hexs = ' '.join(f'{b:02X}' for b in chunk)
                            ascs = ''.join(chr(b) if 32 <= b < 127 else '.' for b in chunk)
                            print(f"    {i:04X}: {hexs:<48s} {ascs}")

                spi_cmd(CMD_FILE_CLOSE, [0x00])
                wait_interrupt()
            else:
                print(f"  FILE_OPEN: {status_name(st)}")

        # Also show first large file header (e.g., ATR/ROM)
        big_target = None
        for f in sub_files:
            if not (f['attr'] & ATTR_DIRECTORY) and f['size'] > 0:
                big_target = f
                break

        if big_target and big_target != read_target:
            if big_target['ext']:
                fname = f"{big_target['name']}.{big_target['ext']}"
            else:
                fname = big_target['name']
            print(f"\n  Peeking: {fname} ({big_target['size']} bytes) — first 64 bytes")

            set_file_name(fname)
            spi_cmd(CMD_FILE_OPEN)
            st = wait_interrupt()
            if st == USB_INT_SUCCESS:
                spi_cmd(CMD_BYTE_READ, [64, 0])
                st = wait_interrupt()

                file_data = bytearray()
                while st == USB_INT_DISK_READ:
                    n, data = read_data()
                    file_data.extend(data)
                    spi_cmd(CMD_BYTE_RD_GO)
                    st = wait_interrupt()

                for i in range(0, min(len(file_data), 64), 16):
                    chunk = file_data[i:i+16]
                    hexs = ' '.join(f'{b:02X}' for b in chunk)
                    ascs = ''.join(chr(b) if 32 <= b < 127 else '.' for b in chunk)
                    print(f"    {i:04X}: {hexs:<48s} {ascs}")

                spi_cmd(CMD_FILE_CLOSE, [0x00])
                wait_interrupt()
            else:
                print(f"  FILE_OPEN: {status_name(st)}")

    print("\nDone.")
    ser.close()

if __name__ == '__main__':
    main()
