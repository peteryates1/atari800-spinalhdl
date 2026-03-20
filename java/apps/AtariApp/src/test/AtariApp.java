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
    //   bit 0       = PAL (1=PAL, 0=NTSC)
    //   bits [3:1]  = ramSelect (001=64K, 011=48K)
    //   bit 4       = turboVblankOnly
    //   bit 5       = atari800mode (1=Atari 800, 0=XL/XE)
    //   bit 6       = hiresEna
    static final int CFG_PAL        = 0x01;
    static final int CFG_RAM_48K    = 0x06;  // ramSelect=011 in bits [3:1]
    static final int CFG_A800_MODE  = 0x20;  // bit 5

    // CONSOL_THROTTLE register (reg 9) bit layout:
    //   bits [13:8] = throttleCount (cycle_length - 1 = 31)
    //   bit 4       = consolStart
    //   bit 3       = consolSelect
    //   bit 2       = consolOption
    static final int THROTTLE_31 = 31 << 8;  // 0x1F00

    // STATUS_CTRL register (reg 0) bits
    static final int CTRL_OSD_ENABLE = 0x01;
    static final int CTRL_COLD_RESET = 0x80;

    // Timing intervals in microseconds
    static final int HEARTBEAT_US = 1000000; // 1 second
    static final int WD_INTERVAL  = 500000;  // kick watchdog every 500 ms

    public static void main(String[] args) {

        SysDevice sys       = SysDevice.getInstance();
        AtariCtrl  atari    = AtariCtrl.getInstance();

        System.out.println("AtariApp: JOP controller starting");

        // ----------------------------------------------------------------
        // Configure AtariCtrl for BRAM-only Atari 800
        // ----------------------------------------------------------------

        // Set CONFIG: PAL, 48KB RAM, Atari 800 mode
        atari.config = CFG_PAL | CFG_RAM_48K | CFG_A800_MODE;

        // Set throttle (cycle_length - 1 = 31), no console keys pressed
        atari.consolThrottle = THROTTLE_31;

        // Disable OSD, pulse cold reset to start Atari with new config
        atari.statusCtrl = CTRL_COLD_RESET;

        System.out.println("AtariApp: Atari configured - PAL, 48K, Atari 800 mode");

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
