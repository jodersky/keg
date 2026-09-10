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
// for quickly writing a simple server or advanced use cases, such as building
// endpoints programmatically.

//snippet:start
val routes = Seq(
  keg.Endpoint(
    path = "/",
    methods = Seq("GET"),
    invoke = _ =>
      keg.Response("Hello, World!", 200)
  ),
  keg.Endpoint(
    path = "/echo",
    methods = Seq("POST"),
    invoke = req =>
      val body = new String(req.body().readAllBytes(), "UTF-8")
      keg.Response(s"Echo: $body", 200)
  ),
  keg.Endpoint(
    path = "/fail",
    invoke = _ =>
      keg.abort(418, "I'm a teapot")
  ),
  keg.Endpoint(
    path = "/*",
    invoke = (req) =>
      keg.Response("Custom 404 Not Found", 404)
  ),
  keg.Recovery(
    invoke = (req, err, ep) =>
      keg.Response(s"Custom error page: ${err}", 500)
  )
)

val server = keg.Server(routes)
//snippet:end

@main
def main() =
  server.start()
