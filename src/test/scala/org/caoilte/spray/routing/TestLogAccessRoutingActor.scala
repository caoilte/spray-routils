package org.caoilte.spray.routing

import spray.http.{HttpEntity, ContentTypes}
import spray.routing._
import akka.actor.{Props, ActorRef, Actor}
import scala.concurrent._
import akka.pattern.ask
import akka.util.Timeout
import java.util.concurrent.TimeUnit

case object DelayedResponse {
  val DEFAULT_RESPONSE = "response"
}

case class DelayedResponse(thinkingMillis: Long, responseMessage:String = DelayedResponse.DEFAULT_RESPONSE)

class TestLogAccessRoutingActor(val accessLogger: AccessLogger,
                                routeOrDelayedResponse:Either[Route,DelayedResponse], path:String)
  extends HttpServiceActor with LogAccessRoutingActor {
  case object RequestForDelayedResponse


  class DelayedResponseActor(response:DelayedResponse) extends Actor {
    import response._
    def receive: Receive = {
      case RequestForDelayedResponse => {
        blocking {
          Thread.sleep(thinkingMillis)
        }
        sender ! HttpEntity(ContentTypes.`text/plain`, responseMessage)
      }
    }
  }

  var testAc:ActorRef = _

  implicit def executionContext = actorRefFactory.dispatcher

  override def preStart {
    routeOrDelayedResponse.right.map( response => {
      testAc = context.actorOf(Props(new DelayedResponseActor(response)), "delayed-response-test-actor")
    })
    super.preStart
  }

  val routes:Route = {
    path(path) {
      get {
        routeOrDelayedResponse match {
          case Left(route) => route
          case Right(DelayedResponse(thinkingMillis, _)) => {
            implicit val TIMEOUT: Timeout = Timeout(thinkingMillis * 2, TimeUnit.MILLISECONDS)
            complete((testAc ? RequestForDelayedResponse).mapTo[HttpEntity])
          }
        }
      }
    }
  }

  override def receive = runRoute(routes)
}
