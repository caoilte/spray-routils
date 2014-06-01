# Spray Routils

## Access Logging

In order to accurately log all access to a Spray Server you need to,
+ Time and log the completion of your routes ([Mathias tells me how][spray-time-custom-directive])
+ Handle Timedout requests ([see Spray Docs][spray-timeout-handling]) and then log them
+ Ensure Routes that timeout don't also log on completion (ie log access twice - 
[see this Google Groups Discussion for background][spray-timeout-discussion])

The ```LogAccessRouting``` Spray Routing trait uses custom directives to manage a ```Map[HttpRequest,Count]``` 
and ensure that HttpRequests are only logged once - either by your routes, or by the timeout route.

Included is a demo that you can use to test this behaviour. It runs from within sbt using ```sbt-revolver``` and uses
a custom logger based on a suffix of the [Common Log Format][common-log-format]. (There are also automated integration 
tests of the same behaviour.) 

### Demo a Server that responds within the timeout

```reStart 1000 500``` will start a server with a ```request-timeout``` of 1000ms and a sleep before responding of 
500ms. Going to ```http://localhost:8085/hello``` will log,

```
spray-routils "GET /hello HTTP/1.1" 200 12 580
```


### Demo a Server that times out before responding

```reStart 500 1000``` will start a server with a ```request-timeout``` of 500ms and a sleep before responding of 
1000ms. Going to ```http://localhost:8085/hello``` will log,

```
spray-routils "GET /hello HTTP/1.1" 500 69 500
spray-routils THIS WOULD HAVE BEEN RESPONSE IF TIMEOUT HADN'T OCCURRED
spray-routils "GET /hello HTTP/1.1" 200 12 1048"
```

### Including Access Logging in your own Spray Routes

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

[spray-time-custom-directive]: https://groups.google.com/d/msg/spray-user/V5q6kaXfcHY/ioUzYbW8XvoJ "A Spray Custom Directive for timing a Route"
[spray-timeout-handling]: http://spray.io/documentation/1.2.1/spray-routing/key-concepts/timeout-handling/ "Spray Timeout Handling"
[spray-timeout-discussion]: https://groups.google.com/d/msg/spray-user/as_3g7Yl_kI/pJmzB-DXOF0J "Discussion about handling Spray Timeouts"
[common-log-format]: http://en.wikipedia.org/wiki/Common_Log_Format "Common Log Format"
