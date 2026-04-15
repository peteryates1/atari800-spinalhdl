package atari;

import com.jopdesign.sys.Const;
import com.jopdesign.sys.Native;
import com.jopdesign.sys.JVMHelp;
import com.jopdesign.fat32.Fat32FileSystem;
import com.jopdesign.fat32.Fat32RandomAccessFile;
import com.jopdesign.fat32.BlockDevice;
import com.jopdesign.fat32.DirEntry;

/**
 * SIO disk drive emulator (D1:).
 *
 * Interrupt-driven: SioBridge hardware interrupt sets cmdPending when a
 * complete SIO command frame arrives. Main loop calls processCommand()
 * which reads the frame from the RX FIFO and sends the response.
 *
 * SIO protocol reference: Atari 800 OS manual + MiST Atari800XL ZPU firmware.
 *
 * Command frame: 5 bytes (deviceId, command, aux1, aux2, checksum)
 * Response: ACK (0x41) or NAK (0x4E), then COMPLETE (0x43) or ERROR (0x45)
 *           + data frame for read commands.
 *
 * Timing (19200 baud, after COMMAND goes high):
 *   T2  = ~250us  -> ACK/NAK
 *   T5  = ~250us  ACK -> COMPLETE/ERROR
 *   T3  = ~150us  COMPLETE -> data
 */
public class SioDiskEmu {

	// SIO protocol constants
	static final int SIO_ACK      = 0x41;
	static final int SIO_NAK      = 0x4E;
	static final int SIO_COMPLETE = 0x43;
	static final int SIO_ERROR    = 0x45;

	// SIO commands
	static final int CMD_READ_SECTOR  = 0x52;  // 'R'
	static final int CMD_WRITE_SECTOR = 0x57;  // 'W' (not implemented)
	static final int CMD_PUT_SECTOR   = 0x50;  // 'P' (not implemented)
	static final int CMD_GET_STATUS   = 0x53;  // 'S'
	static final int CMD_GET_SPEED    = 0x3F;  // '?'
	static final int CMD_FORMAT       = 0x21;  // '!' (not implemented)

	// Device ID for D1:
	static final int DEVICE_D1 = 0x31;

	// SIO protocol timing (microseconds)
	static final int T2_DELAY = 250;   // COMMAND high -> ACK
	static final int T5_DELAY = 250;   // ACK -> COMPLETE/ERROR
	static final int T3_DELAY = 150;   // COMPLETE -> data frame

	// Set by interrupt handler, cleared by processCommand()
	public boolean cmdPending;
	// Debug counters
	public int cmdProcessed;
	public int intCount;

	// Disk state
	boolean mounted;
	int sectorSize;    // 128 or 256 bytes
	int sectorCount;   // total sectors on disk

	// ATR file access
	Fat32RandomAccessFile atrFile;

	// Sector data buffer (max 256 bytes = 64 words)
	int[] sectorBuf = new int[64];

	public SioDiskEmu() {
		cmdPending = false;
		mounted = false;
		sectorSize = 128;
		sectorCount = 0;
		atrFile = null;
	}

	/**
	 * Mount an ATR disk image from SD card.
	 *
	 * ATR format: 16-byte header + sector data.
	 * Header: magic=0x0296, paragraphs (LE16), sectorSize (LE16),
	 *         paragraphsHi (byte), unused...
	 * Paragraphs = (fileSize - 16) / 16
	 * Sectors 1-3 are always 128 bytes (boot sectors).
	 * Sectors 4+ use the header's sectorSize.
	 */
	public boolean mountAtr(Fat32FileSystem fs, BlockDevice dev, DirEntry entry) {
		atrFile = new Fat32RandomAccessFile(fs, dev, entry);

		// Read ATR header (16 bytes)
		int magic = atrFile.readByte(0) | (atrFile.readByte(1) << 8);
		if (magic != 0x0296) {
			JVMHelp.wr("Bad ATR magic\n");
			atrFile = null;
			return false;
		}

		int paraLo = atrFile.readByte(2) | (atrFile.readByte(3) << 8);
		sectorSize = atrFile.readByte(4) | (atrFile.readByte(5) << 8);
		int paraHi = atrFile.readByte(6) & 0xFF;

		int paragraphs = paraLo | (paraHi << 16);
		int dataSize = paragraphs * 16;  // total bytes of sector data

		// Compute sector count:
		// Boot sectors (1-3): 3 * 128 = 384 bytes
		// Data sectors (4+): (dataSize - 384) / sectorSize
		if (dataSize <= 384) {
			sectorCount = dataSize / 128;
		} else {
			sectorCount = 3 + (dataSize - 384) / sectorSize;
		}

		mounted = true;
		JVMHelp.wr("ATR: ");
		AtariSupervisor.wrDec(sectorCount);
		JVMHelp.wr(" sectors, ");
		AtariSupervisor.wrDec(sectorSize);
		JVMHelp.wr(" bytes/sector\n");
		return true;
	}

	/**
	 * Process a pending SIO command frame.
	 * Called from main loop when cmdPending is true.
	 */
	public void processCommand() {
		cmdPending = false;
		cmdProcessed++;

		// Drain FIFO silently — no UART output before response (ACK must arrive <3ms)
		int[] rawBuf = new int[16];
		int total = 0;
		while ((Native.rd(IoAddr.SIOBRIDGE_RX_STATUS) & 0x01) == 0 && total < 16) {
			rawBuf[total] = Native.rd(IoAddr.SIOBRIDGE_RX_DATA);
			total++;
		}

		// Find last complete frame using cmdByteIndex tags
		int[] frame = new int[5];
		int count = 0;
		boolean found = false;
		for (int i = 0; i < total; i++) {
			int data = rawBuf[i] & 0xFF;
			int idx  = (rawBuf[i] >> 8) & 0xFF;
			if (idx == 0) {
				frame[0] = data;
				count = 1;
				found = true;
			} else if (found && idx == count && count < 5) {
				frame[count] = data;
				count++;
			}
		}

		if (count < 5) {
			JVMHelp.wr("SIO: short frame (");
			AtariSupervisor.wrDec(count);
			JVMHelp.wr("/5)\n");
			return;
		}

		int deviceId = frame[0];
		int command  = frame[1];
		int aux1     = frame[2];
		int aux2     = frame[3];
		int checksum = frame[4];

		// Only respond to D1:
		if (deviceId != DEVICE_D1) return;

		// Validate checksum
		int calcSum = sioChecksum(frame, 4);
		if (calcSum != checksum) {
			delayUs(T2_DELAY);
			enableTx();
			sendByte(SIO_NAK);
			waitTxDone();
			disableTx();
			JVMHelp.wr("SIO NAK: bad cksum\n");
			return;
		}

		int sector = (aux2 << 8) | aux1;

		// Dispatch — response is time-critical, debug output follows
		switch (command) {
		case CMD_READ_SECTOR:
			cmdReadSector(sector);
			break;
		case CMD_GET_STATUS:
			cmdGetStatus();
			break;
		case CMD_GET_SPEED:
			cmdGetSpeed();
			break;
		default:
			delayUs(T2_DELAY);
			enableTx();
			sendByte(SIO_NAK);
			waitTxDone();
			disableTx();
			break;
		}

		// Debug output AFTER response is complete
		JVMHelp.wr("D1:");
		AtariSupervisor.wrHex(command);
		JVMHelp.wr(" s=");
		AtariSupervisor.wrDec(sector);
		JVMHelp.wr(" #");
		AtariSupervisor.wrDec(cmdProcessed);
		JVMHelp.wr("\n");
	}

	/**
	 * Handle Read Sector command (0x52).
	 */
	void cmdReadSector(int sector) {
		// ACK
		delayUs(T2_DELAY);
		enableTx();
		sendByte(SIO_ACK);

		if (!mounted || sector < 1 || sector > sectorCount) {
			// No disk or bad sector — ERROR
			delayUs(T5_DELAY);
			sendByte(SIO_ERROR);
			waitTxDone();
			disableTx();
			return;
		}

		// Read sector data (to be implemented in Phase 4)
		// For now, sectors 1-3 are always 128 bytes
		int size = (sector <= 3) ? 128 : sectorSize;
		boolean ok = readSectorData(sector, size);

		delayUs(T5_DELAY);
		if (!ok) {
			sendByte(SIO_ERROR);
			waitTxDone();
			disableTx();
			return;
		}

		// COMPLETE + data
		sendByte(SIO_COMPLETE);
		delayUs(T3_DELAY);

		// Send sector data + checksum
		int cksum = 0;
		for (int i = 0; i < size; i++) {
			int b = getBufByte(i);
			sendByte(b);
			cksum += b;
			if (cksum > 0xFF) cksum = (cksum & 0xFF) + 1;
		}
		sendByte(cksum & 0xFF);

		waitTxDone();
		disableTx();
	}

	/**
	 * Handle Get Status command (0x53).
	 * Returns 4-byte status frame.
	 */
	void cmdGetStatus() {
		delayUs(T2_DELAY);
		enableTx();
		sendByte(SIO_ACK);
		delayUs(T5_DELAY);
		sendByte(SIO_COMPLETE);
		delayUs(T3_DELAY);

		// Status bytes:
		// [0] Drive status: bit4=motor on, bit5=double density (256), bit3=write protect
		// [1] FDC status: 0xFF = OK
		// [2] Format timeout (E0 for 810)
		// [3] Unused
		int stat0 = 0x10;  // motor on
		if (sectorSize == 256) stat0 |= 0x20;  // double density
		if (!mounted) stat0 |= 0x08;  // write protected when no disk

		int cksum = 0;
		int[] status = { stat0, 0xFF, 0xE0, 0x00 };
		for (int i = 0; i < 4; i++) {
			sendByte(status[i]);
			cksum += status[i];
			if (cksum > 0xFF) cksum = (cksum & 0xFF) + 1;
		}
		sendByte(cksum & 0xFF);

		waitTxDone();
		disableTx();
	}

	/**
	 * Handle Get Speed Index command (0x3F).
	 * Returns 1-byte speed index (0x00 = standard speed).
	 */
	void cmdGetSpeed() {
		delayUs(T2_DELAY);
		enableTx();
		sendByte(SIO_ACK);
		delayUs(T5_DELAY);
		sendByte(SIO_COMPLETE);
		delayUs(T3_DELAY);

		int speed = 0x00;  // standard speed (19200 baud)
		sendByte(speed);
		sendByte(speed);  // checksum = same as data for single byte

		waitTxDone();
		disableTx();
	}

	/**
	 * Read sector data from ATR file into sectorBuf.
	 *
	 * ATR layout:
	 *   Offset 0-15: header (16 bytes)
	 *   Offset 16: sector 1 (128 bytes, always)
	 *   Offset 144: sector 2 (128 bytes, always)
	 *   Offset 272: sector 3 (128 bytes, always)
	 *   Offset 400: sector 4 (sectorSize bytes)
	 *   ...
	 */
	boolean readSectorData(int sector, int size) {
		if (atrFile == null) return false;

		// Compute file offset for this sector
		int fileOffset;
		if (sector <= 3) {
			fileOffset = 16 + (sector - 1) * 128;
		} else {
			fileOffset = 16 + 384 + (sector - 4) * sectorSize;
		}

		// Clear buffer first
		for (int i = 0; i < size / 4; i++) {
			sectorBuf[i] = 0;
		}

		int n = atrFile.readBytes(fileOffset, sectorBuf, 0, size);
		return n == size;
	}

	/** Get byte at offset from sectorBuf (packed 4 bytes per word, big-endian). */
	int getBufByte(int offset) {
		int word = sectorBuf[offset >> 2];
		int shift = (3 - (offset & 3)) * 8;
		return (word >> shift) & 0xFF;
	}

	/** Store byte at offset in sectorBuf. */
	void setBufByte(int offset, int value) {
		int idx = offset >> 2;
		int shift = (3 - (offset & 3)) * 8;
		int mask = ~(0xFF << shift);
		sectorBuf[idx] = (sectorBuf[idx] & mask) | ((value & 0xFF) << shift);
	}

	// ===== SIO TX helpers =====

	/** Enable TX output on SIO bus. */
	void enableTx() {
		Native.wr(1, IoAddr.SIOBRIDGE_STATUS_CTRL);  // CTRL: txEnableReg = true
	}

	/** Disable TX output (idles high). */
	void disableTx() {
		Native.wr(0, IoAddr.SIOBRIDGE_STATUS_CTRL);
	}

	/** Send one byte via SIO TX FIFO. Blocks until space available. */
	void sendByte(int b) {
		while ((Native.rd(IoAddr.SIOBRIDGE_TX_STATUS) & 0x02) != 0) {
			// TX FIFO full — wait
		}
		Native.wr(b & 0xFF, IoAddr.SIOBRIDGE_TX_DATA);
	}

	/** Wait for TX FIFO to drain and P2S serializer to finish last byte. */
	void waitTxDone() {
		// Wait for FIFO empty (TX_STATUS bit 0)
		while ((Native.rd(IoAddr.SIOBRIDGE_TX_STATUS) & 0x01) == 0) {}
		// Wait for P2S serializer idle (STATUS bit 2 = TX_BUSY)
		while ((Native.rd(IoAddr.SIOBRIDGE_STATUS_CTRL) & 0x04) != 0) {}
	}

	// ===== Utility =====

	/** SIO checksum: 8-bit sum with end-around carry. */
	static int sioChecksum(int[] data, int len) {
		int sum = 0;
		for (int i = 0; i < len; i++) {
			sum += data[i] & 0xFF;
			if (sum > 0xFF) sum = (sum & 0xFF) + 1;
		}
		return sum & 0xFF;
	}

	/** Microsecond busy-wait. */
	static void delayUs(int us) {
		int start = Native.rd(Const.IO_US_CNT);
		while (Native.rd(Const.IO_US_CNT) - start < us) {
			// wait
		}
	}
}
