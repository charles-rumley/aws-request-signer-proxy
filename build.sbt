name := "aws-signed-request-proxy"
organization := "com.charlesrumley"

version := "1.0-SNAPSHOT"

lazy val root = (project in file(".")).enablePlugins(PlayScala)

scalaVersion := "2.11.11"

resolvers += Resolver.jcenterRepo

libraryDependencies += "com.amazonaws" % "aws-java-sdk" % "1.11.136"
libraryDependencies += "io.ticofab" % "aws-request-signer_2.11" % "0.3.0"
libraryDependencies += "com.netaporter" %% "scala-uri" % "0.4.16"

libraryDependencies += filters
libraryDependencies += ws
libraryDependencies += "org.scalatestplus.play" %% "scalatestplus-play" % "2.0.0" % Test