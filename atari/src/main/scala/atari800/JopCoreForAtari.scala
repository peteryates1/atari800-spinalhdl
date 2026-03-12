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

  def config: JopCoreConfig = JopCoreConfig(
    blockBits = 6,            // 64 blocks × 64 bytes each
    memConfig = JopMemoryConfig(
      addressWidth = 24,
      burstLen     = 0,       // single-word SDRAM access (via SdramArbiter)
      useOcache         = true,
      ocacheWayBits     = 6,    // 64 entries (objects)
      ocacheIndexBits   = 4,    // 16 fields per entry
      useAcache         = true,
      acacheWayBits     = 5,    // 32 entries (arrays)
      acacheFieldBits   = 3     // 8 elements per line
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
          addrBits = 4,         // 16 registers
          interruptCount = 0,
          coreZeroOnly = true,
          registerNames = Seq(
            (0, "STATUS_CTRL"), (1, "CART_SELECT"), (2, "CONFIG"),
            (3, "PADDLE0"), (4, "PADDLE1"), (5, "PADDLE2"), (6, "PADDLE3"),
            (7, "JOY1"), (8, "JOY2"), (9, "JOY3"), (10, "JOY4"),
            (11, "KEYBOARD"), (12, "THROTTLE")
          ),
          factory = _ => new AtariCtrl
        )
      )
    ),
    clkFreq = HertzNumber(56670000)
  )
}
