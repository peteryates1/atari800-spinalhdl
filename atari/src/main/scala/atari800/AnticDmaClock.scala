package atari800

import spinal.core._
import spinal.core.sim._

class AnticDmaClock extends Component {
  val io = new Bundle {
    val enableDma       = in  Bool()
    val playfieldStart  = in  Bool()
    val playfieldEnd    = in  Bool()
    val vblank          = in  Bool()
    val slowDma         = in  Bool()
    val mediumDma       = in  Bool()
    val fastDma         = in  Bool()
    val dmaClockOut0    = out Bool()
    val dmaClockOut1    = out Bool()
    val dmaClockOut2    = out Bool()
    val dmaClockOut3    = out Bool()
  }

  val dmaShiftregReg  = Reg(Bits(8 bits)) init B(0, 8 bits)
  dmaShiftregReg.simPublic()
  val dmaShiftregNext = Bits(8 bits)

  dmaShiftregReg := dmaShiftregNext

  // Next state
  val tick = (dmaShiftregReg(0) & io.slowDma) | (dmaShiftregReg(4) & io.mediumDma) | (dmaShiftregReg(6) & io.fastDma)

  dmaShiftregNext := dmaShiftregReg

  when(io.enableDma) {
    dmaShiftregNext :=
      (~(~tick | io.playfieldEnd | io.vblank)).asBits ##
      (~(~io.playfieldStart & ~dmaShiftregReg(7) | io.vblank)).asBits ##
      dmaShiftregReg(6 downto 1)
  }

  // Output
  io.dmaClockOut0 := dmaShiftregReg(6) & io.enableDma
  io.dmaClockOut1 := dmaShiftregReg(5) & io.enableDma
  io.dmaClockOut2 := dmaShiftregReg(4) & io.enableDma
  io.dmaClockOut3 := dmaShiftregReg(3) & io.enableDma
}
