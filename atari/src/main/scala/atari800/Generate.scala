package atari800

import spinal.core._

object Atari800CoreAc608Vhdl extends App {
  val config = SpinalConfig(
    mode                 = VHDL,
    targetDirectory      = "generated/",
    defaultConfigForClockDomains = ClockDomainConfig(
      resetKind        = ASYNC,
      resetActiveLevel = LOW
    ),
    defaultClockDomainFrequency = FixedFrequency(56.67 MHz)
  )

  config.generateVhdl(new Atari800CoreAc608).printPruned()
}

object Atari800CoreAc608Verilog extends App {
  val config = SpinalConfig(
    mode                 = Verilog,
    targetDirectory      = "generated/",
    defaultConfigForClockDomains = ClockDomainConfig(
      resetKind        = ASYNC,
      resetActiveLevel = LOW
    ),
    defaultClockDomainFrequency = FixedFrequency(56.67 MHz)
  )

  config.generateVerilog(new Atari800CoreAc608).printPruned()
}

object Atari800JopTopSv extends App {
  val config = SpinalConfig(
    mode                 = SystemVerilog,
    targetDirectory      = "generated/",
    defaultConfigForClockDomains = ClockDomainConfig(
      resetKind        = ASYNC,
      resetActiveLevel = LOW
    ),
    defaultClockDomainFrequency = FixedFrequency(56.67 MHz)
  )

  config.generateSystemVerilog(new Atari800JopTop).printPruned()
}
