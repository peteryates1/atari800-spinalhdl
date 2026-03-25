package atari800

import spinal.core._
import jop.config._
import jop.io._
import jop.memory.JopMemoryConfig
import jop.pipeline.JumpTableInitData
import jop.utils.JopFileLoader
import jop.memory.SdramDeviceInfo

/**
 * JOP configuration for unified-clock Atari+JOP build.
 *
 * Both JOP and Atari run at 56.67 MHz from a single PLL.
 * 32 MB SDRAM (W9825G6JH6) via BmbSdramCtrl32 (Altera IP).
 * AlteraLpm memory style required for Cyclone IV.
 */
object JopCoreForAtariDualPll {

  val memDevice = MemoryDevice.W9825G6JH6

  val clkFreqHz = 56670000L

  def config: JopCoreConfig = JopCoreConfig(
    memConfig = JopMemoryConfig(
      addressWidth = 24,
      mainMemSize  = 32 * 1024 * 1024,
      burstLen     = 0
    ),
    useDspMul = true,
    supersetJumpTable = JumpTableInitData.serial,
    devices = Map(
      "uart"      -> DeviceInstance(DeviceType.Uart, params = Map("baudRate" -> 500000)),
      "sdSpi"     -> DeviceInstance(DeviceType.SdSpi, params = Map("clkDivInit" -> 199)),
      "vgaText"   -> DeviceInstance(DeviceType.VgaText),
      "atariCtrl" -> DeviceInstance(DeviceType.Custom(
        key = "atariCtrl",
        addrBits = 4,
        registerNames = Seq(
          (0, "STATUS_CTRL"), (1, "CART_SELECT"), (2, "CONFIG"),
          (3, "PADDLE_01"), (4, "PADDLE_23"), (5, "PADDLE_45"), (6, "PADDLE_67"),
          (7, "JOY_12"), (8, "JOY_34"),
          (9, "KB_THROTTLE"),
          (10, "CART_SLOT_ADDR"), (11, "CART_SLOT_DATA")
        ),
        factory = (_, _, _) => new AtariCtrl
      ))
    ),
    clkFreq = HertzNumber(clkFreqHz),
    memoryStyle = Some(MemoryStyle.AlteraLpm("../../jop-spinalhdl/asm/generated/serial")),
    useSyncRam = Some(true)
  )

  // Microcode ROM paths (relative to project root = atari800-spinalhdl/)
  private val jopAsmBase = "jop-spinalhdl/asm/generated"

  /** Stack RAM (constant table) initialization. */
  def simRamInit: Seq[BigInt] = JopFileLoader.loadStackRam(s"$jopAsmBase/mem_ram.dat")

  /** Microcode ROM for hardware serial boot. */
  def hwRomInit: Seq[BigInt] = JopFileLoader.loadMicrocodeRom(s"$jopAsmBase/serial/mem_rom.dat")
}
