package org.caoilte.spray.routing

import spray.routing._
import spray.can.server.ServerSettings
import akka.actor._
import spray.http.StatusCodes._
import spray.http.HttpRequest
import spray.http.HttpResponse
import org.caoilte.spray.routing.SingleAccessLogger.AccessLogRequest

trait AccessLogger {
  def logAccess(request:HttpRequest, response:HttpResponse, time:Long):Unit
  def accessAlreadyLogged(request:HttpRequest, response:HttpResponse, time:Long):Unit
}

object SingleAccessLogger {
  case class AccessLogRequest(request:HttpRequest, response:HttpResponse, time:Long)
}

class SingleAccessLogger(accessLogger: AccessLogger) extends Actor {
  import SingleAccessLogger._

  def receive = handleAccessLogRequest(Map().withDefaultValue(0))

  def handleAccessLogRequest(inProgressRequests: Map[HttpRequest, Int]): Receive = {
    case request:HttpRequest => {
      context.become(handleAccessLogRequest(inProgressRequests.updated(request, inProgressRequests(request)+1)))
    }
    case AccessLogRequest(request, response, time) => {
      inProgressRequests(request) match {
        case 0 => accessLogger.accessAlreadyLogged(request, response, time)
        case 1 => {
          accessLogger.logAccess(request, response, time)
          context.become(handleAccessLogRequest(inProgressRequests - request))
        }
        case _ => {
          accessLogger.logAccess(request, response, time)
          context.become(handleAccessLogRequest(inProgressRequests.updated(request, inProgressRequests(request)-1)))
        }
      }
    }
  }
}

trait LogAccessRouting extends HttpServiceActor {

  val accessLogger:AccessLogger
  private val requestTimeout = ServerSettings(context.system).requestTimeout

  var singleAccessLoggerRef:ActorRef = _

  override def preStart() {
    singleAccessLoggerRef = context.system.actorOf(Props(new SingleAccessLogger(accessLogger)), "single-access-logger")
    super.preStart
  }

  def accessLogTimeout: Directive0 = {
    mapRequestContext { ctx =>
      ctx.withHttpResponseMapped { response =>
        singleAccessLoggerRef ! AccessLogRequest(ctx.request, response, requestTimeout.toMillis)
        response
      }
    }
  }

  def accessLog: Directive0 =
    mapRequestContext { ctx =>
      singleAccessLoggerRef ! ctx.request
      val timeStamp = System.currentTimeMillis
      ctx.withHttpResponseMapped { response =>
        singleAccessLoggerRef ! AccessLogRequest(ctx.request, response, System.currentTimeMillis - timeStamp)
        response
      }
    }

  override def timeoutRoute: Route = {
    accessLogTimeout {
      complete(
        InternalServerError,
        "The server was not able to produce a timely response to your request.")
    }
  }

}