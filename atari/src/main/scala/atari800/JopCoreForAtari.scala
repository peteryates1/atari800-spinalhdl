package atari800

import spinal.core._
import jop.config._
import jop.io._
import jop.memory.JopMemoryConfig
import jop.pipeline.JumpTableInitData

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

  private def configBase(
    blockBits: Int, ocacheWayBits: Int, ocacheIndexBits: Int,
    acacheWayBits: Int, acacheFieldBits: Int
  ): JopCoreConfig = JopCoreConfig(
    blockBits = blockBits,
    memConfig = JopMemoryConfig(
      addressWidth = 24,
      burstLen     = 0,       // single-word SDRAM access (via SdramArbiter)
      useOcache         = true,
      ocacheWayBits     = ocacheWayBits,
      ocacheIndexBits   = ocacheIndexBits,
      useAcache         = true,
      acacheWayBits     = acacheWayBits,
      acacheFieldBits   = acacheFieldBits
    ),
    useDspMul = true,
    supersetJumpTable = JumpTableInitData.serialDsp,
    ioConfig = IoConfig(
      hasSdSpi    = true,
      hasVgaText  = true,
      uartBaudRate = 115200,
      sdSpiClkDivInit = 112,   // ~250 kHz init at 56.67 MHz
      extensionDevices = Seq(
        IoDeviceDescriptor(
          name = "atariCtrl",
          addrBits = 4,         // 16 registers (12 used)
          interruptCount = 0,
          coreZeroOnly = true,
          registerNames = Seq(
            (0, "STATUS_CTRL"), (1, "CART_SELECT"), (2, "CONFIG"),
            (3, "PADDLE_01"), (4, "PADDLE_23"), (5, "PADDLE_45"), (6, "PADDLE_67"),
            (7, "JOY_12"), (8, "JOY_34"),
            (9, "KB_THROTTLE"),
            (10, "CART_SLOT_ADDR"), (11, "CART_SLOT_DATA")
          ),
          factory = _ => new AtariCtrl
        )
      )
    ),
    clkFreq = HertzNumber(56670000)
  )
}
