package test;

import com.jopdesign.hw.SysDevice;

/**
 * Atari800 + JOP controller application.
 *
 * Runs on JOP in simulation (BootMode.Simulation) and on hardware.
 * Configures the Atari 800 core via AtariCtrl and then loops,
 * printing periodic heartbeat messages over UART.
 *
 * I/O address layout (IoAddressAllocator output for ep4cgx150 build):
 *   BmbSys    0xF0-0xFF  (SysDevice — timing, watchdog)
 *   BmbUart   0xE0-0xEF  (System.out via JOPOutputStream)
 *   BmbVgaText 0xD0-0xDF (not used directly here)
 *   AtariCtrl 0xC0-0xCF  (AtariCtrl hardware object)
 *   BmbSdSpi  0xBC-0xBF  (not used here)
 */
public class AtariApp {

    // CONFIG register (reg 2) bit layout:
    //   bits[1:0] = ramSelect (00=64K, 01=128K, 10=48K, 11=reserved)
    //   bit 2     = 0=PAL, 1=NTSC
    //   bit 5     = 0=XL/XE mode, 1=Atari 800 mode
    static final int CFG_RAM_64K   = 0x00;
    static final int CFG_PAL       = 0x00;
    static final int CFG_XLXE_MODE = 0x00;

    // STATUS_CTRL register (reg 0) bits
    static final int CTRL_COLD_RESET = 0x01;

    // Timing intervals in microseconds
    static final int HEARTBEAT_US = 1000000; // 1 second
    static final int WD_INTERVAL  = 500000;  // kick watchdog every 500 ms

    public static void main(String[] args) {

        SysDevice sys       = SysDevice.getInstance();
        AtariCtrl  atari    = AtariCtrl.getInstance();

        // ----------------------------------------------------------------
        // Configure AtariCtrl
        // ----------------------------------------------------------------

        // Set CONFIG: PAL, 64KB RAM, XL/XE mode
        atari.config = CFG_RAM_64K | CFG_PAL | CFG_XLXE_MODE;

        // Clear STATUS_CTRL (no OSD, no reset)
        atari.statusCtrl = 0;

        // Issue a cold reset to start the Atari core with the new config
        atari.statusCtrl = CTRL_COLD_RESET;

        System.out.println("AtariApp: JOP controller starting");
        System.out.println("AtariApp: Atari configured - PAL, 64KB, XL/XE mode");

        // ----------------------------------------------------------------
        // Main loop — heartbeat + watchdog
        // ----------------------------------------------------------------
        int nextHb  = sys.uscntTimer + HEARTBEAT_US;
        int nextWd  = sys.uscntTimer + WD_INTERVAL;
        int wdVal   = 0;
        int seconds = 0;

        while (true) {
            int now = sys.uscntTimer;

            if (now - nextWd >= 0) {
                wdVal = ~wdVal;
                sys.wd = wdVal;
                nextWd = now + WD_INTERVAL;
            }

            if (now - nextHb >= 0) {
                System.out.println("AtariApp: heartbeat t=" + seconds + "s");
                seconds++;
                nextHb = now + HEARTBEAT_US;
            }
        }
    }
}
