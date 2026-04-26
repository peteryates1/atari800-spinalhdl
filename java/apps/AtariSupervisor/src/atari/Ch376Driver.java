package atari;

import com.jopdesign.hw.SdSpi;
import com.jopdesign.sys.Const;
import com.jopdesign.sys.JVMHelp;
import com.jopdesign.sys.Native;

/**
 * CH376T USB/SD controller driver over SPI.
 *
 * The CH376T is connected to the JOP SdSpi device in SPI mode (SPI# pin low).
 * SPI protocol: CS low, send command byte, send/receive data bytes, CS high.
 * No 0x57/0xAB prefix (that's UART mode only).
 *
 * SPI Mode 0 (CPOL=0, CPHA=0), MSB first — matches SdSpi hardware.
 */
public class Ch376Driver {

    // ---- CH376 command codes ----
    public static final int CMD_GET_IC_VER    = 0x01;
    public static final int CMD_RESET_ALL     = 0x05;
    public static final int CMD_CHECK_EXIST   = 0x06;
    public static final int CMD_SET_USB_MODE  = 0x15;
    public static final int CMD_GET_STATUS    = 0x22;
    public static final int CMD_RD_USB_DATA0  = 0x27;
    public static final int CMD_WR_HOST_DATA  = 0x2C;
    public static final int CMD_SET_FILE_NAME = 0x2F;
    public static final int CMD_DISK_CONNECT  = 0x30;
    public static final int CMD_DISK_MOUNT    = 0x31;
    public static final int CMD_FILE_OPEN     = 0x32;
    public static final int CMD_FILE_CLOSE    = 0x36;
    public static final int CMD_BYTE_LOCATE   = 0x39;
    public static final int CMD_BYTE_READ     = 0x3A;
    public static final int CMD_BYTE_RD_GO    = 0x3B;
    public static final int CMD_DISK_READ     = 0x54;
    public static final int CMD_DISK_RD_GO    = 0x55;

    // ---- Status / interrupt codes ----
    public static final int USB_INT_SUCCESS    = 0x14;
    public static final int USB_INT_CONNECT    = 0x15;
    public static final int USB_INT_DISCONNECT = 0x16;
    public static final int USB_INT_DISK_READ  = 0x1D;
    public static final int USB_INT_DISK_WRITE = 0x1E;
    public static final int USB_INT_DISK_ERR   = 0x1F;
    public static final int ERR_MISS_FILE      = 0x42;
    public static final int CMD_RET_SUCCESS    = 0x51;
    public static final int ERR_DISK_DISCON    = 0x82;

    // ---- USB modes ----
    public static final int USB_MODE_SD_HOST   = 0x03;
    public static final int USB_MODE_USB_HOST  = 0x06;

    private SdSpi spi;
    private boolean initialized;

    public Ch376Driver() {
        initialized = false;
    }

    // ================================================================
    // Low-level SPI helpers
    // ================================================================

    /** Send a command byte (CS assert + command + post-command delay). */
    private void cmdBegin(int cmd) {
        spi.csAssert();
        spi.send(cmd);
        delayUs(2);  // CH376T needs ≥1.5µs after command byte
    }

    /** End command (CS deassert). */
    private void cmdEnd() {
        spi.csDeassert();
    }

    /** Send a command with no data, return nothing. */
    private void cmdNoData(int cmd) {
        cmdBegin(cmd);
        cmdEnd();
    }

    /** Send a command, write one byte, return nothing. */
    private void cmdWrite1(int cmd, int data) {
        cmdBegin(cmd);
        spi.send(data);
        cmdEnd();
    }

    /** Send a command, read one response byte. */
    private int cmdRead1(int cmd) {
        cmdBegin(cmd);
        int r = spi.receive();
        cmdEnd();
        return r;
    }

    /**
     * Wait for INT# to go active (card detect pin reads as "present").
     * The CH376T INT# is active low. It's wired to the sdSpi cd input,
     * which is also active low, so cardPresent == INT# active.
     * Returns the status byte, or -1 on timeout.
     */
    private int waitInterrupt(int timeoutMs) {
        int deadline = Native.rd(Const.IO_US_CNT) + (timeoutMs * 1000);
        while (!spi.isCardPresent()) {
            int now = Native.rd(Const.IO_US_CNT);
            if (now - deadline > 0) return -1;
            if ((now & 0xFFFF) == 0) toggleWd();
        }
        return getStatus();
    }

    /** GET_STATUS: read the interrupt status byte and clear INT#. */
    public int getStatus() {
        return cmdRead1(CMD_GET_STATUS);
    }

    private void toggleWd() {
        Native.wr(~Native.rd(Const.IO_WD), Const.IO_WD);
    }

    /** Microsecond busy-wait using hardware counter. */
    private void delayUs(int us) {
        int start = Native.rd(Const.IO_US_CNT);
        while (Native.rd(Const.IO_US_CNT) - start < us) { }
    }

    // ================================================================
    // Initialization
    // ================================================================

    /**
     * Initialize the CH376T. Returns true if chip responds correctly.
     */
    public boolean init() {
        spi = SdSpi.getInstance();

        // SPI clock: divider=27 -> ~1 MHz at 56.67 MHz sys clock
        // (conservative for initial bring-up)
        spi.setClockDivider(27);
        spi.csDeassert();
        delayUs(10000);  // 10ms settle

        // Software reset
        cmdNoData(CMD_RESET_ALL);
        delayUs(100000);  // wait 100ms for CH376T internal init (spec: >35ms)
        toggleWd();

        // Verify chip presence with CHECK_EXIST
        int testResult = checkExistDebug();
        if (testResult < 0) {
            JVMHelp.wr("CH376: no response\n");
            return false;
        }

        // Read chip version
        int ver = getIcVer();
        JVMHelp.wr("CH376: v");
        wrDec(ver);
        JVMHelp.wr("\n");

        initialized = true;
        return true;
    }

    /**
     * CHECK_EXIST: send a test byte, expect bitwise complement back.
     * This verifies the SPI bus and CH376T are working.
     */
    public boolean checkExist() {
        int testVal = 0xA5;
        cmdBegin(CMD_CHECK_EXIST);
        spi.send(testVal);
        int r = spi.receive();
        cmdEnd();
        int expected = (~testVal) & 0xFF;
        return r == expected;
    }

    /**
     * CHECK_EXIST with debug output. Returns received byte, or -1 on mismatch.
     * Tests at slow clock speed with multiple test values.
     */
    public int checkExistDebug() {
        // Try very slow clock: divider=255 -> ~110 kHz
        spi.setClockDivider(255);
        delayUs(100);

        // Test with 0xA5 (expect 0x5A)
        int t1 = doCheckExist(0xA5);
        // Test with 0x55 (expect 0xAA)
        int t2 = doCheckExist(0x55);
        // Test with 0x01 (expect 0xFE)
        int t3 = doCheckExist(0x01);

        JVMHelp.wr("CE slow: ");
        wrHex(0xA5); JVMHelp.wr("->"); wrHex(t1);
        JVMHelp.wr(" ");
        wrHex(0x55); JVMHelp.wr("->"); wrHex(t2);
        JVMHelp.wr(" ");
        wrHex(0x01); JVMHelp.wr("->"); wrHex(t3);
        JVMHelp.wr("\n");

        // Restore normal speed
        spi.setClockDivider(27);

        // Same tests at normal speed
        int n1 = doCheckExist(0xA5);
        int n2 = doCheckExist(0x55);
        int n3 = doCheckExist(0x01);

        JVMHelp.wr("CE fast: ");
        wrHex(0xA5); JVMHelp.wr("->"); wrHex(n1);
        JVMHelp.wr(" ");
        wrHex(0x55); JVMHelp.wr("->"); wrHex(n2);
        JVMHelp.wr(" ");
        wrHex(0x01); JVMHelp.wr("->"); wrHex(n3);
        JVMHelp.wr("\n");

        int exp1 = (~0xA5) & 0xFF;
        if (n1 == exp1 || t1 == exp1) return exp1;
        return -1;
    }

    /** Single CHECK_EXIST attempt. Returns received byte. */
    private int doCheckExist(int testVal) {
        spi.csAssert();
        delayUs(5);
        spi.send(CMD_CHECK_EXIST);
        delayUs(5);
        spi.send(testVal);
        delayUs(2);
        int r = spi.receive();
        spi.csDeassert();
        delayUs(5);
        return r;
    }

    /** GET_IC_VER: read chip version (lower 6 bits). */
    public int getIcVer() {
        return cmdRead1(CMD_GET_IC_VER) & 0x3F;
    }

    // ================================================================
    // SD card operations
    // ================================================================

    /**
     * Initialize SD card: set USB mode to SD host, connect, mount.
     * Returns true on success.
     */
    public boolean initSdCard() {
        if (!initialized) return false;

        // Set mode to SD host
        cmdBegin(CMD_SET_USB_MODE);
        spi.send(USB_MODE_SD_HOST);
        int r = spi.receive();
        cmdEnd();
        if (r != CMD_RET_SUCCESS) {
            JVMHelp.wr("CH376: mode fail ");
            wrHex(r);
            JVMHelp.wr("\n");
            return false;
        }
        delayUs(50000);  // 50ms settle after mode change
        toggleWd();

        // DISK_CONNECT: check if SD card is present
        cmdNoData(CMD_DISK_CONNECT);
        int status = waitInterrupt(2000);
        if (status != USB_INT_SUCCESS) {
            JVMHelp.wr("CH376: no SD ");
            wrHex(status);
            JVMHelp.wr("\n");
            return false;
        }
        JVMHelp.wr("CH376: SD connected\n");
        toggleWd();

        // DISK_MOUNT: mount filesystem
        cmdNoData(CMD_DISK_MOUNT);
        status = waitInterrupt(5000);
        if (status != USB_INT_SUCCESS) {
            JVMHelp.wr("CH376: mount fail ");
            wrHex(status);
            JVMHelp.wr("\n");
            return false;
        }
        JVMHelp.wr("CH376: SD mounted\n");
        return true;
    }

    /**
     * Read a raw 512-byte sector by LBA into buf[128] (big-endian words).
     * Returns true on success.
     */
    public boolean readSector(int lba, int[] buf) {
        if (!initialized) return false;

        // DISK_READ: LBA (4 bytes LE) + sector count (1)
        cmdBegin(CMD_DISK_READ);
        spi.send(lba & 0xFF);
        spi.send((lba >> 8) & 0xFF);
        spi.send((lba >> 16) & 0xFF);
        spi.send((lba >> 24) & 0xFF);
        spi.send(1);  // 1 sector
        cmdEnd();

        // CH376T reads in 64-byte chunks (8 chunks per 512-byte sector)
        int bufIdx = 0;
        for (int chunk = 0; chunk < 8; chunk++) {
            int status = waitInterrupt(2000);
            if (status != USB_INT_DISK_READ) {
                return status == USB_INT_SUCCESS && chunk > 0;
            }

            // RD_USB_DATA0: length byte + data
            cmdBegin(CMD_RD_USB_DATA0);
            int len = spi.receive();
            for (int i = 0; i < len; i += 4) {
                int w = spi.receive() << 24;
                w |= spi.receive() << 16;
                w |= spi.receive() << 8;
                w |= spi.receive();
                if (bufIdx < 128) buf[bufIdx++] = w;
            }
            cmdEnd();

            // DISK_RD_GO: tell CH376T to continue (required after every chunk,
            // including the last — triggers USB_INT_SUCCESS after final chunk)
            cmdNoData(CMD_DISK_RD_GO);
            if ((chunk & 3) == 0) toggleWd();
        }

        // Final status should be USB_INT_SUCCESS
        int status = waitInterrupt(2000);
        return status == USB_INT_SUCCESS;
    }

    // ================================================================
    // File operations (CH376T built-in FAT support)
    // ================================================================

    /**
     * Open a file by name. The name must be 8.3 format, uppercase,
     * padded with spaces (e.g., "GAME    ATR" for "GAME.ATR").
     * Prefix with '/' for root directory.
     * Returns status: USB_INT_SUCCESS or ERR_MISS_FILE.
     */
    public int fileOpen(String name) {
        // SET_FILE_NAME: send name bytes terminated with 0x00
        cmdBegin(CMD_SET_FILE_NAME);
        for (int i = 0; i < name.length(); i++) {
            spi.send(name.charAt(i));
        }
        spi.send(0x00);
        cmdEnd();

        // FILE_OPEN
        cmdNoData(CMD_FILE_OPEN);
        return waitInterrupt(2000);
    }

    /**
     * Read bytes from currently open file into buf.
     * Returns number of bytes actually read, or -1 on error.
     */
    public int fileRead(int[] buf, int offset, int len) {
        // BYTE_READ: request len bytes
        cmdBegin(CMD_BYTE_READ);
        spi.send(len & 0xFF);
        spi.send((len >> 8) & 0xFF);
        cmdEnd();

        int totalRead = 0;
        while (totalRead < len) {
            int status = waitInterrupt(2000);
            if (status == USB_INT_SUCCESS) {
                break;  // end of file or done
            }
            if (status != USB_INT_DISK_READ) {
                return -1;  // error
            }

            // RD_USB_DATA0: get available data
            cmdBegin(CMD_RD_USB_DATA0);
            int chunkLen = spi.receive();
            for (int i = 0; i < chunkLen; i++) {
                int b = spi.receive();
                if (offset + totalRead < buf.length) {
                    // Pack bytes into int array (one byte per entry for simplicity)
                    buf[offset + totalRead] = b;
                }
                totalRead++;
            }
            cmdEnd();

            // Continue reading
            if (totalRead < len) {
                cmdNoData(CMD_BYTE_RD_GO);
            }
            toggleWd();
        }
        return totalRead;
    }

    /**
     * Seek to a byte offset in the currently open file.
     * Returns USB_INT_SUCCESS on success.
     */
    public int fileSeek(int offset) {
        cmdBegin(CMD_BYTE_LOCATE);
        spi.send(offset & 0xFF);
        spi.send((offset >> 8) & 0xFF);
        spi.send((offset >> 16) & 0xFF);
        spi.send((offset >> 24) & 0xFF);
        cmdEnd();
        return waitInterrupt(2000);
    }

    /** Close the currently open file. */
    public void fileClose() {
        cmdBegin(CMD_FILE_CLOSE);
        spi.send(0x00);  // 0 = don't update file length
        cmdEnd();
        waitInterrupt(1000);
    }

    // ================================================================
    // Debug helpers
    // ================================================================

    private static void wrHex(int val) {
        wrHexByte((val >> 4) & 0xF);
        wrHexByte(val & 0xF);
    }

    private static void wrHexByte(int nibble) {
        JVMHelp.wr(nibble < 10 ? '0' + nibble : 'A' + nibble - 10);
    }

    private static void wrDec(int val) {
        if (val >= 10) wrDec(val / 10);
        JVMHelp.wr('0' + (val % 10));
    }
}
