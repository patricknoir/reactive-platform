import sbt.Keys.organization

name := "reactive-platform"


val commonSettings = Seq(
  version := "1.0.0-SNAPSHOT",
  organization := "org.patricknoir.platform",
  scalaVersion := "2.12.1",
  dockerRepository := Some("patricknoir")
)

val Versions = new {
  val Circe = "0.7.0"
  val Cats = "0.9.0"
  val ReactiveSystem = "0.3.0"
  val Akka = "2.4.17"
}

val commonDependencies = Seq(
  "io.circe" %% "circe-core" % Versions.Circe,
  "io.circe" %% "circe-generic" % Versions.Circe,
  "io.circe" %% "circe-parser" % Versions.Circe,
  "org.typelevel" %% "cats" % Versions.Cats,
  "org.patricknoir.kafka" %% "kafka-reactive-service" % Versions.ReactiveSystem,
  "com.typesafe.akka" %% "akka-actor" % Versions.Akka,
  "com.typesafe.akka" %% "akka-agent" % Versions.Akka,
  "com.typesafe.akka" %% "akka-camel" % Versions.Akka,
  "com.typesafe.akka" %% "akka-cluster" % Versions.Akka,
  "com.typesafe.akka" %% "akka-cluster-metrics" % Versions.Akka,
  "com.typesafe.akka" %% "akka-cluster-sharding" % Versions.Akka,
  "com.typesafe.scala-logging" %% "scala-logging" % "3.5.0",
  "ch.qos.logback" % "logback-classic" % "1.1.3",
  "org.iq80.leveldb"            % "leveldb"          % "0.7",
  "org.fusesource.leveldbjni"   % "leveldbjni-all"   % "1.8"
)



val root = project
            .in(file("."))
            .settings(commonSettings)
            .settings(libraryDependencies ++= commonDependencies)
            .enablePlugins(DockerPlugin, AshScriptPlugin)

val server = project.in(file("server/"))
              .settings(commonSettings)
              .settings(libraryDependencies ++= commonDependencies)
              .dependsOn(root)
              .enablePlugins(DockerPlugin, AshScriptPlugin)

val client = project.in(file("client/"))
  .settings(commonSettings)
  .settings(libraryDependencies ++= commonDependencies)
  .dependsOn(root)
  .enablePlugins(DockerPlugin, AshScriptPlugin)