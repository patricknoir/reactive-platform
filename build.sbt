import sbt.Keys.organization

name := "reactive-platform"

resolvers ++= Seq(
  "bintray" at "http://jcenter.bintray.com"
)

val commonSettings = Seq(
  version := "1.0.0-SNAPSHOT",
  organization := "org.patricknoir.platform",
  scalaVersion := "2.12.1",
  dockerRepository := Some("patricknoir")
)

val Versions = new {
  val Circe = "0.7.0"
  val Cats = "0.9.0"
  val ReactiveSystem = "0.3.1"
  val Akka = "2.4.17"
  val Kafka = "0.10.2.0"
}

val commonDependencies = Seq(
  "io.circe"                        %% "circe-core"                 % Versions.Circe,
  "io.circe"                        %% "circe-generic"              % Versions.Circe,
  "io.circe"                        %% "circe-parser"               % Versions.Circe,
  "org.typelevel"                   %% "cats"                       % Versions.Cats,
  "org.patricknoir.kafka"           %% "kafka-reactive-service"     % Versions.ReactiveSystem,
  "com.typesafe.akka"               %% "akka-actor"                 % Versions.Akka,
  "com.typesafe.akka"               %% "akka-agent"                 % Versions.Akka,
  "com.typesafe.akka"               %% "akka-camel"                 % Versions.Akka,
  "com.typesafe.akka"               %% "akka-cluster"               % Versions.Akka,
  "com.typesafe.akka"               %% "akka-cluster-metrics"       % Versions.Akka,
  "com.typesafe.akka"               %% "akka-cluster-sharding"      % Versions.Akka,
  "com.typesafe.scala-logging"      %% "scala-logging"              % "3.5.0",
  "ch.qos.logback"                  %  "logback-classic"            % "1.1.3",
  "org.iq80.leveldb"                %  "leveldb"                    % "0.7",
  "org.fusesource.leveldbjni"       %  "leveldbjni-all"             % "1.8",
  "org.apache.kafka"                %%  "kafka"                     % Versions.Kafka,
  "com.orbitz.consul"               % "consul-client"               % "0.14.0",
  "org.mousio"                      % "etcd4j"                      % "2.13.0"
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

/** Examples: **/

val walletSystem = project.in(file("examples/wallet-system"))
  .settings(commonSettings)
  .settings(libraryDependencies ++= commonDependencies)
  .dependsOn(root)
  .enablePlugins(DockerPlugin, AshScriptPlugin)

val protocol = project.in(file("examples/wallet-system/protocol"))
  .settings(commonSettings)
  .settings(libraryDependencies ++= commonDependencies)
  .dependsOn(root)

val walletService = project
  .in(file("examples/wallet-system/service/wallet-service"))
  .settings(commonSettings)
  .settings(libraryDependencies ++= commonDependencies)
  .dependsOn(protocol, root)

val walletClient = project.in(file("examples/wallet-client"))
  .settings(commonSettings)
  .settings(libraryDependencies ++= commonDependencies)
  .dependsOn(protocol, root)

lazy val publishSite = taskKey[Unit]("publish the site under /docs")
val dest = (baseDirectory / "docs")

lazy val documentation =
  project
    .in(file("documentation"))
    .settings(commonSettings:_*)
    .settings(
      name := "documentation",
      paradoxTheme := Some(builtinParadoxTheme("generic")),
      paradoxProperties in Compile ++= Map(
        "scaladoc.rfc.base_url" -> s"https://patricknoir.github.io/reactive-platform/api/%s.html"
      ),
      paradoxNavigationDepth := 3,
      publishSite := Def.task {
        println("Executing task publishSite!")
        val docsDir = (baseDirectory / "../docs").value
        val internalApiDir = (baseDirectory / "../docs/api").value
        val apidocsDir = (baseDirectory / "../apidocs").value
        val siteDir = (paradox in Compile).value //** "*"
        IO.delete(docsDir)
        IO.createDirectory(docsDir)
        IO.copyDirectory(siteDir, docsDir, true)
        IO.copyDirectory(apidocsDir, internalApiDir, true)
      }.value
    ).enablePlugins(ParadoxPlugin)