package org.caoilte.spray.routing

import akka.actor.{Props, ActorSystem}
import akka.io.{Tcp, IO}
import spray.can.Http
import scala.concurrent.{Await, Future}
import akka.pattern.ask
import spray.http.{HttpResponse, HttpRequest}
import com.typesafe.config.ConfigFactory
import akka.util.Timeout
import scala.concurrent.duration._


class DemoAccessLogger extends AccessLogger {
  override def logAccess(request: HttpRequest, response: HttpResponse, time: Long) = {

    val method = request.method.value
    val path = request.uri.path
    val protocol = request.protocol.value
    val responseCode = response.status.intValue.toString
    val responseSize = response.message.entity.data.length
    println(s""""$method $path $protocol" $responseCode $responseSize $time""")
  }

  override def accessAlreadyLogged(request: HttpRequest, response: HttpResponse, time: Long) = {

    val method = request.method.value
    val path = request.uri.path
    val protocol = request.protocol.value
    val responseCode = response.status.intValue.toString
    val responseSize = response.message.entity.data.length

    println(
      s"""THIS WOULD HAVE BEEN RESPONSE IF TIMEOUT HADN'T OCCURRED
         |"$method $path $protocol" $responseCode $responseSize $time"""".stripMargin)
  }
}

object LogAccessRoutingDemo extends App {
  def demoArgs():(Long, Long) = {
    try {
      (args(0).toLong, args(1).toLong)
    } catch {
      case e:Exception => {
        println("""Failed to parse demo args.
          |Best usage is 'reStart requestTimeoutInMillis responseDelayInMillis' in sbt.
          |Example Timeout Demo     : 'reStart 500 1000'
          |Example Success Response : 'reStart 1000 500'""".stripMargin)
        System.exit(1)
        throw e
      }
    }
  }

  val (requestTimeoutInMillis, responseDelayInMillis) = demoArgs()
  val config = ConfigFactory.parseString(
    s"""
      |spray.can {
      |  server {
      |    request-timeout = $requestTimeoutInMillis ms
      |    idle-timeout = 100 s
      |    registration-timeout = 100 s
      |  }
      |}
    """.stripMargin)

  implicit val TIMEOUT: Timeout = 1.second
  implicit val system = ActorSystem("log-access-routing-demo", config)


  val serviceActor = system.actorOf(Props(
    new TestLogAccessRoutingActor(new DemoAccessLogger, responseDelayInMillis, "Hello World!", "hello"))
  )


  val serverStartedFuture = IO(Http).ask(Http.Bind(serviceActor, "localhost", 8085)).flatMap {
    case b: Http.Bound ⇒ Future.successful(b)
    case Tcp.CommandFailed(b: Http.Bind) ⇒
      // TODO: replace by actual exception when Akka #3861 is fixed.
      //       see https://www.assembla.com/spaces/akka/tickets/3861
      Future.failed(new RuntimeException(
        "Binding failed. Switch on DEBUG-level logging for `akka.io.TcpListener` to log the cause."))
  }(system.dispatcher)

  Await.result(serverStartedFuture, 1 second)
  println("LogAccessRoutingDemo started. Try it out on http://localhost:8085/hello")

}
