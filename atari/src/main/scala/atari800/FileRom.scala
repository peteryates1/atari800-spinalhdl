package atari800

import spinal.core._
import java.nio.file.{Files, Paths}

/** ROM loaded from a binary .rom file at elaboration time.
  * Replaces the old Scala-embedded ROM classes (Os16, Basic, Os8, Os2, Os5200, Os16Loop).
  *
  * @param filePath  Path to the .rom binary file
  * @param size      Expected size in bytes (used for address width calculation)
  * @param writable  If true, ROM can be written via DMA (for supervisor loading)
  */
class FileRom(filePath: String, size: Int, writable: Boolean = false) extends Component {
  val addrWidth = log2Up(size)

  val io = new Bundle {
    val clock   = in  Bool()
    val address = in  UInt(addrWidth bits)
    val we      = in  Bool()
    val data    = in  Bits(8 bits)
    val q       = out Bits(8 bits)
  }

  val initData: Seq[Bits] = {
    val path = Paths.get(filePath)
    if (Files.exists(path)) {
      val bytes = Files.readAllBytes(path)
      println(s"[FileRom] Loaded $filePath (${bytes.length} bytes, expected $size)")
      require(bytes.length == size, s"ROM file $filePath is ${bytes.length} bytes, expected $size")
      bytes.map(b => B((b.toInt & 0xFF), 8 bits)).toSeq
    } else {
      println(s"[FileRom] WARNING: $filePath not found, using zeros ($size bytes)")
      Seq.fill(size)(B(0x00, 8 bits))
    }
  }

  val rom = Mem(Bits(8 bits), initialContent = initData)

  if (writable) {
    rom.write(io.address, io.data, io.we)
  }

  io.q := rom.readSync(io.address)
}
