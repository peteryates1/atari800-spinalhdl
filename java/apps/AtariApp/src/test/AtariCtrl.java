package test;

import com.jopdesign.hw.HardwareObject;
import com.jopdesign.sys.Const;

/**
 * Hardware object for the AtariCtrl I/O device.
 *
 * AtariCtrl is allocated by IoAddressAllocator at base 0xC0
 * (IO_BASE + 0x40 = -128 + 64 = -64), with 16 register slots (addrBits=4).
 *
 * Field declaration order must match register address order.
 */
public class AtariCtrl extends HardwareObject {

    static final int BASE = Const.IO_BASE + 0x40; // 0xC0 = -64

    private static AtariCtrl instance = null;

    public static AtariCtrl getInstance() {
        if (instance == null) {
            instance = (AtariCtrl) make(new AtariCtrl(), BASE, 1);
        }
        return instance;
    }

    /** reg 0: STATUS_CTRL — bit 0: cold reset pulse, bit 1: OSD enable */
    public volatile int statusCtrl;
    /** reg 1: CART_SELECT — cartridge bank/type select */
    public volatile int cartSelect;
    /** reg 2: CONFIG — bits[1:0] RAM size, bit2 NTSC, bit5 A800 mode */
    public volatile int config;
    /** reg 3: PADDLE_01 — paddle positions for ports 1 and 2 */
    public volatile int paddle01;
    /** reg 4: PADDLE_23 — paddle positions for ports 3 and 4 */
    public volatile int paddle23;
    /** reg 5: PADDLE_45 — paddle positions for ports 5 and 6 */
    public volatile int paddle45;
    /** reg 6: PADDLE_67 — paddle positions for ports 7 and 8 */
    public volatile int paddle67;
    /** reg 7: JOY_12 — joystick directions for ports 1 and 2 */
    public volatile int joy12;
    /** reg 8: JOY_34 — joystick directions for ports 3 and 4 */
    public volatile int joy34;
    /** reg 9: CONSOL_THROTTLE — [13:8]=throttle, [4]=start, [3]=select, [2]=option */
    public volatile int consolThrottle;
    /** reg 10: CART_SLOT_ADDR — cartridge slot address */
    public volatile int cartSlotAddr;
    /** reg 11: CART_SLOT_DATA — cartridge slot data */
    public volatile int cartSlotData;
    /** reg 12: KEYBOARD — [5:0]=scanCode, [8]=pressed, [9]=shift, [10]=ctrl, [11]=break */
    public volatile int keyboard;
}
