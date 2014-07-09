package org.caoilte.spray.routing

import spray.routing._
import spray.can.server.ServerSettings
import akka.actor._
import scala.concurrent.duration.{FiniteDuration, Duration}
import spray.util.LoggingContext
import spray.http._
import org.caoilte.spray.routing.SingleAccessLogger.AccessLogRequest
import RequestAccessLogger._

trait AccessLogger {
  def logAccess(request:HttpRequest, response:HttpResponse, time:Long):Unit
  def accessAlreadyLogged(request:HttpRequest, response:HttpResponse, time:Long):Unit
}

object RequestAccessLogger {

  def defaultTimeStampCalculator():(Unit => Long) = {
    val startTime = System.currentTimeMillis
    Unit => System.currentTimeMillis() - startTime
  }

  def props(ctx: RequestContext, singleAccessLogger:ActorRef,
            timeStampCalculator:(Unit => Long), requestTimeout:FiniteDuration):Props = {
    Props(new RequestAccessLogger(ctx, singleAccessLogger, timeStampCalculator, requestTimeout))
  }

  case object RequestLoggingTimeout

}

class RequestAccessLogger(ctx: RequestContext, singleAccessLogger:ActorRef,
                          timeStampCalculator:(Unit => Long), requestTimeout:FiniteDuration) extends Actor {
  import ctx._

  import context.dispatcher
  val cancellable:Cancellable = context.system.scheduler.scheduleOnce(requestTimeout * 100, self, RequestLoggingTimeout)

  def receive = {
    case RequestLoggingTimeout => {
      val errorResponse = HttpResponse(
        StatusCodes.InternalServerError,
        HttpEntity(s"The RequestAccessLogger timed out waiting for the request to complete after " +
          s"'${requestTimeout * 100}' which is 100 times the " +
          s"configured request timeout (and therefore when a timeout response was made)."))
      singleAccessLogger ! AccessLogRequest(request, errorResponse, timeStampCalculator(Unit))
      context.stop(self)
    }
    case response:HttpResponse => {
      cancellable.cancel()
      singleAccessLogger ! AccessLogRequest(request, response, timeStampCalculator(Unit))
      forwardMsgAndStop(response)
    }
    case other => {
      forwardMsgAndStop(other)
    }
  }

  def forwardMsgAndStop(msg:Any) {
    responder forward msg
    context.stop(self)
  }
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

trait LogAccessRouting extends HttpService {
  var singleAccessLoggerRef:ActorRef
  val requestTimeout:FiniteDuration
  val accessLogger:AccessLogger


  def accessLogTimeout: Directive0 = {
    mapRequestContext { ctx =>
      ctx.withHttpResponseMapped { response =>
        singleAccessLoggerRef ! AccessLogRequest(ctx.request, response, requestTimeout.toMillis)
        response
      }
    }
  }

  override def runRoute(route: Route)(implicit eh: ExceptionHandler, rh: RejectionHandler, ac: ActorContext,
                                      rs: RoutingSettings, log: LoggingContext): Actor.Receive = {

    def attachLoggingInterceptorToRequest(request: HttpRequest,
                                 timeStampCalculator: (Unit => Long) = defaultTimeStampCalculator()):RequestContext = {

      attachLoggingInterceptorToCtx(
        RequestContext(request, ac.sender(), request.uri.path).withDefaultSender(ac.self), timeStampCalculator
      )
    }
    def attachLoggingInterceptorToCtx(ctx:RequestContext,
                                 timeStampCalculator: (Unit => Long) = defaultTimeStampCalculator()):RequestContext = {

      val loggingInterceptor = ac.actorOf(
        RequestAccessLogger.props(ctx, singleAccessLoggerRef, timeStampCalculator, requestTimeout)
      )
      ctx.withResponder(loggingInterceptor)
    }

    {
      case Timedout(request: HttpRequest) ⇒ {
        val ctx = attachLoggingInterceptorToRequest(request, Unit => requestTimeout.toMillis)
        super.runRoute(timeoutRoute)(eh, rh, ac, rs, log)(ctx)
      }
      case request: HttpRequest ⇒ {
        val ctx = attachLoggingInterceptorToRequest(request)
        singleAccessLoggerRef ! request
        super.runRoute(route)(eh, rh, ac, rs, log)(ctx)
      }
      case ctx: RequestContext ⇒ {
        singleAccessLoggerRef ! ctx.request
        super.runRoute(route)(eh, rh, ac, rs, log)(attachLoggingInterceptorToCtx(ctx))
      }
      case other => super.runRoute(route)(eh, rh, ac, rs, log)(other)
    }
  }
}

trait LogAccessRoutingActor extends HttpServiceActor with LogAccessRouting {

  val accessLogger:AccessLogger
  val requestTimeout:FiniteDuration = {
    val configuredRequestTimeout:Duration = ServerSettings(context.system).requestTimeout
    require(configuredRequestTimeout.isFinite(), "LogAccessRouting cannot be configured if request timeouts are not finite")
    configuredRequestTimeout.asInstanceOf[FiniteDuration]
  }

  var singleAccessLoggerRef:ActorRef = _

  override def preStart() {
    singleAccessLoggerRef = context.system.actorOf(Props(new SingleAccessLogger(accessLogger)), "single-access-logger")
    super.preStart
  }
}