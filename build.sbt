// atari800-spinalhdl: Atari 800 FPGA core + JOP soft-core integration
//
// Unified build: single Scala version, JOP as direct dependency.
//
// Usage:
//   sbt "atari/runMain atari800.Atari800JopTopSv"

// Reference jop-spinalhdl submodule as an SBT project
lazy val jopRef = RootProject(file("jop-spinalhdl"))

lazy val atari = (project in file("atari"))
  .dependsOn(jopRef)
  .settings(
    name := "atari800",
    scalaVersion := "2.13.18",
    libraryDependencies += compilerPlugin(
      "com.github.spinalhdl" %% "spinalhdl-idsl-plugin" % "1.14.0"
    ),
    libraryDependencies += "org.scalatest" %% "scalatest" % "3.2.19" % Test,
    fork := true,
    Test / fork := true,
    Test / parallelExecution := false,
    Test / envVars ++= Seq("FB_DEBUG", "FB_TRACE").flatMap(k => sys.env.get(k).map(k -> _)).toMap,
    Compile / run / baseDirectory := (ThisBuild / baseDirectory).value
  )
