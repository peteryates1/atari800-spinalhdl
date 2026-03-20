package atari800

import spinal.core._
import jop.config._
import jop.io._
import jop.memory.JopMemoryConfig
import jop.pipeline.JumpTableInitData
import jop.utils.JopFileLoader

/**
 * JOP configuration for the Atari 800 board.
 *
 * Not a Component — just a JopCoreConfig factory.
 * Atari800JopTop instantiates JopCore directly with this config.
 */
object JopCoreForAtari {

  // Cache sizes: "large" for 10CL025 (fills available BRAM),
  //              "default" for ECP5 i5 (fits in 24K LUTs)
  def config: JopCoreConfig = configSmall

  /** Simulation config: pre-loaded SDRAM boot (no UART download wait). */
  def configForSim: JopCoreConfig = configSmallForSim

  // Microcode ROM paths (relative to project root = atari800-spinalhdl/)
  private val jopAsmBase = "jop-spinalhdl/asm/generated"

  /** Microcode ROM for simulation boot (JumpTableData / DspJumpTableData). */
  def simRomInit: Seq[BigInt] = JopFileLoader.loadMicrocodeRom(s"$jopAsmBase/mem_rom.dat")

  /** Stack RAM (constant table) initialization — same for sim and hardware. */
  def simRamInit: Seq[BigInt] = JopFileLoader.loadStackRam(s"$jopAsmBase/mem_ram.dat")

  /** Microcode ROM for hardware serial boot (SerialJumpTableData / SerialDspJumpTableData). */
  def hwRomInit: Seq[BigInt] = JopFileLoader.loadMicrocodeRom(s"$jopAsmBase/serial/mem_rom.dat")

  def configLarge: JopCoreConfig = configBase(
    blockBits       = 6,   // 64 blocks
    ocacheWayBits   = 6,   // 64 entries
    ocacheIndexBits = 4,   // 16 fields
    acacheWayBits   = 5,   // 32 entries
    acacheFieldBits = 3    // 8 elements
  )

  def configSmall: JopCoreConfig = configBase(
    blockBits       = 4,   // 16 blocks (default)
    ocacheWayBits   = 4,   // 16 entries (default)
    ocacheIndexBits = 4,   // 16 fields
    acacheWayBits   = 4,   // 16 entries (default)
    acacheFieldBits = 2    // 4 elements (default)
  )

  /** configSmall with simulation jump table (pre-loaded SDRAM, no UART wait). */
  def configSmallForSim: JopCoreConfig = configBase(
    blockBits       = 4,
    ocacheWayBits   = 4,
    ocacheIndexBits = 4,
    acacheWayBits   = 4,
    acacheFieldBits = 2,
    simBoot         = true
  )

  private def configBase(
    blockBits: Int, ocacheWayBits: Int, ocacheIndexBits: Int,
    acacheWayBits: Int, acacheFieldBits: Int,
    simBoot: Boolean = false
  ): JopCoreConfig = JopCoreConfig(
    blockBits = blockBits,
    memConfig = JopMemoryConfig(
      addressWidth = 24,
      mainMemSize  = 256 * 1024,  // 256 KB BRAM (AtariSupervisor.jop is ~45 KB)
      burstLen     = 0,       // single-word access
      useOcache         = true,
      ocacheWayBits     = ocacheWayBits,
      ocacheIndexBits   = ocacheIndexBits,
      useAcache         = true,
      acacheWayBits     = acacheWayBits,
      acacheFieldBits   = acacheFieldBits
    ),
    useDspMul = true,
    supersetJumpTable = if (simBoot) JumpTableInitData.simulation else JumpTableInitData.serial,
    devices = Map(
      "uart"      -> DeviceInstance(DeviceType.Uart, params = Map("baudRate" -> 500000)),
      "sdSpi"     -> DeviceInstance(DeviceType.SdSpi, params = Map("clkDivInit" -> 112)),
      "vgaText"   -> DeviceInstance(DeviceType.VgaText),
      "atariCtrl" -> DeviceInstance(DeviceType.Custom(
        key = "atariCtrl",
        addrBits = 4,         // 16 registers (12 used)
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
    clkFreq = HertzNumber(56670000)
  )
}
