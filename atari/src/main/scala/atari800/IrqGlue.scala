package atari800

import spinal.core._

class IrqGlue extends Component {
  val io = new Bundle {
    val pokeyIrq   = in  Bool()
    val piaIrqa    = in  Bool()
    val piaIrqb    = in  Bool()
    val pbiIrq     = in  Bool()
    val combinedIrq = out Bool()
  }

  io.combinedIrq := io.pokeyIrq & io.piaIrqa & io.piaIrqb & io.pbiIrq
}
