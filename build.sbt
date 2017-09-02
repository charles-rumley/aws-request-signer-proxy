name := "aws-request-signer-proxy"
organization := "com.charlesrumley"

version := "0.1"

maintainer in Docker := "charles.rumley@gmail.com"
dockerRepository := Some("docker.io/charlesrumley")

// open a port for Play
dockerExposedPorts := Seq(9000)

enablePlugins(JavaAppPackaging)

lazy val root = (project in file(".")).enablePlugins(PlayScala)

scalaVersion := "2.11.11"

resolvers += Resolver.jcenterRepo

libraryDependencies += "com.amazonaws" % "aws-java-sdk" % "1.11.136"
libraryDependencies += "io.ticofab" %% "aws-request-signer" % "0.5.1"
libraryDependencies += "com.netaporter" %% "scala-uri" % "0.4.16"

libraryDependencies += filters
libraryDependencies += ws
libraryDependencies += "org.scalatestplus.play" %% "scalatestplus-play" % "2.0.0" % Test