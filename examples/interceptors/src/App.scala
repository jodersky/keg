package app

// An *interceptor* is a component that sits between the server and your
// endpoint handlers. It is called for every request that matches an endpoint,
// and can:
// - perform side effects before or after calling the handler (e.g. logging)
// - modify the request before passing it to the handler
// - modify the response before returning it to the client
// - short-circuit the request and return a response directly (e.g. auth checks)
//
// Interceptors are a good fit for cross-cutting concerns that are *opaque* to
// the endpoint handlers — things they don't need to know about, such as
// logging, metrics or compression. For concerns that the handler *does* need to
// know about (e.g. the currently logged-in user), prefer a custom `FromRequest`
// instance instead.

// #### Logging
//
// The simplest kind of interceptor just runs side effects around the handler.
// Here we print a line before and after every request:
//snippet:start
object LoggingInterceptor extends keg.Interceptor:
  def intercept(
    request: keg.Request,
    endpoint: keg.Endpoint,
    next: keg.Request => keg.FinalResponse
  ): keg.FinalResponse =
    println(s"--> ${request.method} ${request.path}")
    val response = next(request)
    response match
      case r: keg.Response[?] =>
        println(s"<-- ${r.status}")
      case _ =>
    response
//snippet:end

// #### Request/response modification
//
// Interceptors can also transform the request before it reaches the handler, or
// wrap the response on the way back out. The following interceptor adds
// transparent gzip compression: it decompresses a gzip-encoded request body
// (if present) and compresses the response body when the client signals that it
// accepts gzip encoding.
//snippet:start
import java.util.zip.{GZIPInputStream, GZIPOutputStream}

object GzipInterceptor extends keg.Interceptor:
  def intercept(
    request: keg.Request,
    endpoint: keg.Endpoint,
    next: keg.Request => keg.FinalResponse
  ): keg.FinalResponse =
    // Decompress request body if Content-Encoding: gzip
    val req =
      if request.headers.get("content-encoding").exists(_.equalsIgnoreCase("gzip")) then
        request.copy(getBody = () => GZIPInputStream(request.getBody()))
      else
        request

    val response = next(req)

    // Compress response if client accepts gzip
    val clientAcceptsGzip = request.headers
      .getAll("accept-encoding")
      .flatMap(_.split(','))
      .map(_.trim.toLowerCase)
      .exists(_ == "gzip")

    if !clientAcceptsGzip then response
    else
      response match
        case resp: keg.Response[keg.model.Data] =>
          val originalData = resp.data
          val compressedData = new keg.model.Data:
            def write(out: java.io.OutputStream): Unit =
              val gz = GZIPOutputStream(out)
              originalData.write(gz)
              gz.finish()
            def headers: Seq[(String, String)] = originalData.headers
            def contentLength: Option[Long] = None
          resp.copy(
            data = compressedData,
            headers = resp.headers :+ ("Content-Encoding" -> "gzip")
          )
        case ws: keg.model.WsResponse => ws
//snippet:end

// #### Short-circuiting
//
// An interceptor can also return a response *without* calling `next`, which
// completely bypasses the handler. This is useful for things like access
// control. The example below rejects any request that doesn't carry a specific
// header:
//snippet:start
case class ApiKeyInterceptor(validKey: String) extends keg.Interceptor:
  def intercept(
    request: keg.Request,
    endpoint: keg.Endpoint,
    next: keg.Request => keg.FinalResponse
  ): keg.FinalResponse =
    request.headers.get("x-api-key") match
      case Some(key) if key == validKey =>
        next(request)
      case _ =>
        keg.Response("Unauthorized", 401)
//snippet:end

// #### Using interceptors
//
// Interceptors are passed to `keg.Server` via the `interceptors` parameter.
// They are applied in order, so the first interceptor in the list is the
// outermost one (it runs first on the way in and last on the way out).
//snippet:start
@keg.get("/hello")
def hello(req: keg.Request) =
  s"hello, ${req.headers.getOrElse("x-name", "world")}!\n"

val routes = keg.routes()

@main
def main() =
  val server = keg.Server(
    routes,
    interceptors = Seq(
      LoggingInterceptor,
      GzipInterceptor,
      ApiKeyInterceptor("secret")
    )
  )
  server.start()
//snippet:end
/* usage snippet
$ curl "http://localhost:8080/hello" -H "x-api-key: secret" -H "x-name: alice"
hello, alice!
*/
/* usage snippet
$ curl "http://localhost:8080/hello" -i
HTTP/1.1 401 Unauthorized
...
Unauthorized
*/
/* usage snippet
$ curl "http://localhost:8080/hello" --compressed -H "x-api-key: secret"
hello, world!
*/
