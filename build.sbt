lazy val root = project
  .in(file("."))
  .aggregate(core)

val commonWarts = Seq(
  Wart.AsInstanceOf,
  Wart.EitherProjectionPartial,
  Wart.Null,
  Wart.OptionPartial,
  Wart.Product,
  Wart.Return,
  // Wart.TraversableOps,
  Wart.TryPartial,
  Wart.Var
)

lazy val commonSettings = Seq(
  organization                := "zkweb",
  scalaVersion                := "2.13.8",
  run / fork                  := true,
  addCompilerPlugin("org.typelevel" % "kind-projector"     % "0.13.2" cross CrossVersion.full),
  addCompilerPlugin("com.olegpy"   %% "better-monadic-for" % "0.3.1" cross CrossVersion.binary),
  scalacOptions += "-Ymacro-annotations",
  scalacOptions += "-Xsource:3",
  testFrameworks += new TestFramework("munit.Framework"),
  scalafmtOnCompile           := true,
  Global / cancelable         := true,
  javaOptions ++= Seq(
    "-XX:+UseG1GC",
    "-Xmx600m",
    "-Xms600m",
    "-XX:SurvivorRatio=8",
    "-Duser.timezone=UTC"
  ),
  compile / wartremoverErrors := commonWarts,
  Compile / wartremoverErrors := commonWarts,
  version ~= (_.replace('+', '-')),
  dynver ~= (_.replace('+', '-'))
)

lazy val core = project
  .in(file("core"))
  .enablePlugins(PackPlugin)
  .settings(commonSettings)
  .settings(
    libraryDependencies ++= Seq(
      "org.log4s"         %% "log4s"             % "1.8.2",
      "org.apache.curator" % "curator-framework" % "5.2.1",
      "io.netty"           % "netty-codec-http"  % "4.1.63.Final",
      "ch.qos.logback"     % "logback-classic"   % "1.3.0-alpha14"
    )
  )
  .settings(packGenerateWindowsBatFile := false)
