package jop.system

import spinal.core._

/**
 * Generator for JopCoreForAtari — thin wrapper that controls
 * the output directory for the atari800-spinalhdl project.
 *
 * The JopCoreForAtari case class lives in jop-spinalhdl (where it
 * has access to JopCore, JopCoreConfig, etc.). This generator just
 * invokes it with the correct targetDirectory.
 *
 * Usage: sbt "jopBridge/runMain jop.system.GenerateJopForAtari"
 */
object GenerateJopForAtari extends App {
  val config = SpinalConfig(
    mode            = SystemVerilog,
    targetDirectory = "generated/",
    defaultConfigForClockDomains = ClockDomainConfig(
      resetKind        = ASYNC,
      resetActiveLevel = LOW
    ),
    defaultClockDomainFrequency = FixedFrequency(56.67 MHz)
  )

  config.generateSystemVerilog {
    val vgaDomain = ClockDomain.external("vga",
      config = ClockDomainConfig(
        resetKind        = ASYNC,
        resetActiveLevel = LOW
      ),
      frequency = FixedFrequency(25 MHz)
    )
    JopCoreForAtari(vgaDomain)
  }.printPruned()
}
