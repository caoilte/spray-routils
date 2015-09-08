organization := "org.caoilte"

name := "spray-routils"

scalaVersion := "2.11.7"

Dependencies.settings

releaseSettings

Publishing.settings

Revolver.settings

mainClass in Revolver.reStart := Some("org.caoilte.spray.routing.LogAccessRoutingDemo")

fullClasspath in Revolver.reStart <<= fullClasspath in Test

scalacOptions := Seq("-unchecked", "-deprecation", "-feature", "-encoding", "utf8", "-language", "postfixOps")
