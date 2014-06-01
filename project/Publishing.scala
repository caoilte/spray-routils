import sbt._
import Keys._

object Publishing {
  val artifactoryHost = scala.util.Properties.envOrNone("ARTIFACTORY_HOST") getOrElse("")
  val artifactoryPort = scala.util.Properties.envOrNone("ARTIFACTORY_PORT") getOrElse("")
  val artifactoryPath = scala.util.Properties.envOrNone("ARTIFACTORY_PATH") getOrElse("")
  val artifactoryUsername = scala.util.Properties.envOrNone("ARTIFACTORY_USERNAME") getOrElse("")
  val artifactoryPassword = scala.util.Properties.envOrNone("ARTIFACTORY_PASSWORD") getOrElse("")

  val settings = Seq (
    publishTo := Some("Artifactory Realm" at "http://"+artifactoryHost+":"+artifactoryPort+artifactoryPath),
    credentials := Seq(Credentials("Artifactory Realm", artifactoryHost, artifactoryUsername, artifactoryPassword)),
    publishMavenStyle := true
  )
}