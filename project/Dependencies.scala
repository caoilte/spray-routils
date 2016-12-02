import sbt._
import Keys._

object Dependencies {

  object V {
    val scalaTest = "3.0.0"
    val spray = "1.3.4"
    val akka = "2.3.9"
  }

  object C {
    val sprayCan = "io.spray" %% "spray-can" % V.spray
    val sprayRouting = "io.spray" %% "spray-routing-shapeless23" % V.spray
    val akka = "com.typesafe.akka" %% "akka-actor" % V.akka
  }

  object T {
    val sprayClient = "io.spray" %% "spray-client" % V.spray % "test"
    val scalaTest = "org.scalatest" %% "scalatest" % V.scalaTest % "test"
    val akkaTestKit = "com.typesafe.akka" %% "akka-testkit" % V.akka % "test"
  }

  val dependencies = Seq(
    C.akka,
    T.scalaTest,
    T.akkaTestKit,
    C.sprayCan,
    C.sprayRouting,
    T.sprayClient
  )

  val settings = Seq(
    resolvers += "spray repo" at "http://repo.spray.io",
    libraryDependencies := dependencies
  )
}
