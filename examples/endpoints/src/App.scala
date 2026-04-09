package app

// Keg is structured as a layered framework. At the very top, you write your
// application code by annotating methods with `@keg.get`, `@keg.post`, etc.
// This is the most convenient way to write your application, but isn't what's
// actually happening under the hood. Keg actually uses a different
// representation for your endpoints. The annotations are just a thin layer that
// generates the underlying data structures for you.
//
// If you want more control, you can skip the annotations and write the
// underlying data structures directly. This is more verbose, but can be useful
// for advanced use cases, such as building endpoints programmatically.

//snippet:start
val endpoints = Seq(
  keg.Endpoint(
    selector = keg.EndpointSelector.Simple("/", Seq("GET")),
    invoke = _ =>
      keg.model.EndpointResult.Success(
        keg.Response("Hello, World!", 200)
      )
  ),
  keg.Endpoint(
    selector = keg.EndpointSelector.Simple("/echo", Seq("POST")),
    invoke = req =>
      val body = new String(req.body.readAllBytes(), "UTF-8")
      keg.model.EndpointResult.Success(
        keg.Response(s"Echo: $body", 200)
      )
  ),
  keg.Endpoint(
    selector = keg.EndpointSelector.Simple("/fail", Seq("GET")),
    invoke = _ =>
      keg.model.EndpointResult.Failure.MethodAbort(418, "I'm a teapot")
  ),
  keg.Endpoint(
    selector = keg.EndpointSelector.NotFound,
    invoke = (req) =>
      keg.model.EndpointResult.Success(
        keg.Response("Custom 404 Not Found", 404)
      )
  ),
  keg.model.Recovery(
    invoke = (req, ep, err) =>
      keg.Response(s"Custom error page: ${err}", 500)
  )
)

val server = keg.Server(endpoints)
//snippet:end

@main
def main() =
  server.start()
