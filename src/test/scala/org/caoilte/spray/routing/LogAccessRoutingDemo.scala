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
import spray.routing.Route


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


  def demoArgs():Either[Route,(Long, Long)] = {
    try {
      Right((args(0).toLong, args(1).toLong))
    } catch {
      case e:Exception => {
        if (args.length == 1 && args(0).toString().equals("exception")) {
          Left(FailureRoutes.exceptionRoute)
        } else if (args.length == 1 && args(0).toString().equals("fail")) {
          Left(FailureRoutes.failureRoute)
        } else {
          println(
            """Failed to parse demo args.
            |Possibilities are 'reStart <request-timeout-in-millis> <response-delay-in-millis>', eg
            |  Example Timeout Demo                : 'reStart 500 1000'
            |  Example Success Demo                : 'reStart 1000 500'
            |Or 'reStart <failure-type>' eg,
            |  Example Exception Throwing Demo     : 'reStart exception'
            |  Example Fails with Exception Demo   : 'reStart fail'
            |""".stripMargin)
          System.exit(1)
          throw e
        }
      }
    }
  }

  val exceptionRouteOrDemoArgs = demoArgs()
  val (
    requestTimeoutInMillis, responseDelayInMillis) = exceptionRouteOrDemoArgs.right.getOrElse(1000L, 1000L)

  val serviceActorProps = {
    exceptionRouteOrDemoArgs.fold( route => {
      RouteServiceActor.apply(new DemoAccessLogger, route, "hello")
    }, tupleArgs => {
      DelayedResponseServiceActor.apply(new DemoAccessLogger, DelayedResponse(tupleArgs._2), "hello")
    })
  }

  val config = ConfigFactory.parseString(
    s"""
      |spray.can {
      |  server {
      |    routils-request-timeout = $requestTimeoutInMillis ms
      |    request-timeout = infinite
      |    idle-timeout = 100 s
      |    registration-timeout = 100 s
      |  }
      |}
    """.stripMargin)

  implicit val TIMEOUT: Timeout = 3.second
  implicit val system = ActorSystem("log-access-routing-demo", config)


  val serviceActor = system.actorOf(serviceActorProps)


  val serverStartedFuture = IO(Http).ask(Http.Bind(serviceActor, "localhost", 8085)).flatMap {
    case b: Http.Bound ⇒ Future.successful(b)
    case Tcp.CommandFailed(b: Http.Bind) ⇒
      // TODO: replace by actual exception when Akka #3861 is fixed.
      //       see https://github.com/akka/akka/issues/13861
      Future.failed(new RuntimeException(
        "Binding failed. Switch on DEBUG-level logging for `akka.io.TcpListener` to log the cause."))
  }(system.dispatcher)

  Await.result(serverStartedFuture, 2 second)
  println("LogAccessRoutingDemo started. Try it out on http://localhost:8085/hello")

}
