// atari800-spinalhdl: Atari 800 FPGA core + JOP soft-core integration
//
// Multi-project build:
//   atari     — Atari 800 core (Scala 2.12 / SpinalHDL 1.10.2a)
//   jopBridge — JOP board wrapper generator (Scala 2.13 / SpinalHDL 1.12.2)
//
// Usage:
//   sbt "jopBridge/runMain jop.system.GenerateJopForAtari"
//   sbt "atari/runMain atari800.Atari800JopTopSv"

lazy val root = (project in file("."))
  .settings(
    name := "atari800-spinalhdl",
    publish / skip := true
  )
  .aggregate(atari, jopBridge)

lazy val atari = (project in file("atari"))
  .settings(
    name := "atari800",
    scalaVersion := "2.12.18",
    libraryDependencies ++= Seq(
      "com.github.spinalhdl" %% "spinalhdl-core" % "1.10.2a",
      "com.github.spinalhdl" %% "spinalhdl-lib"  % "1.10.2a",
      "com.github.spinalhdl" %% "spinalhdl-sim"  % "1.10.2a",
      compilerPlugin("com.github.spinalhdl" %% "spinalhdl-idsl-plugin" % "1.10.2a")
    ),
    fork := true,
    Compile / run / baseDirectory := (ThisBuild / baseDirectory).value
  )

// Reference jop-spinalhdl submodule as an SBT project
lazy val jopRef = RootProject(file("jop-spinalhdl"))

lazy val jopBridge = (project in file("jop-bridge"))
  .dependsOn(jopRef)
  .settings(
    name := "jop-bridge",
    scalaVersion := "2.13.18",
    fork := true,
    Compile / run / baseDirectory := (ThisBuild / baseDirectory).value
  )
