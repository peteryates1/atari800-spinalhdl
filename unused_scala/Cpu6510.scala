package atari800

import spinal.core._

class Cpu6510(
  pipelineOpcode: Boolean = false,
  pipelineAluMux: Boolean = false,
  pipelineAluOut: Boolean = false,
  emulateBitfade: Boolean = false,
  emulate01Write: Boolean = false
) extends Component {
  val io = new Bundle {
    val ena_1khz     = in  Bool() default False
    val enable       = in  Bool()
    val halt         = in  Bool()
    val reset        = in  Bool()
    val nmi_n        = in  Bool()
    val irq_n        = in  Bool()

    val we           = out Bool()
    val a            = out UInt(16 bits)
    val d            = in  UInt(8 bits)
    val q            = out UInt(8 bits)

    val vic_last_data = in  UInt(8 bits) default U(0xFF, 8 bits)
    val diIO         = in  UInt(8 bits)
    val doIO         = out UInt(8 bits)

    val debugOpcode  = out UInt(8 bits)
    val debugJam     = out Bool()
    val debugPc      = out UInt(16 bits)
    val debugA       = out UInt(8 bits)
    val debugX       = out UInt(8 bits)
    val debugY       = out UInt(8 bits)
    val debugS       = out UInt(8 bits)
    val debug_flags  = out UInt(8 bits)
    val debug_io     = out UInt(8 bits)
  }

  val localA  = UInt(16 bits)
  val localD  = UInt(8 bits)
  val localQ  = UInt(8 bits)
  val localWe = Bool()

  val currentIO = UInt(8 bits)
  val ioDir     = Reg(UInt(8 bits))
  val ioData    = Reg(UInt(8 bits))

  val accessingIO = (localA(15 downto 1) === U(0, 15 bits))
  val ioFade      = Reg(UInt(8 bits)) init U(0, 8 bits)

  val cpuInstance = new Cpu65xx(
    pipelineOpcode = pipelineOpcode,
    pipelineAluMux = pipelineAluMux,
    pipelineAluOut = pipelineAluOut
  )
  cpuInstance.io.enable := io.enable
  cpuInstance.io.halt   := io.halt
  cpuInstance.io.reset  := io.reset
  cpuInstance.io.nmi_n  := io.nmi_n
  cpuInstance.io.irq_n  := io.irq_n
  cpuInstance.io.d      := localD
  localQ                := cpuInstance.io.q
  localA                := cpuInstance.io.addr
  localWe               := cpuInstance.io.we
  io.debugOpcode        := cpuInstance.io.debugOpcode
  io.debugJam           := cpuInstance.io.debugJam
  io.debugPc            := cpuInstance.io.debugPc
  io.debugA             := cpuInstance.io.debugA
  io.debugX             := cpuInstance.io.debugX
  io.debugY             := cpuInstance.io.debugY
  io.debugS             := cpuInstance.io.debugS
  io.debug_flags        := cpuInstance.io.debug_flags

  // Data mux
  localD := io.d
  when(accessingIO) {
    when(~localA(0)) {
      localD := ioDir
    } otherwise {
      localD := currentIO
    }
  }

  // IO register writes
  when(accessingIO && localWe && io.enable) {
    when(~localA(0)) {
      ioDir := localQ
    } otherwise {
      ioData := localQ
    }
  }
  when(io.reset) {
    ioDir := U(0, 8 bits)
  }

  // Current IO
  for (i <- 0 until 8) {
    when(~ioDir(i)) {
      currentIO(i) := io.diIO(i)
    } otherwise {
      currentIO(i) := ioData(i)
    }
  }
  if (emulateBitfade) {
    currentIO(7) := ioFade(7)
    currentIO(6) := ioFade(6)
    currentIO(3) := ioFade(3)
  }

  // Connect outputs
  io.a  := localA
  io.q  := (if (emulate01Write) Mux(accessingIO, io.vic_last_data, localQ) else localQ)
  io.we := localWe
  io.doIO := currentIO
  io.debug_io := U(0, 2 bits) @@ (ioData(5 downto 0) | ~ioDir(5 downto 0))
}
