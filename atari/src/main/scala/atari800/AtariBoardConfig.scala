package atari800

import jop.config.JopCoreConfig

/**
 * Board-specific configuration for the Atari 800 + JOP design.
 *
 * Combines the SDRAM memory map (board-dependent) with the JOP core
 * configuration (FPGA-size-dependent).  Smaller FPGAs need minimal cache
 * settings; larger FPGAs can afford wider caches.
 *
 * SDRAM memory layout convention:
 *   0x000000 ... atariSdramBase-1  : JOP code + heap (loaded from .jop)
 *   atariSdramBase ... end          : Atari RAM + ROM images
 *
 * atariSdramBase must be aligned to the Atari address space size (8MB = 2^23).
 */
case class AtariBoardConfig(
  sdramBytes:     Long,          // total physical SDRAM in bytes
  atariSdramBase: Long,          // Atari byte-address 0 maps to this physical SDRAM byte address
  jopConfig:      JopCoreConfig  // JOP core + cache config (size depends on FPGA resources)
) {
  require(
    atariSdramBase % (1L << 23) == 0,
    s"atariSdramBase 0x${atariSdramBase.toHexString} must be 8MB-aligned (multiple of 0x800000)"
  )
  require(
    atariSdramBase + (1L << 23) <= sdramBytes,
    s"Atari SDRAM region (0x${atariSdramBase.toHexString}..0x${(atariSdramBase + (1L<<23) - 1).toHexString}) exceeds SDRAM size (0x${sdramBytes.toHexString})"
  )

  /**
   * One-bit prefix to prepend to the Atari's 23-bit SDRAM_ADDR to form
   * the 24-bit SdramArbiter port-A address.
   *
   * atariSdramBase must be < 2^24 (fits within the 24-bit arbiter address space).
   */
  def atariAddrPrefix: Int = {
    require(
      atariSdramBase < (1L << 24),
      s"atariSdramBase 0x${atariSdramBase.toHexString} exceeds 24-bit arbiter address space"
    )
    (atariSdramBase >> 23).toInt  // 1 bit: 0 or 1
  }
}
