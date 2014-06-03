package org.caoilte.spray.routing

import org.scalatest.FlatSpec
import akka.testkit.TestKit
import com.typesafe.config.ConfigFactory
import akka.actor.{ActorRef, Actor, Props, ActorSystem}
import spray.routing.{Route, HttpServiceActor}
import spray.http._
import spray.can.Http
import scala.concurrent._
import scala.concurrent.duration._
import spray.client.pipelining._
import akka.io.{Tcp, IO}
import akka.pattern.ask
import akka.util.Timeout
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.time.{Seconds, Span}
import spray.http.HttpRequest
import spray.http.HttpResponse
import spray.httpx.UnsuccessfulResponseException
import scala.util.Failure

class LogAccessRoutingTests extends FlatSpec with ScalaFutures {

  behavior of "An HTTP Server that handles a request with a 200 response within the request timeout"

  it should "Log Access Once with the Correct Request, Response and Access Times" in {
    aTestLogAccessRoutingActor(
      requestTimeoutMillis = 4000,
      responseOrExceptionIfNone = Some(Response(500))) { testKit =>
      import testKit._

      whenReady(makeHttpCall, timeout(Span(2, Seconds))) { s =>
        assert(s.entity.asString(HttpCharsets.`UTF-8`) === Response.DEFAULT_RESPONSE)
      }

      expectMsgPF(3 seconds, "Expected normal log event with response delayed properties") {
        case LogEvent(
        HttpRequest(HttpMethods.GET,URI, _, _, _),
        HttpResponse(StatusCodes.OK, _, _, _), time, false
        ) if time >= 500 && time <= 4000 => true
      }
      expectNoMsg(3 seconds)
    }
  }

  behavior of "An HTTP Server with a route that throws an Exception within the request timeout"

  it should "Log Access Once with the Correct Request, Response and Access Times" in {
    aTestLogAccessRoutingActor(
      requestTimeoutMillis = 4000,
      responseOrExceptionIfNone = None) { testKit =>
      import testKit._


      whenReady(makeHttpCall, timeout(Span(2, Seconds))) { s =>
        assert(s.status.intValue == 500)
      }

      expectMsgPF(3 seconds, "Expected normal log event with error response properties") {
        case LogEvent(
        HttpRequest(HttpMethods.GET,URI, _, _, _),
        HttpResponse(StatusCodes.InternalServerError, _, _, _), time, false
        ) if time <= 4000 => true
      }
      expectNoMsg(3 seconds)
    }
  }

  behavior of "An HTTP Server that handles a request with a 200 response outside of the request timeout"

  it should "Log Access Once with the Correct Request, Standard Timeout Error Response and Request Timeout time" in {
    aTestLogAccessRoutingActor(
      requestTimeoutMillis = 50,
      responseOrExceptionIfNone = Some(Response(500))) { testKit =>
      import testKit._

      whenReady(makeHttpCall, timeout(Span(2, Seconds))) { s =>
        assert(s.status.intValue == 500)
      }

      expectMsgPF(3 seconds, "Expected normal log event with with timeout properties") {
        case LogEvent(
        HttpRequest(HttpMethods.GET,URI, _, _, _),
        HttpResponse(StatusCodes.InternalServerError, _, _, _), 50, false
        ) => true
      }

      expectMsgPF(3 seconds, "Expected already logged event with response delayed properties") {
        case LogEvent(
        HttpRequest(HttpMethods.GET,URI, _, _, _),
        HttpResponse(StatusCodes.OK, _, _, _), time, true
        ) if time >= 500 => true
      }
    }
  }




  implicit val TIMEOUT: Timeout = 3.second
  val PORT = 8084
  val HOST = s"http://localhost:$PORT"
  val PATH = "test"
  val URI:Uri = Uri(s"$HOST/$PATH")
  case class LogEvent(request: HttpRequest, response: HttpResponse, time: Long, alreadyLogged: Boolean)

  class TestAccessLogger(listener:ActorRef) extends AccessLogger {
    override def logAccess(request: HttpRequest, response: HttpResponse, time: Long) = {
      listener ! LogEvent(request, response, time, false)
    }

    override def accessAlreadyLogged(request: HttpRequest, response: HttpResponse, time: Long) = {
      listener ! LogEvent(request, response, time, true)
    }
  }

  
  def CONFIG(requestTimeout:String = "1 s") =
    s"""
      |spray.can {
      |  server {
      |    request-timeout = $requestTimeout
      |    idle-timeout = 10 s
      |    registration-timeout = 100 s
      |  }
      |}
      |akka {
      |  loglevel="DEBUG"
      |  debug {
      |    receive = on
      |  }
      |}
      |
    """.stripMargin

  def aTestLogAccessRoutingActor(
                                  requestTimeoutMillis:Long,
                                  responseOrExceptionIfNone:Option[Response]
                                  )
                                (callback: TestKit => Unit) {
    val config = ConfigFactory.parseString(CONFIG(s"$requestTimeoutMillis ms"))
    implicit val system = ActorSystem("test-system", config)
    val testKit = new TestKit(system)

    try {
      val accessLogger = new TestAccessLogger(testKit.testActor)
      val serviceActor = system.actorOf(Props(
        new TestLogAccessRoutingActor(accessLogger, responseOrExceptionIfNone, PATH))
      )

      val sprayServerStartResult = IO(Http).ask(Http.Bind(serviceActor, "localhost", PORT)).flatMap {
        case b: Http.Bound ⇒ Future.successful(b)
        case Tcp.CommandFailed(b: Http.Bind) ⇒
          Future.failed(new RuntimeException(
            "Binding failed. Switch on DEBUG-level logging for `akka.io.TcpListener` to log the cause."))
      }(system.dispatcher)

      assert(sprayServerStartResult.isReadyWithin(3 second))
      callback(testKit)
    } finally {
      system.shutdown()
    }
  }

  def makeHttpCall(implicit system: ActorSystem):Future[HttpResponse] = {
    import system.dispatcher
    val pipeline: HttpRequest => Future[HttpResponse] =
      sendReceive

    pipeline(Get(Uri(s"$HOST/$PATH")))
  }



}
