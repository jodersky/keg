package app

// Error handling is also done with annotations. A method annotated with
// `@keg.error()` will be called whenever an exception is thrown while
// processing a request. Such as method is known as the *recovery*.
//
// A recovery may only take arguments of the following types:
// - `keg.Request`: the request that caused the error
// - `Option[keg.Endpoint]`: the endpoint that generated the error, if the error
//   originated in an endpoint
// - `Throwable`: the exception that was thrown
//
// By convention, `keg.AbortException` is thrown by endpoints when the error
// represents something that has a status code. The message is intended for
// debugging, and shouldn't be shown to the user.
//
// A recovery itself should not throw! Any exceptions from a recovery will
// bubble up to the HTTP transport, which may simply terminate the HTTP
// connection or produce non-overridable error messages of its own.
//
//snippet:start
@keg.error()
def errorHandler(
  req: keg.Request,
  ep: Option[keg.Endpoint],
  ex: Throwable
): keg.RawResponse =
  // In this example we include the messages in the response. In a real app,
  // you'd usually hide the message, or use an application-specific exception
  // type when you actually want to include the message in the response body.
  ex match
    case keg.AbortException(code, message) =>
      keg.Response(
        s"the endpoint ${ep.map(_.path).getOrElse("none")} rejected the request: ${message}\n",
        code
      )
    case _ =>
      keg.Response(
        s"an exception was thrown while processing the request: ${ex.getMessage}\n",
        500
      )

@keg.get("/fail")
def fail(): keg.Response[keg.Data] =
  throw new RuntimeException("something went wrong")

@keg.get("/reject")
def reject(): keg.Response[keg.Data] =
  keg.abort(400, "request rejected by endpoint")
//snippet:end
/* usage snippet
$ curl "http://localhost:8080/fail" -i
HTTP/1.1 500 Internal Server Error
...
an exception was thrown while processing the request: something went wrong
*/
/* usage snippet
$ curl "http://localhost:8080/reject" -i
HTTP/1.1 400 Bad Request
...
the endpoint /reject rejected the request: request rejected by endpoint
*/


// By default, a request to a non-existing endpoint will throw a 404 abort
// exception. While you *can* define a recovery which catches a 404, you could
// also have a fallback endpoint:
//snippedt:start
@keg.get("/*")
def notFound(req: keg.Request) =
  keg.Response(
    s"custom not found: ${req.path}\n",
    404
  )
//snippet:end
/* usage snippet
$ curl "http://localhost:8080/doesnotexist"
custom not found: /doesnotexist
*/
val routes = keg.routes()

@main
def main() =
  val server = keg.Server(routes)
  server.start()
