package retro.machines.atari
import retro.common.util._
import retro.common.video._
import retro.common.scaler._
import retro.common.sdram._
import retro.link._

// USB HID (boot keyboard) usage code -> Atari 800 keyboard matrix position.
// Extracted from the proven Ch376UsbKeyboard (V1.1 board) so the RP2040
// supervisor path shares exactly the same mapping.
// Entry: 7 bits, bit 6 = valid, bits 5:0 = matrix position.
object AtariHidMap {
  def table: IndexedSeq[Int] = {
    val m = Array.fill(128)(0)
    // Letters a-z: HID 0x04..0x1D
    val letterMap = Seq(
      63, 21, 18, 58, 42, 56, 61, 57,  // a-h
      13,  1,  5,  0, 37, 35,  8, 10,  // i-p
      47, 40, 62, 45, 11, 16, 46, 22,  // q-x
      43, 23                           // y-z
    )
    for (i <- letterMap.indices) m(i + 0x04) = 0x40 | letterMap(i)
    // Digits 1-9,0: HID 0x1E..0x27
    val digitMap = Seq(31, 30, 26, 24, 29, 27, 51, 53, 48, 50)
    for (i <- digitMap.indices) m(i + 0x1E) = 0x40 | digitMap(i)
    // Specials
    val specialMap = Seq(
      (0x28, 12), // Return
      (0x29, 28), // Escape
      (0x2A, 52), // Backspace
      (0x2B, 44), // Tab
      (0x2C, 33), // Space
      (0x2D, 14), // Minus
      (0x2E, 15), // Equals
      (0x2F,  6), // [ -> Atari +
      (0x30,  7), // ] -> Atari *
      (0x31, 54), // \ -> Atari <
      (0x33,  2), // ; -> Atari ;
      (0x34, 55), // ' -> Atari >
      (0x35, 39), // ` -> Atari INVERSE
      (0x36, 32), // , -> Atari ,
      (0x37, 34), // . -> Atari .
      (0x38, 38), // / -> Atari /
      (0x39, 60), // Caps Lock -> CAPS
      (0x3A, 17), // F1 -> HELP
      (0x3B,  3), // F2 -> 1200XL F1
      (0x3C,  4), // F3 -> 1200XL F2
      (0x3D, 19)  // F4 -> 1200XL F3
    )
    for ((hid, atari) <- specialMap) m(hid) = 0x40 | atari
    m.toIndexedSeq
  }
  // HID codes handled outside the matrix (same as Ch376UsbKeyboard):
  //   0x3E F5 -> Start, 0x3F F6 -> Select, 0x40 F7 -> Option, 0x48 Pause -> Break
}
