organization := "org.caoilte"

name := "spray-routils"

version := "0.0.1-SNAPSHOT"

scalaVersion := "2.10.4"

Dependencies.settings

Revolver.settings

mainClass in Revolver.reStart := Some("org.caoilte.spray.routing.LogAccessRoutingDemo")

fullClasspath in Revolver.reStart <<= fullClasspath in Test