package org.caoilte.spray.routing

import com.typesafe.config.Config
import spray.routing._
import akka.actor._
import scala.concurrent.duration._
import spray.util.{SettingsCompanion, LoggingContext}
import spray.http._
import RequestAccessLogger._
import scala.concurrent.duration._
import spray.util._


trait AccessLogger {
  def logAccess(request:HttpRequest, response:HttpResponse, time:Long):Unit
  def accessAlreadyLogged(request:HttpRequest, response:HttpResponse, time:Long):Unit
}

object RequestAccessLogger {

  def defaultTimeStampCalculator():(Unit => Long) = {
    val startTime = System.currentTimeMillis
    Unit => System.currentTimeMillis() - startTime
  }

  def props(ctx: RequestContext, accessLogger: AccessLogger,
            timeStampCalculator:(Unit => Long), requestTimeout:FiniteDuration):Props = {
    Props(new RequestAccessLogger(ctx, accessLogger, timeStampCalculator, requestTimeout))
  }

  case object RequestLoggingTimeout

}

class RequestAccessLogger(ctx: RequestContext, accessLogger: AccessLogger,
                          timeStampCalculator:(Unit => Long), requestTimeout:FiniteDuration) extends Actor {
  import ctx._

  import context.dispatcher
  val cancellable:Cancellable = context.system.scheduler.scheduleOnce(requestTimeout, self, RequestLoggingTimeout)

  def receive = {
    case RequestLoggingTimeout => {
      val errorResponse = HttpResponse(
        StatusCodes.InternalServerError,
        HttpEntity(s"The RequestAccessLogger timed out waiting for the request to complete after " +
          s"'${requestTimeout}'."))

      accessLogger.logAccess(request, errorResponse, requestTimeout.toMillis)
      ctx.complete(errorResponse)
      context.stop(self)
    }
    case response:HttpResponse => {
      cancellable.cancel()
      accessLogger.logAccess(request, response, timeStampCalculator(Unit))
      forwardMsgAndStop(response)
    }
    case other => {
      responder forward other
    }
  }

  def forwardMsgAndStop(msg:Any) {
    responder forward msg
    context.stop(self)
  }
}

object SingleAccessLogger {
  case class AccessLogRequest(request:HttpRequest, response:HttpResponse, time:Long)
  case object LogState
}

trait LogAccessRouting extends HttpService {
  val requestTimeout:FiniteDuration
  val accessLogger:AccessLogger

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
        RequestAccessLogger.props(ctx, accessLogger, timeStampCalculator, requestTimeout)
      )
      ctx.withResponder(loggingInterceptor)
    }

    {
      case request: HttpRequest ⇒ {
        val ctx = attachLoggingInterceptorToRequest(request)
        super.runRoute(route)(eh, rh, ac, rs, log)(ctx)
      }
      case ctx: RequestContext ⇒ {
        super.runRoute(route)(eh, rh, ac, rs, log)(attachLoggingInterceptorToCtx(ctx))
      }
      case other => super.runRoute(route)(eh, rh, ac, rs, log)(other)
    }
  }
}

case class LogAccessRoutingSettings(requestTimeout: FiniteDuration)

object LogAccessRoutingSettings extends SettingsCompanion[LogAccessRoutingSettings]("spray.can.server") {
  override def fromSubConfig(c: Config): LogAccessRoutingSettings = {

    val sprayDuration:Duration = c getDuration "request-timeout"
    require(!sprayDuration.isFinite(), "LogAccessRouting requires spray.can.server.request-timeout to be set as 'infinite'")

    val duration:Duration = c getDuration "routils-request-timeout"
    require(duration.isFinite(), "LogAccessRouting requires spray.can.server.routils-request-timeout to be set with a finite duration")
    apply(duration.asInstanceOf[FiniteDuration])
  }
}

trait LogAccessRoutingActor extends HttpServiceActor with LogAccessRouting {

  val accessLogger:AccessLogger
  val requestTimeout:FiniteDuration = LogAccessRoutingSettings(context.system).requestTimeout

  var singleAccessLoggerRef:ActorRef = _
}