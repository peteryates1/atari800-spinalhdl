package atari800

import spinal.core._
import scala.io.Source

// The 8x16 supervisor/overlay font, single-sourced from the firmware header
// firmware/supervisor/font8x16.h (which was itself extracted from
// VgaTextOverlayDevice) so the on-screen text renders identically whether the
// RP2040 rasterises it (framebuffer path) or the FPGA generates it directly
// (TextOverlay720). 128 glyphs x 16 rows, bit7 = leftmost pixel.
object Font8x16 {
  val rows = 128 * 16

  // Parse every 0xNN byte in the header's array. sbt runs from the repo root.
  val data: Seq[Int] = {
    val src   = Source.fromFile("firmware/supervisor/font8x16.h").mkString
    val bytes = "0x[0-9A-Fa-f]{2}".r.findAllIn(src).map(s => Integer.parseInt(s.substring(2), 16)).toArray
    require(bytes.length >= rows, s"font8x16.h parse: expected >= $rows bytes, got ${bytes.length}")
    bytes.take(rows).toSeq
  }

  // As a Mem init vector.
  def initBits: Seq[Bits] = data.map(b => B(b & 0xFF, 8 bits))
}
