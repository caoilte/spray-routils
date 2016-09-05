package org.caoilte.spray.routing

import org.caoilte.spray.routing.TestAccessLogger.{LogAccess, LogEvent}
import org.scalatest.{FlatSpec, GivenWhenThen}
import akka.testkit.TestKit
import com.typesafe.config.ConfigFactory
import akka.actor.{ActorRef, ActorSystem, Props}
import spray.http.HttpHeaders.Accept
import spray.routing.{Directives, Route}
import spray.http._
import spray.can.Http

import scala.concurrent._
import scala.concurrent.duration._
import spray.client.pipelining._
import akka.io.{IO, Tcp}
import akka.pattern.ask
import akka.util.Timeout
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.time.{Seconds, Span}


object FailureRoutes extends Directives {

  val exceptionRoute = {
    parameterMap { params => // stops exception being thrown during actor creation
      throw new Exception("Test Exception")
    }
  }
  val failureRoute = {
    failWith(new RequestProcessingException(StatusCodes.InternalServerError,"Test Exception"))
  }
}

object SuccessRoutes extends Directives {
  val chunkedResponseRoute:RouteCreator = new RouteCreator {
    override def apply(system: ActorSystem): Route = {
      implicit val actorRefFac = system
      parameterMap { params =>
        Directives.getFromResource("veryLargeFile.txt")
      }
    }
  }
}

class LogAccessRoutingTests extends FlatSpec with ScalaFutures with GivenWhenThen {

  behavior of "An HTTP Server that handles a request with a 200 response within the request timeout"

  it should "'Log Access' with a 200 response and an Access Time less than the request timeout" in {
    aTestLogAccessRoutingActor(
      requestTimeoutMillis = 4000,
      httpServiceActorFactory = DelayedResponseServiceActor.factory(DelayedResponse(500), PATH)) { testKit =>
      import testKit._

      whenReady(makeHttpCall(), timeout(Span(2, Seconds))) { s =>
        assert(s.entity.asString(HttpCharsets.`UTF-8`) === DelayedResponse.DEFAULT_RESPONSE)
      }

      expectMsgPF(3 seconds, "Expected normal log access event with response delayed properties") {
        case LogEvent(
        HttpRequest(HttpMethods.GET,URI, _, _, _),
        HttpResponse(StatusCodes.OK, _, _, _), time, LogAccess
        ) if time >= 500 && time <= 4000 => true
      }
    }
  }

  behavior of "An HTTP Server that handles a request with a 200 Chunked Response within the request timeout"

  it should "'Log Access' with a 200 response and an Access Time less than the request timeout" in {

    aTestLogAccessRoutingActor(
      requestTimeoutMillis = 4000,
      httpServiceActorFactory = RouteServiceActor.factory(SuccessRoutes.chunkedResponseRoute, PATH)) { testKit =>
      import testKit._

      whenReady(makeHttpCall(), timeout(Span(2, Seconds))) { s =>
        assert(s.entity.asString(HttpCharsets.`UTF-8`) === scala.io.Source.fromURL(testKit.getClass().getResource("/veryLargeFile.txt")).mkString)
      }

      expectMsgPF(3 seconds, "Expected normal log access event with response delayed properties") {
        case LogEvent(
        HttpRequest(HttpMethods.GET,URI, _, _, _),
        HttpResponse(StatusCodes.OK, _, _, _), time, LogAccess
        ) if time <= 4000 => true
      }
    }
  }

  behavior of "An HTTP Server with a route that throws an Exception within the request timeout"


  it should "'Log Access' with a 500 response and an Access Time less than the request timeout" in {
    aTestLogAccessRoutingActor(
      requestTimeoutMillis = 4000,
      httpServiceActorFactory = RouteServiceActor.factory(FailureRoutes.exceptionRoute, PATH)) { testKit =>
      import testKit._


      whenReady(makeHttpCall(), timeout(Span(2, Seconds))) { s =>
        assert(s.status.intValue == 500)
      }

      expectMsgPF(3 seconds, "Expected normal log access event with error response properties") {
        case LogEvent(
        HttpRequest(HttpMethods.GET,URI, _, _, _),
        HttpResponse(StatusCodes.InternalServerError, _, _, _), time, LogAccess
        ) if time <= 4000 => true
      }
    }
  }

  behavior of "An HTTP Server with a route that completes with a failure within the request timeout"

  it should "'Log Access' with a 500 response and an Access Time less than the request timeout" in {
    aTestLogAccessRoutingActor(
      requestTimeoutMillis = 4000,
      httpServiceActorFactory = RouteServiceActor.factory(FailureRoutes.failureRoute, PATH)) { testKit =>
      import testKit._


      whenReady(makeHttpCall(), timeout(Span(2, Seconds))) { s =>
        assert(s.status.intValue == 500)
      }

      expectMsgPF(3 seconds, "Expected normal log access event with error response properties") {
        case LogEvent(
        HttpRequest(HttpMethods.GET,URI, _, _, _),
        HttpResponse(StatusCodes.InternalServerError, _, _, _), time, LogAccess
        ) if time <= 4000 => true
      }
    }
  }

  behavior of "An HTTP Server that handles a request with a 200 response outside of the request timeout"

  it should "'Log Access' with a 500 response and an Access Time equal to the configured Request Timeout time " +
    "and then not log the response completed outside of the timeout" in {
    aTestLogAccessRoutingActor(
      requestTimeoutMillis = 50,
      httpServiceActorFactory = DelayedResponseServiceActor.factory(DelayedResponse(500), PATH)) { testKit =>
      import testKit._

      whenReady(makeHttpCall(), timeout(Span(2, Seconds))) { s =>
        assert(s.status.intValue == 500)
      }

      expectMsgPF(3 seconds, "Expected log access event with timeout properties") {
        case LogEvent(
        HttpRequest(HttpMethods.GET,URI, _, _, _),
        HttpResponse(StatusCodes.InternalServerError, _, _, _), 50, LogAccess
        ) => true
      }
    }
  }

  behavior of "An HTTP Server that doesn't complete within 100 times the request timeout to a TXT request"

  it should "'Log Access' with a 500 response and an Access Time equal to the configured Request Timeout time " +
    "and then not log the response completed outside of the timeout" in {
    aTestLogAccessRoutingActor(
      requestTimeoutMillis = 10,
      httpServiceActorFactory = DelayedResponseServiceActor.factory(DelayedResponse(2000), PATH)) { testKit =>
      import testKit._


      whenReady(makeHttpCall(), timeout(Span(2, Seconds))) { s =>
        assert(s.status.intValue == 500)
      }

      expectMsgPF(3 seconds, "Expected normal log access access event with with timeout properties") {
        case LogEvent(
        HttpRequest(HttpMethods.GET,URI, _, _, _),
        HttpResponse(StatusCodes.InternalServerError, _, _, _), 10, LogAccess
        ) => true
      }
    }
  }

  behavior of "An HTTP Server that doesn't complete within 100 times the request timeout to a JSON request"

  it should "'Log Access' with a 500 response and an Access Time equal to the configured Request Timeout time" in {

    aTestLogAccessRoutingActor(
      requestTimeoutMillis = 10,
      httpServiceActorFactory = DelayedResponseServiceActor.factory(DelayedResponse(2000), PATH)) { testKit =>
      import testKit._


      whenReady(makeHttpCall(MediaTypes.`application/json`), timeout(Span(2, Seconds))) { s =>
        assert(s.status.intValue == 500)
        println(s.entity.toOption.get.contentType)
      }

      expectMsgPF(3 seconds, "Expected normal log access access event with timeout properties") {
        case LogEvent(
        HttpRequest(HttpMethods.GET,URI, _, _, _),
        HttpResponse(StatusCodes.InternalServerError, _, _, _), 10, LogAccess
        ) => true
      }
    }
  }


  behavior of "An HTTP Server that handles a JSON request with a TXT response within the request timeout"

  it should "'Log Access' for a request with an unacceptable Accept header by making a 406 response with an Access Time less than the request timeout" in {
    aTestLogAccessRoutingActor(
      requestTimeoutMillis = 4000,
      httpServiceActorFactory = DelayedResponseServiceActor.factory(DelayedResponse(50), PATH)) { testKit =>
      import testKit._

      whenReady(makeHttpCall(MediaTypes.`application/json`), timeout(Span(2, Seconds))) { s =>
        withClue(s"Unexpected response status code\nResponse Body Was: '${s.entity.asString}'\n") {
          assert(s.status.intValue == 406)
        }
      }

      expectMsgPF(3 seconds, "Expected normal log access event with with Not Acceptable properties") {
        case LogEvent(
        HttpRequest(HttpMethods.GET,URI, _, _, _),
        HttpResponse(StatusCodes.NotAcceptable, _, _, _), _, LogAccess
        ) => true
      }

    }
  }


  implicit val TIMEOUT: Timeout = 3.second
  val PORT = 8084
  val HOST = s"http://localhost:$PORT"
  val PATH = "test"
  val URI:Uri = Uri(s"$HOST/$PATH")


  def CONFIG(requestTimeout:String = "1 s") =
    s"""
      |spray.can {
      |  server {
      |    request-timeout = infinite # We completely disable Spray timeout handling so that we can manage it
      |    idle-timeout = 10 s
      |    registration-timeout = 100 s
      |    routils-request-timeout = $requestTimeout
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
                                  httpServiceActorFactory: ActorRef => Props
                                  )
                                (callback: TestKit => Unit) {
    val config = ConfigFactory.parseString(CONFIG(s"$requestTimeoutMillis ms"))
    implicit val system = ActorSystem("test-system", config)
    val testKit = new TestKit(system)

    try {
      val serviceActor = system.actorOf(httpServiceActorFactory(testKit.testActor))

      val sprayServerStartResult = IO(Http).ask(Http.Bind(serviceActor, "localhost", PORT)).flatMap {
        case b: Http.Bound ⇒ Future.successful(b)
        case Tcp.CommandFailed(b: Http.Bind) ⇒
          Future.failed(new RuntimeException(
            "Binding failed. Switch on DEBUG-level logging for `akka.io.TcpListener` to log the cause."))
      }(system.dispatcher)

      assert(sprayServerStartResult.isReadyWithin(3 second))
      callback(testKit)
      Then("No more access log messages should be received by the testkit testActor")
      testKit.expectNoMsg(3 seconds)
    } finally {
      system.shutdown()
    }
  }

  def makeHttpCall(mediaType:MediaType = MediaTypes.`text/plain`)(implicit system: ActorSystem):Future[HttpResponse] = {
    import system.dispatcher
    val pipeline: HttpRequest => Future[HttpResponse] =
      addHeader(`Accept`(mediaType)) ~> sendReceive

    pipeline(Get(Uri(s"$HOST/$PATH")))
  }



}
