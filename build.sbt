name := "reactive-platform"

version := "1.0.0-SNAPSHOT"
organization := "com.lottomart.platform"
scalaVersion := "2.12.1"

val Versions = new {
  val Circe = "0.7.0"
  val Cats = "0.9.0"
  val ReactiveSystem = "0.3.0"
  val Akka = "2.4.17"
}

libraryDependencies ++= Seq(
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
