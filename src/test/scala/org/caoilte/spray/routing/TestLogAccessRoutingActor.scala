package org.caoilte.spray.routing

import akka.testkit.TestKit
import org.caoilte.spray.routing.TestAccessLogger.TestAccessLogger
import spray.http.{ContentTypes, HttpEntity, HttpRequest, HttpResponse}
import spray.routing._
import akka.actor.{Actor, ActorRef, ActorSystem, Props}

import scala.concurrent._
import akka.pattern.ask
import akka.util.Timeout
import java.util.concurrent.TimeUnit

object TestAccessLogger {
  sealed trait LogType
  case object LogAccess extends LogType
  case object AccessAlreadyLogged extends LogType

  case class LogEvent(request: HttpRequest, response: HttpResponse, time: Long, logAccessType: LogType)

  class TestAccessLogger(listener:ActorRef) extends AccessLogger {
    override def logAccess(request: HttpRequest, response: HttpResponse, time: Long) = {
      listener ! LogEvent(request, response, time, LogAccess)
    }

    override def accessAlreadyLogged(request: HttpRequest, response: HttpResponse, time: Long) = {
      listener ! LogEvent(request, response, time, AccessAlreadyLogged)
    }
  }
}


case object DelayedResponse {
  val DEFAULT_RESPONSE = "response"
}

case class DelayedResponse(thinkingMillis: Long, responseMessage:String = DelayedResponse.DEFAULT_RESPONSE)

object DelayedResponseServiceActor {
  def factory(delayedResponse: DelayedResponse, path:String): (ActorRef => Props) = actorRef => {
    apply(new TestAccessLogger(actorRef), delayedResponse, path)
  }
  def apply(accessLogger: AccessLogger, delayedResponse: DelayedResponse, path:String):Props = {
    Props(new DelayedResponseServiceActor(accessLogger, delayedResponse, path))
  }
}

class DelayedResponseServiceActor(val accessLogger: AccessLogger, delayedResponse: DelayedResponse, path:String)
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
    testAc = context.actorOf(Props(new DelayedResponseActor(delayedResponse)), "delayed-response-test-actor")
    super.preStart
  }

  val routes:Route = {
    path(path) {
      get {
        implicit val TIMEOUT: Timeout = Timeout(delayedResponse.thinkingMillis * 2, TimeUnit.MILLISECONDS)
        complete((testAc ? RequestForDelayedResponse).mapTo[HttpEntity])
      }
    }
  }

  override def receive = runRoute(routes)
}

trait RouteCreator {
  def apply(system:ActorSystem):Route
}

object RouteServiceActor {
  def factory(route: Route, path:String): (ActorRef => Props) = actorRef => {
    apply(new TestAccessLogger(actorRef), route, path)
  }
  def factory(routeCreator: RouteCreator, path:String): (ActorRef => Props) = actorRef => {
    apply(new TestAccessLogger(actorRef), routeCreator, path)
  }
  def apply(accessLogger: AccessLogger, route: Route, path:String):Props = {
    val routeCreator:RouteCreator = new RouteCreator {
      override def apply(system: ActorSystem): Route = route
    }
    Props(new RouteServiceActor(accessLogger, routeCreator, path))
  }
  def apply(accessLogger: AccessLogger, routeCreator: RouteCreator, path:String):Props = {
    Props(new RouteServiceActor(accessLogger, routeCreator, path))
  }
}

class RouteServiceActor(val accessLogger: AccessLogger, routeCreator: RouteCreator, path:String)
  extends HttpServiceActor with LogAccessRoutingActor {

  val routes:Route = {
    path(path) {
      get {
        routeCreator(context.system)
      }
    }
  }

  override def receive = runRoute(routes)
}