// atari800-spinalhdl: Atari 800 FPGA core (SpinalHDL) + RP2040/Pico supervisor.
//
// Usage (generate SystemVerilog for a board top):
//   sbt "atari/runMain retro.boards.Atari800WukongSv"
//   sbt "atari/runMain retro.boards.Atari800Rp2040HdmiLgSv"

lazy val atari = (project in file("atari"))
  .settings(
    name := "atari800",
    scalaVersion := "2.13.18",
    libraryDependencies ++= Seq(
      "com.github.spinalhdl" %% "spinalhdl-core" % "1.12.2",
      "com.github.spinalhdl" %% "spinalhdl-lib"  % "1.12.2",
      compilerPlugin("com.github.spinalhdl" %% "spinalhdl-idsl-plugin" % "1.12.2")
    ),
    libraryDependencies += "org.scalatest" %% "scalatest" % "3.2.19" % Test,
    fork := true,
    Test / fork := true,
    Test / parallelExecution := false,
    Test / envVars ++= Seq("FB_DEBUG", "FB_TRACE").flatMap(k => sys.env.get(k).map(k -> _)).toMap,
    Compile / run / baseDirectory := (ThisBuild / baseDirectory).value
  )
