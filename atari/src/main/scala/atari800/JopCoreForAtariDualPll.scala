package atari800

import spinal.core._
import jop.config._
import jop.io._
import jop.memory.JopMemoryConfig
import jop.pipeline.JumpTableInitData
import jop.utils.JopFileLoader

/**
 * JOP configuration for dual-PLL Atari+JOP build.
 *
 * JOP runs at 80 MHz (dram_pll c1) with 2M baud UART.
 * 256KB BRAM for serial boot (AtariSupervisor.jop is ~45 KB).
 * AlteraLpm memory style required for Cyclone IV.
 */
object JopCoreForAtariDualPll {

  def config: JopCoreConfig = JopCoreConfig(
    memConfig = JopMemoryConfig(
      mainMemSize  = 128 * 1024,
      burstLen     = 0
    ),
    useDspMul = true,
    supersetJumpTable = JumpTableInitData.serial,
    devices = Map(
      "uart"      -> DeviceInstance(DeviceType.Uart, params = Map("baudRate" -> 2000000)),
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
    clkFreq = HertzNumber(80000000),
    memoryStyle = Some(MemoryStyle.AlteraLpm),
    useSyncRam = Some(true)
  )

  // Microcode ROM paths (relative to project root = atari800-spinalhdl/)
  private val jopAsmBase = "jop-spinalhdl/asm/generated"

  /** Stack RAM (constant table) initialization. */
  def simRamInit: Seq[BigInt] = JopFileLoader.loadStackRam(s"$jopAsmBase/mem_ram.dat")

  /** Microcode ROM for hardware serial boot. */
  def hwRomInit: Seq[BigInt] = JopFileLoader.loadMicrocodeRom(s"$jopAsmBase/serial/mem_rom.dat")
}
