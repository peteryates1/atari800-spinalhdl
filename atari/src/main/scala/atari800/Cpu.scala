package atari800

import spinal.core._
import spinal.core.sim._

class Cpu extends Component {
  val io = new Bundle {
    val ENABLE       = in  Bool()
    val DI           = in  Bits(8 bits)
    val IRQ_n        = in  Bool()
    val NMI_n        = in  Bool()
    val MEMORY_READY = in  Bool()
    val THROTTLE     = in  Bool()
    val RDY          = in  Bool()
    val DO           = out Bits(8 bits)
    val A            = out Bits(16 bits)
    val R_W_n        = out Bool()
    val CPU_FETCH    = out Bool()
  }

  val CPU_ENABLE = io.ENABLE & io.MEMORY_READY & io.THROTTLE
  CPU_ENABLE.simPublic()

  // Signals
  val debugOpcode  = UInt(8 bits)
  val debugPc      = UInt(16 bits)
  debugPc.simPublic()
  val debugA       = UInt(8 bits)
  debugA.simPublic()
  val debugX       = UInt(8 bits)
  debugX.simPublic()
  val debugY       = UInt(8 bits)
  debugY.simPublic()
  val debugS       = UInt(8 bits)
  debugS.simPublic()
  val debugFlags   = UInt(8 bits)
  debugFlags.simPublic()
  val diUnsigned   = UInt(8 bits)
  diUnsigned.simPublic()
  val doUnsigned   = UInt(8 bits)
  doUnsigned.simPublic()
  val addrUnsigned = UInt(16 bits)
  addrUnsigned.simPublic()
  val CPU_ENABLE_RDY = Bool()
  CPU_ENABLE_RDY.simPublic()
  val WE           = Bool()
  WE.simPublic()
  val nmiPendingNext = Bool()
  val nmiPendingReg  = Reg(Bool()) init False
  nmiPendingReg.simPublic()
  val nmiNAdjusted   = Bool()
  nmiNAdjusted.simPublic()
  val nmiNReg        = Reg(Bool()) init True
  val nmiEdge        = Bool()
  val CPU_ENABLE_RESET = Bool()
  val notRdy       = Bool()

  diUnsigned := io.DI.asUInt

  val cpu6502 = new Cpu65xx(
    pipelineOpcode = false,
    pipelineAluMux = false,
    pipelineAluOut = false
  )
  cpu6502.io.enable      := CPU_ENABLE_RDY
  cpu6502.io.halt        := False
  cpu6502.io.reset       := clockDomain.isResetActive
  cpu6502.io.nmi_n       := nmiNAdjusted
  cpu6502.io.irq_n       := io.IRQ_n
  cpu6502.io.d           := diUnsigned
  doUnsigned             := cpu6502.io.q
  addrUnsigned           := cpu6502.io.addr
  WE                     := cpu6502.io.we
  debugOpcode            := cpu6502.io.debugOpcode
  debugPc                := cpu6502.io.debugPc
  debugA                 := cpu6502.io.debugA
  debugX                 := cpu6502.io.debugX
  debugY                 := cpu6502.io.debugY
  debugS                 := cpu6502.io.debugS
  debugFlags             := cpu6502.io.debug_flags

  CPU_ENABLE_RDY    := (CPU_ENABLE & (io.RDY | WE)) | clockDomain.isResetActive
  CPU_ENABLE_RESET  := CPU_ENABLE | clockDomain.isResetActive
  notRdy            := ~io.RDY

  nmiEdge           := ~io.NMI_n & nmiNReg
  nmiPendingNext    := (nmiEdge & ~(io.RDY | WE)) | (nmiPendingReg & ~io.RDY) | (nmiPendingReg & io.RDY & ~CPU_ENABLE)
  nmiNAdjusted      := ~nmiPendingReg & io.NMI_n

  // Register
  nmiPendingReg := nmiPendingNext
  nmiNReg       := io.NMI_n

  // Outputs
  io.R_W_n    := ~WE
  io.DO       := doUnsigned.asBits
  io.A        := addrUnsigned.asBits
  io.CPU_FETCH := io.ENABLE & io.THROTTLE
}
