package app

// Keg itself does not implement the actual handling of network requests.
// Instead, it factors those out to so-called *transports*. A transport is the
// subsystem which handles the low-level reading and writing of network streams
// which make up the HTTP protocol.
//
// Keg is structured in such a way that the transport for a keg server can be
// swapped out. Or, put differently, you can essentially think of keg as being a
// large wrapper around a transport, which does that actual request handling.
//
// Out-of-the-box, keg comes with two transports:
//
// 1. `keg.transport.UndertowTransport`, based on the Undertow webserver. This
//    is a very stable and feature-complete webserver for Java. Unfortunately
//    however, it does not work with ScalaNative and also has issues when built
//    as a GraalVM native image.
//
//    This transport is the **default when using keg on the JVM**.
//
// 1. `keg.transport.uhttp.UhttpTransport`, based on an experimental pure-scala
//    HTTP server written from scratch. This webserver is minimal, doesn't
//    implement advanced features such as websockets, and is probably not
//    spec-compliant (web clients should be tolerant). It is however available
//    under scala native!
//
//    This transport is the **default when using keg with scala native**.
//
// Here is a more detailed comparison of both transports:
//
// | feature    | UndertowTransport | UhttpTransport |
// |------------|-------------------|----------------|
// | HTTP/1.1   | yes               | yes            |
// | HTTP/2.0   | yes               | no             |
// | HTTPS      | yes               | only on JVM    |
// | websockets | yes               | no             |
// | JVM        | yes               | yes            |
// | native     | no                | yes            |
//
// You can switch out the transport simply by passing it as an argument to
// `keg.Server`. For example, here we explicitly tell keg to use the Uhttp
// server, regardless of platform:
//snippet:start
@keg.get("/hello")
def hello() =
  "hello world\n"

val routes = keg.routes()

val server = keg.Server(
  routes,
  transport = keg.transport.uhttp.UhttpTransport
)
//snippet:end
/* usage snippet
$ curl http://localhost:8080/hello
hello world
*/

// You can also implement your own transport if you like. A transport is a
// `keg.transport.Transport`, whose `init` method receives the listeners to
// bind to and the request handler, and returns a `BackendServer` which keg
// starts and stops. Take a look at the
// [Transport](keg/src/keg/transport/Transport.scala) trait to see what you
// need.

@main
def main() =
  server.start()
