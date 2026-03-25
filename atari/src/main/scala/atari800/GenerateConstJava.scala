package atari800

import jop.config._
import jop.generate.ConstGenerator

/**
 * Generate Const.java from the Atari JOP configuration.
 *
 * Builds a JopConfig from JopCoreForAtariDualPll (or JopCoreForAtari)
 * and calls ConstGenerator.generate() to produce Const.java.
 *
 * Usage:
 *   sbt "atari/runMain atari800.GenerateConstJava"           # dual-PLL (default)
 *   sbt "atari/runMain atari800.GenerateConstJava single"    # single-PLL
 *   sbt "atari/runMain atari800.GenerateConstJava --write"   # write to file
 */
object GenerateConstJava {

  /** Build a JopConfig wrapping the given JopCoreConfig for Const generation. */
  def jopConfigFrom(coreConfig: JopCoreConfig, name: String): JopConfig = JopConfig(
    assembly = SystemAssembly.qmtechWithDb,
    systems = Seq(JopSystem(
      name = name,
      memory = "bram",
      bootMode = BootMode.Serial,
      clkFreq = coreConfig.clkFreq,
      coreConfig = coreConfig
    ))
  )

  def main(args: Array[String]): Unit = {
    val useSingle = args.contains("single")
    val writeToFile = args.contains("--write")

    val (coreConfig, configName) = if (useSingle) {
      (JopCoreForAtari.config, "atari-single-pll")
    } else {
      (JopCoreForAtariDualPll.config, "atari-dual-pll")
    }

    val jopConfig = jopConfigFrom(coreConfig, configName)
    val source = ConstGenerator.generate(jopConfig)

    if (writeToFile) {
      val path = "jop-spinalhdl/java/runtime/src/jop/com/jopdesign/sys/Const.java"
      val writer = new java.io.PrintWriter(path)
      writer.print(source)
      writer.close()
      println(s"Wrote $path")
    } else {
      println(source)
    }
  }
}
