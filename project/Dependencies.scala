import sbt._
import Keys._

object Dependencies {

  object V {
    val scalaTest = "2.1.5"
    val spray211 = "1.3.1-20140423"
    val spray210 = "1.3.1"
    val akka = "2.3.2"
  }

  object C {
    object `2.10` {
      val sprayCan = "io.spray" % "spray-can" % V.spray210
      val sprayRouting = "io.spray" % "spray-routing" % V.spray210
    }
    object `2.11` {
      val sprayCan = "io.spray" %% "spray-can" % V.spray211
      val sprayRouting = "io.spray" %% "spray-routing" % V.spray211
    }
    val akka = "com.typesafe.akka" %% "akka-actor" % V.akka
  }

  object T {
    object `2.10` {
      val sprayClient = "io.spray" % "spray-client" % V.spray210 % "test"
    }
    object `2.11` {
      val sprayClient = "io.spray" %% "spray-client" % V.spray211 % "test"
    }
    val scalaTest = "org.scalatest" %% "scalatest" % V.scalaTest % "test"
    val akkaTestKit = "com.typesafe.akka" %% "akka-testkit" % V.akka % "test"
  }

  val commonDependencies = Seq(
    C.akka,
    T.scalaTest,
    T.akkaTestKit
  )

  val settings = Seq(
    resolvers += "spray repo" at "http://repo.spray.io",
    libraryDependencies := {
      CrossVersion.partialVersion(scalaVersion.value) match {
        case Some((2, scalaMajor)) if scalaMajor >= 11 =>
          libraryDependencies.value ++ Seq(
            C.`2.11`.sprayCan,
            C.`2.11`.sprayRouting,
            T.`2.11`.sprayClient) ++ commonDependencies
        case _ =>
          libraryDependencies.value ++ Seq(
            C.`2.10`.sprayCan,
            C.`2.10`.sprayRouting,
            T.`2.10`.sprayClient) ++ commonDependencies
      }
    }
  )
}
