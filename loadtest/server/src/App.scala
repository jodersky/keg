package loadtest

// Server under test for macro benchmarks. Each endpoint targets a different
// code path, so that results can be attributed. Everything that is not the
// subject of a benchmark (logging, debug mode, interceptors) is off by default.
//
// Run with --help for options. Once the server accepts connections, a single
// line starting with "listening on" is printed to stdout.

// Fixed-size body, measures raw transport and framework overhead.
@keg.get("/plaintext")
def plaintext() = "Hello, World!"

case class Message(message: String) derives upickle.default.ReadWriter

// Idiomatic JSON response. Note that keg streams JSON responses, so there is no
// Content-Length header.
@keg.get("/json")
def json() = Message("Hello, World!")

// Same payload as /json, but serialized upfront and sent with a Content-Length
// header. Comparing the two isolates the effect of streamed responses (e.g. on
// connection reuse).
@keg.get("/json/buffered")
def jsonBuffered() =
  val bytes = upickle.default.writeToByteArray(Message("Hello, World!"))
  keg.Response[keg.Data](
    new keg.Data:
      def write(out: java.io.OutputStream) = out.write(bytes)
      def headers = Seq("Content-Type" -> "application/json; charset=UTF-8")
      def contentLength = Some(bytes.length.toLong)
  )

// JSON parsing and serialization.
@keg.post("/json")
def jsonRoundtrip(message: Message) = message

// Reads the full request body and writes it back.
//
// The body is read via the request rather than an `Array[Byte]` parameter: the
// latter currently resolves to the JSON instance (upickle has a Reader for byte
// arrays), which parses the body as a JSON array.
@keg.post("/echo")
def echo(req: keg.Request) = req.bytes()

// Router trie lookup with dynamic segments and parameter parsing.
@keg.get("/params/:id/:name")
def params(id: Int, name: String) = s"$id $name"

val routes = keg.routes()

// Does nothing but forward, to measure the per-interceptor cost.
object PassthroughInterceptor extends keg.Interceptor:
  def intercept(
    request: keg.Request,
    endpoint: keg.Endpoint,
    next: keg.Request => keg.RawResponse
  ): keg.RawResponse = next(request)

@clip.command(name = "loadtest-server", help = "Server under test for load tests.")
def app(
  @clip.arg("--transport", help = s"Transport to use: ${transports.keys.mkString(", ")}")
  transport: String = defaultTransport,
  @clip.arg("--host", help = "Address to listen on")
  host: String = "::",
  @clip.arg("--port", help = "Port to listen on, 0 picks a free one")
  port: Int = 8080,
  @clip.arg("--interceptors", help = "Number of no-op interceptors to install")
  interceptors: Int = 0,
  @clip.arg("--log-level", help = "Minimum level of log messages (trace, debug, info, warn, error)")
  logLevel: String = "warn"
): Unit =
  val selectedTransport = transports.getOrElse(
    transport,
    clip.abort(s"unknown transport '$transport', available: ${transports.keys.mkString(", ")}", 2)
  )

  scribe.Logger.root
    .clearHandlers()
    .withHandler(minimumLevel = Some(scribe.Level(logLevel)))
    .replace()

  val server = keg.Server(
    routes,
    listeners = Seq(keg.Listener.Http(host, port)),
    // set explicitly, so that a DEBUG variable in the environment can't
    // silently enable it
    debugMode = false,
    // fixed secret, avoids a warning at startup
    secrets = Seq("bG9hZHRlc3QtbG9hZHRlc3QtbG9hZHRlc3QtbG9hZHRlc3Q="),
    interceptors = Seq.fill(interceptors)(PassthroughInterceptor),
    transport = selectedTransport
  )
  server.start()
  println(s"listening on $transport port ${server.serverPort()}")
  System.out.flush()
  server.awaitTermination()

def main(args: Array[String]): Unit = clip.main(this, args)
