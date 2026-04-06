package atari;

import com.jopdesign.sys.Const;
import com.jopdesign.sys.Native;
import com.jopdesign.sys.JVMHelp;

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

	// Disk state
	boolean mounted;
	int sectorSize;    // 128 or 256 bytes
	int sectorCount;   // total sectors on disk

	// Sector data buffer (max 256 bytes = 64 words)
	int[] sectorBuf = new int[64];

	public SioDiskEmu() {
		cmdPending = false;
		mounted = false;
		sectorSize = 128;
		sectorCount = 0;
	}

	/**
	 * Process a pending SIO command frame.
	 * Called from main loop when cmdPending is true.
	 */
	public void processCommand() {
		cmdPending = false;

		// Read command frame from RX FIFO (5 bytes)
		int[] frame = new int[5];
		int count = 0;
		while (count < 5 && (Native.rd(IoAddr.SIOBRIDGE_RX_STATUS) & 0x01) == 0) {
			frame[count] = Native.rd(IoAddr.SIOBRIDGE_RX_DATA) & 0xFF;
			count++;
		}

		// Drain any extra bytes (noise, partial frames)
		while ((Native.rd(IoAddr.SIOBRIDGE_RX_STATUS) & 0x01) == 0) {
			Native.rd(IoAddr.SIOBRIDGE_RX_DATA);
		}

		if (count < 5) {
			// Short frame — ignore
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
			// Bad checksum — send NAK
			delayUs(T2_DELAY);
			enableTx();
			sendByte(SIO_NAK);
			waitTxDone();
			disableTx();
			return;
		}

		// Dispatch command
		int sector = (aux2 << 8) | aux1;
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
			// Unknown command — NAK
			delayUs(T2_DELAY);
			enableTx();
			sendByte(SIO_NAK);
			waitTxDone();
			disableTx();
			break;
		}
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
	 * Read sector data into sectorBuf. Override in Phase 4 for ATR.
	 * Returns false if read failed.
	 */
	boolean readSectorData(int sector, int size) {
		// Stub: fill with zeros (Phase 4 will read from ATR file)
		for (int i = 0; i < size / 4; i++) {
			sectorBuf[i] = 0;
		}
		return mounted;
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

	/** Wait for TX FIFO to drain completely (all bytes serialized). */
	void waitTxDone() {
		while ((Native.rd(IoAddr.SIOBRIDGE_TX_STATUS) & 0x01) == 0) {
			// TX FIFO not empty — wait
		}
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
