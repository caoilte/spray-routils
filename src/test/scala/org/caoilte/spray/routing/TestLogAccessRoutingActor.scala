package org.caoilte.spray.routing

import spray.routing._
import akka.actor.{Props, ActorRef, Actor}
import scala.concurrent._
import akka.pattern.ask
import akka.util.Timeout

class TestLogAccessRoutingActor(val accessLogger: AccessLogger, val thinkingMillis: Long, response:String, path:String) extends HttpServiceActor with LogAccessRouting {
  case object RequestForDelayedResponse


  implicit val TIMEOUT: Timeout = Timeout(thinkingMillis*2)

  class DelayedResponseActor extends Actor {
    def receive: Receive = {
      case RequestForDelayedResponse => {
        blocking {
          Thread.sleep(thinkingMillis)
        }
        sender ! response
      }
    }
  }

  var testAc:ActorRef = _

  implicit def executionContext = actorRefFactory.dispatcher

  override def preStart {
    testAc = context.actorOf(Props(new DelayedResponseActor()), "delayed-response-test-actor")
    super.preStart
  }

  val routes:Route = {
    accessLog {
      path(path) {
        get {
          complete((testAc ? RequestForDelayedResponse).mapTo[String])
        }
      }
    }
  }

  override def receive = runRoute(routes)
}
