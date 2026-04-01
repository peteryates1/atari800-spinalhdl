package atari;

import com.jopdesign.sys.Const;
import com.jopdesign.sys.JVMHelp;
import com.jopdesign.sys.Native;
import com.jopdesign.hw.VgaText;
import com.jopdesign.fat32.SdSpiBlockDevice;
import com.jopdesign.fat32.Fat32FileSystem;
import com.jopdesign.fat32.Fat32InputStream;
import com.jopdesign.fat32.DirEntry;

/**
 * Atari 800 supervisor running on JOP.
 *
 * Boot sequence:
 *   1. Hold Atari in reset (holdReset=1, default)
 *   2. Init SD card, mount FAT32
 *   3. Load cartridge ROM from SD card into SDRAM
 *   4. Set cart type, release Atari from reset
 *   5. Poll serial for keyboard/joystick input
 *
 * SDRAM memory map (24-bit physical addresses):
 *   JOP:   0x000000 - 0x7FFFFF
 *   Atari: 0x800000 - 0xFFFFFF
 *
 * Atari cartridge SDRAM addresses (8K standard cart, CART_MODE_8K):
 *   sdramCartAddr[22:0] = 1 ## emuCartAddr[20] ## ~emuCartAddr[20] ## emuCartAddr[19:0]
 *   For 8K cart (cfgBank=0): emuCartAddr = 0x00_0000..0x00_1FFF
 *   -> sdramCartAddr = 0x500000..0x501FFF (23-bit)
 *   -> physical SDRAM = 0xD00000..0xD01FFF (24-bit, with B"1" prefix)
 *   -> JOP word addr  = 0x340000..0x3407FF (byte addr / 4)
 *
 * I/O addresses (from IoAddressAllocator):
 *   AtariCtrl: IO_BASE + 0x40 .. IO_BASE + 0x4F  (0xC0-0xCF)
 *   SdSpi:     IO_BASE + 0x68 .. IO_BASE + 0x6B  (0xE8-0xEB)
 */
public class AtariSupervisor {

	// --- AtariCtrl register offsets from IO_BASE ---
	static final int ATARI_BASE     = Const.IO_BASE + 0x40;
	static final int ATARI_STATUS   = ATARI_BASE + 0;   // R: bit0=osd, bit1=locked, bit6=holdReset
	                                                     // W: bit0=osdEn, bit6=holdReset, bit7=coldReset
	static final int ATARI_CART_SEL = ATARI_BASE + 1;   // W: cartSelect[5:0]
	static final int ATARI_CONFIG   = ATARI_BASE + 2;   // W: [0]=pal, [3:1]=ramSel, [4]=turbo, [5]=a800, [6]=hires
	static final int ATARI_PADDLE01 = ATARI_BASE + 3;   // W: [7:0]=pad0, [15:8]=pad1
	static final int ATARI_PADDLE23 = ATARI_BASE + 4;   // W: [7:0]=pad2, [15:8]=pad3
	static final int ATARI_JOY12    = ATARI_BASE + 7;   // W: [4:0]=joy1_n, [12:8]=joy2_n
	static final int ATARI_JOY34    = ATARI_BASE + 8;   // W: [4:0]=joy3_n, [12:8]=joy4_n
	static final int ATARI_KB_THR   = ATARI_BASE + 9;   // W: [13:8]=throttle, [4]=start, [3]=select, [2]=option
	static final int ATARI_KEYBOARD = ATARI_BASE + 12;  // W: [5:0]=scanCode, [8]=pressed, [9]=shift, [10]=ctrl, [11]=break

	// --- Cartridge types (matches CartLogic CART_MODE constants) ---
	static final int CART_MODE_OFF = 0;
	static final int CART_MODE_8K  = 1;   // A000-BFFF (8K)
	static final int CART_MODE_16K = 33;  // 8000-BFFF (16K) = 0x21

	// --- SDRAM addresses for cartridge ROM ---
	// Physical SDRAM byte address: 0xD00000 (Atari prefix=1, sdramCartAddr=0x500000)
	// JOP word address: 0xD00000 / 4 = 0x340000
	// Same base for 8K and 16K (16K extends to 0xD03FFF)
	static final int CART_WORD_ADDR = 0x340000;

	// Track what cart mode was loaded
	static int loadedCartMode = CART_MODE_OFF;

	// Timing
	static final int WD_INTERVAL = 100000;  // microseconds between watchdog toggles

	// Atari keyboard state
	static boolean keyPressed = false;
	static int atariScanCode = 0;

	// Console key state (F-keys from serial)
	static boolean consolStart  = false;
	static boolean consolSelect = false;
	static boolean consolOption = false;

	// --- USB HID scancode to Atari 800 scancode translation ---
	// Index = USB HID usage ID, value = Atari scan code (0-63), -1 = unmapped
	static final int[] hidToAtari = {
		-1, -1, -1, -1,  // 0x00-0x03: reserved
		0x3F, // 0x04: A
		0x15, // 0x05: B
		0x12, // 0x06: C
		0x3A, // 0x07: D
		0x2A, // 0x08: E
		0x38, // 0x09: F
		0x3D, // 0x0A: G
		0x39, // 0x0B: H
		0x0D, // 0x0C: I
		0x01, // 0x0D: J
		0x05, // 0x0E: K
		0x00, // 0x0F: L
		0x25, // 0x10: M
		0x23, // 0x11: N
		0x08, // 0x12: O
		0x0A, // 0x13: P
		0x2F, // 0x14: Q
		0x28, // 0x15: R
		0x3E, // 0x16: S
		0x2D, // 0x17: T
		0x0B, // 0x18: U
		0x10, // 0x19: V
		0x2E, // 0x1A: W
		0x16, // 0x1B: X
		0x2B, // 0x1C: Y
		0x17, // 0x1D: Z
		0x1F, // 0x1E: 1
		0x1E, // 0x1F: 2
		0x1A, // 0x20: 3
		0x18, // 0x21: 4
		0x1D, // 0x22: 5
		0x1B, // 0x23: 6
		0x33, // 0x24: 7
		0x35, // 0x25: 8
		0x30, // 0x26: 9
		0x32, // 0x27: 0
		0x0C, // 0x28: Enter/Return
		0x1C, // 0x29: Escape
		0x34, // 0x2A: Backspace
		0x2C, // 0x2B: Tab
		0x21, // 0x2C: Space
		0x06, // 0x2D: - (minus)
		0x07, // 0x2E: = (equals)
		-1,   // 0x2F: [ (no direct Atari equivalent)
		-1,   // 0x30: ] (no direct Atari equivalent)
		-1,   // 0x31: backslash
		-1,   // 0x32: non-US #
		0x02, // 0x33: ; (semicolon)
		-1,   // 0x34: ' (apostrophe)
		-1,   // 0x35: ` (grave)
		0x20, // 0x36: , (comma)
		0x22, // 0x37: . (period)
		0x26, // 0x38: / (slash)
		0x3C, // 0x39: Caps Lock
	};

	public static void main(String[] args) {

		int w = 0, wd_next = 0;

		JVMHelp.wr("Atari Supervisor starting...\n");

		// --- Initialize Atari core (still held in reset) ---
		initAtari();

		// --- Initialize VGA text overlay ---
		VgaText vga = VgaText.getInstance();
		vga.clear(VgaText.attr(VgaText.WHITE, VgaText.BLACK));
		vga.setCursor(0, 0);
		vga.writeString("Atari Supervisor", VgaText.attr(VgaText.YELLOW, VgaText.BLACK));
		vga.enable();

		// --- SDRAM test: write/read Atari RAM region ---
		// Atari SDRAM base: physical 0x800000 -> JOP word addr 0x200000
		JVMHelp.wr("SDRAM test...\n");
		int testBase = 0x200000;  // Atari RAM base in JOP word space
		int errors = 0;
		for (int i = 0; i < 16; i++) {
			Native.wrMem(0xDEAD0000 | i, testBase + i);
		}
		for (int i = 0; i < 16; i++) {
			int rd = Native.rdMem(testBase + i);
			int exp = 0xDEAD0000 | i;
			if (rd != exp) {
				JVMHelp.wr("  [");
				wrDec(i);
				JVMHelp.wr("] W=");
				wrHex((exp >> 24) & 0xFF); wrHex((exp >> 16) & 0xFF);
				wrHex((exp >> 8) & 0xFF); wrHex(exp & 0xFF);
				JVMHelp.wr(" R=");
				wrHex((rd >> 24) & 0xFF); wrHex((rd >> 16) & 0xFF);
				wrHex((rd >> 8) & 0xFF); wrHex(rd & 0xFF);
				JVMHelp.wr("\n");
				errors++;
			}
		}
		if (errors == 0) {
			JVMHelp.wr("SDRAM OK (16 words)\n");
		} else {
			JVMHelp.wr("SDRAM ERRORS: ");
			wrDec(errors);
			JVMHelp.wr("/16\n");
		}

		// Also test cartridge region
		JVMHelp.wr("Cart region test...\n");
		errors = 0;
		for (int i = 0; i < 4; i++) {
			Native.wrMem(0xCAFE0000 | i, CART_WORD_ADDR + i);
		}
		for (int i = 0; i < 4; i++) {
			int rd = Native.rdMem(CART_WORD_ADDR + i);
			int exp = 0xCAFE0000 | i;
			if (rd != exp) {
				JVMHelp.wr("  Cart[");
				wrDec(i);
				JVMHelp.wr("] W=");
				wrHex((exp >> 24) & 0xFF); wrHex((exp >> 16) & 0xFF);
				wrHex((exp >> 8) & 0xFF); wrHex(exp & 0xFF);
				JVMHelp.wr(" R=");
				wrHex((rd >> 24) & 0xFF); wrHex((rd >> 16) & 0xFF);
				wrHex((rd >> 8) & 0xFF); wrHex(rd & 0xFF);
				JVMHelp.wr("\n");
				errors++;
			}
		}
		if (errors == 0) {
			JVMHelp.wr("Cart region OK\n");
		} else {
			JVMHelp.wr("Cart ERRORS: ");
			wrDec(errors);
			JVMHelp.wr("/4\n");
		}

		// Read ATARI_STATUS to check holdReset state
		int status = Native.rd(ATARI_STATUS);
		JVMHelp.wr("Status reg: ");
		wrHex(status & 0xFF);
		JVMHelp.wr("\n");

		// Release Atari from reset: clear holdReset (bit 6), keep OSD on (bit 0)
		Native.wr(CART_MODE_OFF, ATARI_CART_SEL);
		Native.wr(0x01, ATARI_STATUS);
		// Use single-char writes only — no string refs (avoid SDRAM method cache)
		JVMHelp.wr('R');
		JVMHelp.wr('E');
		JVMHelp.wr('L');
		JVMHelp.wr('\n');

		// Wait a bit for Atari to start hammering SDRAM
		delay(100000);  // 100ms

		// Test: can JOP still read SDRAM while Atari is active?
		JVMHelp.wr('T');  // "T" = starting test
		int testVal = Native.rdMem(0x200000);  // read from Atari SDRAM region
		wrHex((testVal >> 24) & 0xFF);
		wrHex((testVal >> 16) & 0xFF);
		wrHex((testVal >> 8) & 0xFF);
		wrHex(testVal & 0xFF);
		JVMHelp.wr('\n');

		// Test: read from JOP's own region (word addr 0)
		JVMHelp.wr('J');
		testVal = Native.rdMem(0);
		wrHex((testVal >> 24) & 0xFF);
		wrHex((testVal >> 16) & 0xFF);
		wrHex((testVal >> 8) & 0xFF);
		wrHex(testVal & 0xFF);
		JVMHelp.wr('\n');

		// Re-read status using I/O (no SDRAM)
		status = Native.rd(ATARI_STATUS);
		JVMHelp.wr('S');
		wrHex(status & 0xFF);
		JVMHelp.wr('\n');

		JVMHelp.wr('O');  // "OK" — survived
		JVMHelp.wr('K');
		JVMHelp.wr('\n');

		// --- Main loop ---
		// Check UART inline every iteration (pure I/O, no SDRAM access).
		// Only call pollSerial() when data actually arrives — this triggers
		// method cache fills (SDRAM burst) but only on key presses, not
		// continuously.  Keeps ANTIC DMA contention-free during normal display.
		while (true) {
			if ((Native.rd(Const.IO_UART_STATUS) & Const.MSK_UA_RDRF) != 0) {
				pollSerial();
			}
			int now = Native.rd(Const.IO_US_CNT);
			if (wd_next - now < 0) {
				wd_next = now + WD_INTERVAL;
				w = ~w;
				Native.wr(w, Const.IO_WD);
			}
		}
	}

	/** Initialize Atari core registers (Atari stays in reset via holdReset) */
	static void initAtari() {
		// PAL, 48K RAM (ramSelect=3), atari800mode, hires enabled
		// Config reg: [0]=pal=1, [3:1]=ramSel=3, [5]=a800=1, [6]=hires=1
		int config = 1 | (3 << 1) | (1 << 5) | (1 << 6);  // 0x67
		Native.wr(config, ATARI_CONFIG);

		// Joysticks: all released (active low = 0x1F each)
		Native.wr(0x1F1F, ATARI_JOY12);
		Native.wr(0x1F1F, ATARI_JOY34);

		// Paddles: center position
		Native.wr(0x7474, ATARI_PADDLE01);
		Native.wr(0x7474, ATARI_PADDLE23);

		// Console keys: none pressed, throttle=31
		Native.wr(0x1F00, ATARI_KB_THR);

		// No key pressed
		Native.wr(0, ATARI_KEYBOARD);

		JVMHelp.wr("Atari init: PAL 48K 800\n");
	}

	// ===================================================================
	// SD card ROM loading
	// ===================================================================

	/**
	 * Load cartridge ROM from SD card into SDRAM.
	 * Searches cartridge/ directory for .rom files (8K or 16K).
	 * Falls back to CART.ROM in root directory.
	 * Sets loadedCartMode on success.
	 * Returns true if loaded successfully.
	 */
	static boolean loadCartRom(Fat32FileSystem fs, VgaText vga) {
		DirEntry entry = null;

		// Try cartridge/ subdirectory first
		DirEntry cartDir = fs.findFile(fs.getRootCluster(), "cartridge");
		if (cartDir != null && cartDir.isDirectory()) {
			JVMHelp.wr("Found cartridge/\n");
			// Look for Star Raiders first, then fall back to first valid ROM
			entry = fs.findFile(cartDir.getStartCluster(), "Star Raiders.rom");
			if (entry == null) {
				entry = findFirstRom(fs, cartDir.getStartCluster());
			}
		}

		// Fall back to CART.ROM in root
		if (entry == null) {
			entry = fs.findFile(fs.getRootCluster(), "CART.ROM");
		}

		if (entry == null) {
			JVMHelp.wr("No ROM found\n");
			return false;
		}

		String romName = entry.getName();
		int fileSize = entry.fileSize;
		JVMHelp.wr(romName);
		JVMHelp.wr(": ");
		wrDec(fileSize);
		JVMHelp.wr(" bytes\n");

		// Determine cart mode from file size
		int cartMode;
		if (fileSize == 8192) {
			cartMode = CART_MODE_8K;
		} else if (fileSize == 16384) {
			cartMode = CART_MODE_16K;
		} else {
			JVMHelp.wr("Unsupported size\n");
			return false;
		}

		// Read file and write to SDRAM
		int wordAddr = CART_WORD_ADDR;
		int wordsWritten = 0;

		try {
			Fat32InputStream in = fs.openFile(entry);
			int bytesLeft = fileSize;

			while (bytesLeft >= 4) {
				int b0 = in.read();
				int b1 = in.read();
				int b2 = in.read();
				int b3 = in.read();
				if (b0 < 0 || b1 < 0 || b2 < 0 || b3 < 0) break;

				// SDRAM is little-endian: DATA_IN[7:0] -> lowest byte addr.
				// We want b0 at lowest addr, so:
				// DATA_IN[7:0]=b0, [15:8]=b1, [23:16]=b2, [31:24]=b3
				int word = (b3 << 24) | (b2 << 16) | (b1 << 8) | b0;
				Native.wrMem(word, wordAddr);
				wordAddr++;
				wordsWritten++;
				bytesLeft -= 4;

				// Watchdog every 256 words
				if ((wordsWritten & 0xFF) == 0) {
					Native.wr(~Native.rd(Const.IO_WD), Const.IO_WD);
				}
			}
			in.close();
		} catch (Exception e) {
			JVMHelp.wr("Read error\n");
			return false;
		}

		JVMHelp.wr("Loaded ");
		wrDec(wordsWritten * 4);
		JVMHelp.wr(" bytes to SDRAM\n");

		loadedCartMode = cartMode;
		return true;
	}

	/**
	 * Find first .rom file with valid cart size (8K or 16K) in directory.
	 */
	static DirEntry findFirstRom(Fat32FileSystem fs, int dirCluster) {
		DirEntry[] entries = fs.listDir(dirCluster);
		if (entries == null) return null;

		for (int i = 0; i < entries.length; i++) {
			DirEntry e = entries[i];
			if (e == null || e.isDirectory()) continue;
			String name = e.getName();
			if (name == null) continue;
			if (name.length() < 5) continue;
			// Check for .rom or .ROM extension
			String ext = name.substring(name.length() - 4);
			if (ext.equalsIgnoreCase(".rom")) {
				int sz = e.fileSize;
				if (sz == 8192 || sz == 16384) {
					return e;
				}
			}
		}
		return null;
	}

	// ===================================================================
	// Serial keyboard/joystick input
	// ===================================================================
	// Protocol (host -> JOP):
	//   'K' <lo> <hi>  — keyboard: lo=[5:0]=scancode,[6]=shift,[7]=ctrl
	//                              hi: bit0=pressed, bit1=break
	//   'C' <bits>     — console: bit0=option, bit1=select, bit2=start
	//   'J' <bits>     — joystick 1: [4:0]=up,down,left,right,fire (active-low)
	//   'R'            — cold reset

	/** Check if UART has data available */
	static boolean uartAvail() {
		return (Native.rd(Const.IO_UART_STATUS) & Const.MSK_UA_RDRF) != 0;
	}

	/** Read one byte from UART (non-blocking: caller must check uartAvail first) */
	static int uartRead() {
		return Native.rd(Const.IO_UART_DATA) & 0xFF;
	}

	/** Read one byte from UART with timeout (microseconds). Returns -1 on timeout. */
	static int uartReadTimeout(int us) {
		int deadline = Native.rd(Const.IO_US_CNT) + us;
		while (!uartAvail()) {
			if (Native.rd(Const.IO_US_CNT) - deadline > 0) return -1;
		}
		return uartRead();
	}

	/** Poll UART for serial keyboard/joystick commands */
	static void pollSerial() {
		if (!uartAvail()) return;
		int cmd = uartRead();

		switch (cmd) {
		case 'K': {
			// Keyboard event: 2 data bytes
			int lo = uartReadTimeout(5000);
			int hi = uartReadTimeout(5000);
			if (lo < 0 || hi < 0) { JVMHelp.wr("K?\n"); break; }
			int scanCode = lo & 0x3F;
			boolean shift = (lo & 0x40) != 0;
			boolean ctrl  = (lo & 0x80) != 0;
			boolean pressed = (hi & 0x01) != 0;
			boolean brk     = (hi & 0x02) != 0;
			int kbReg = (scanCode & 0x3F)
				| (pressed ? (1 << 8) : 0)
				| (shift   ? (1 << 9) : 0)
				| (ctrl    ? (1 << 10) : 0)
				| (brk     ? (1 << 11) : 0);
			Native.wr(kbReg, ATARI_KEYBOARD);
			break;
		}
		case 'C': {
			// Console keys: 1 data byte
			int bits = uartReadTimeout(5000);
			if (bits < 0) break;
			consolOption = (bits & 1) != 0;
			consolSelect = (bits & 2) != 0;
			consolStart  = (bits & 4) != 0;
			updateConsoleKeys();
			break;
		}
		case 'J': {
			// Joystick 1: 1 data byte (active-low, 5 bits)
			int bits = uartReadTimeout(5000);
			if (bits < 0) break;
			// joy1 in low byte, joy2 in high byte (keep joy2 released)
			Native.wr(0x1F00 | (bits & 0x1F), ATARI_JOY12);
			break;
		}
		case 'R':
			// Cold reset
			Native.wr(0x80, ATARI_STATUS);
			break;
		}
	}

	/** Update console key register (Start/Select/Option + throttle) */
	static void updateConsoleKeys() {
		int throttle = 31;  // max throttle
		int reg = (throttle << 8)
			| (consolStart  ? (1 << 4) : 0)
			| (consolSelect ? (1 << 3) : 0)
			| (consolOption ? (1 << 2) : 0);
		Native.wr(reg, ATARI_KB_THR);
	}

	// ===================================================================
	// Utility
	// ===================================================================

	/** Print a byte as 2-digit hex */
	static void wrHex(int val) {
		int hi = (val >> 4) & 0xF;
		int lo = val & 0xF;
		JVMHelp.wr(hi < 10 ? '0' + hi : 'A' + hi - 10);
		JVMHelp.wr(lo < 10 ? '0' + lo : 'A' + lo - 10);
	}

	/** Print a decimal number */
	static void wrDec(int val) {
		if (val < 0) { JVMHelp.wr('-'); val = -val; }
		if (val == 0) { JVMHelp.wr('0'); return; }
		// Max 10 digits for int
		int div = 1000000000;
		boolean started = false;
		while (div > 0) {
			int d = val / div;
			if (d > 0 || started) {
				JVMHelp.wr('0' + d);
				started = true;
			}
			val = val % div;
			div = div / 10;
		}
	}

	/** Delay in microseconds (busy-wait on IO_US_CNT) */
	static void delay(int us) {
		int start = Native.rd(Const.IO_US_CNT);
		while (Native.rd(Const.IO_US_CNT) - start < us) {
			// wait
		}
	}
}
