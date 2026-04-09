package app

// Error handling in keg is also done through annotated methods. These methods
// will be called when the corresponding error occurs, and can be used to return
// custom error messages or pages.

// Annotating a method with `@keg.notFound` will cause it to be called whenever
// a request is made to a path that doesn't match any of the defined endpoints.
// You can access the request and other parameters just like with regular
// endpoint annotations.
//snippet:start
@keg.notFound()
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

// Similarly, there's a `@keg.methodNotAllowed` annotation for handling requests
// that match a path but not the method of any defined endpoint.
//snippet:start
@keg.methodNotAllowed()
def methodNotAllowed(req: keg.Request) =
  keg.Response(
    s"custom method not allowed: ${req.path}\n",
    405
  )

@keg.get("/foo")
def foo() = "bar"
//snippet:end
/* usage snippet
$ curl "http://localhost:8080/foo" -X POST
custom method not allowed: /foo
*/

// Finally, there's `@keg.error()` for handling actual errors that occur while
// processing a request.
//
// This annotation is a bit special, in that it does not accept arbitrary
// arguments, but may only take arguments of the following types:
// - `keg.Request` (the request that caused the error)
// - `keg.Endpoint` (the endpoint that generated the error)
// - `keg.EndpointResult.Failure` (the type of error that occurred)
//
// The `keg.EndpointResult.Failure` has three subtypes:
// - `keg.EndpointResult.ParamAbort(param, code, message)` Something went wrong
//   in the `FromRequest` instance for one of the endpoint's parameters. The
//   failure includes a suggested HTTP status code and error message.
// - `keg.EndpointResult.MethodAbort(code, message)` The endpoint rejected the
//   request with a specific status code and message.
// - `keg.EndpointResult.Exception(ex)` An exception was thrown while processing
//   the request
//snippet:start
// @keg.error()
def errorHandler(
  req: keg.Request,
  ep: keg.Endpoint,
  failure: keg.EndpointResult.Failure
) =

  keg.Response(
    s"custom error handler: ${failure}\n",
    500
  )

val routes = keg.routes()

@main
def main() =
  val server = keg.Server(routes.endpoints)
  server.start()
