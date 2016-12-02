organization := "org.caoilte"

name := "spray-routils"

scalaVersion := "2.11.8"

Dependencies.settings

Publishing.settings

Revolver.settings

mainClass in Revolver.reStart := Some("org.caoilte.spray.routing.LogAccessRoutingDemo")

fullClasspath in Revolver.reStart <<= fullClasspath in Test

scalacOptions := Seq("-unchecked", "-deprecation", "-feature", "-encoding", "utf8", "-language", "postfixOps")

publishTo := Some("Sonatype Snapshots Nexus" at "https://itvrepos.artifactoryonline.com/itvrepos/cps-libs")

