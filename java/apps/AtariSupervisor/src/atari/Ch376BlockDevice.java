package atari;

import com.jopdesign.fat32.BlockDevice;
import com.jopdesign.sys.JVMHelp;

/**
 * BlockDevice implementation using CH376T USB/SD controller.
 * Wraps Ch376Driver to provide the standard BlockDevice interface
 * for use with Fat32FileSystem.
 *
 * On boards with a CH376T (e.g., ATARI-800-LG-V1), the SdSpi hardware
 * talks to the CH376T over SPI. The CH376T manages SD card communication
 * internally — we use its DISK_READ command for raw sector reads.
 */
public class Ch376BlockDevice implements BlockDevice {
    private Ch376Driver ch376;

    public Ch376BlockDevice() {
        ch376 = new Ch376Driver();
    }

    public boolean init() {
        if (!ch376.init()) return false;
        return ch376.initSdCard();
    }

    public boolean readBlock(int sectorNum, int[] buf) {
        return ch376.readSector(sectorNum, buf);
    }

    public boolean writeBlock(int sectorNum, int[] buf) {
        // CH376T write not implemented yet
        return false;
    }
}
