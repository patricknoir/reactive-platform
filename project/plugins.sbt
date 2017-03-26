logLevel := Level.Warn

addCompilerPlugin(
  "org.scalamacros" % "paradise" % "2.1.0" cross CrossVersion.full
)

addSbtPlugin("com.lightbend.paradox" % "sbt-paradox" % "0.2.9")