package app

// A keg application requires two things:
// 1. a set of routes, which are defined by annotating methods
// 2. a server, which is created from the routes and listens for incoming requests
//snippet:start
// define routes by annotating methods
@keg.get("/hello")
def hello() =
  s"hello, world!"

@keg.post("/echo")
def echo(req: keg.Request) =
  req.text()

// use the keg.routes() macro to collect all annotated methods into a set of
// routes
val routes: Seq[keg.Route] = keg.routes()

// finally, create a server from the routes
val server: keg.Server = keg.Server(routes)

@main
def main() =
  // start the server to listen for incoming requests
  server.start()

  // not necessary, but this lets you gracefully shut down the server,
  // preventing new requests from being accepted and waiting for existing
  // requests to finish before exiting the application
  sys.addShutdownHook:
    server.stop()
    server.awaitTermination()
//snippet:end

/* usage snippet
$ curl "http://localhost:8080/hello"
hello, world!
*/
/* usage snippet
$ curl "http://localhost:8080/echo" -d "hello"
hello
*/

/* this part of the example is only compiled, not run (we have plenty of other tests for this) */
val keyPath = java.nio.file.Paths.get("nonexistent.key")
val certPath = java.nio.file.Paths.get("nonexistent.crt")

// By default the server listens on port 8080. You can change this by setting
//the `listeners` parameter when creating the server.
//snippet:start
val serverWithConfig = keg.Server(
  routes,
  listeners = Seq(
    keg.Listener.Http("::", 8081),
    keg.Listener.Http("::1", 8082), // you can even listen on multiple ports!
    keg.Listener.Https("::", 8443, key = keyPath, cert = certPath) // see the TLS example for more info on HTTPS
  )
)
//snippet:end
