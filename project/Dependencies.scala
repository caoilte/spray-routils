import sbt._
import Keys._

object Dependencies {

  object V {
    val scalaTest = "2.1.5"
    val spray = "1.3.1"
    val akka = "2.3.2"
  }

  object C {
    val sprayCan = "io.spray" % "spray-can" % V.spray
    val sprayRouting = "io.spray" % "spray-routing" % V.spray
    val akka = "com.typesafe.akka" %% "akka-actor" % V.akka
  }

  object T {
    val scalaTest = "org.scalatest" %% "scalatest" % V.scalaTest % "test"
    val akkaTestKit = "com.typesafe.akka" %% "akka-testkit" % V.akka % "test"
    val sprayClient = "io.spray" % "spray-client" % V.spray % "test"
  }


  val settings = Seq(libraryDependencies ++= Seq(
    C.sprayCan,
    C.sprayRouting,
    C.akka,
    T.scalaTest,
    T.akkaTestKit,
    T.sprayClient
  ))
}
