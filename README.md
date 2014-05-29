# Spray Routils

## Access Logging

In order to accurately log all access to a Spray Server you need to be very careful about how you handle requests that timeouts.

The ```LogAccessRouting``` Spray Routing trait can take care of that complexity for you.
 
Included is a demo that you can run from within sbt using ```sbt-revolver```

Below is an example snippet showing how you might mix the trait into your own project

```scala
import org.caoilte.spray.routing.LogAccessRouting
import org.caoilte.spray.routing.AccessLogger

class YourAccessLogger extends AccessLogger {
  override def logAccess(request: HttpRequest, response: HttpResponse, time: Long) = ???
    
  override def accessAlreadyLogged(request: HttpRequest, response: HttpResponse, time: Long) = ???
}

class YourHttpService extends HttpServiceActor with LogAccessRouting {
  val accessLogger:AccessLogger = new YourAccessLogger
    
  val routes:Route = accessLog {
    ???
  }
}
```
