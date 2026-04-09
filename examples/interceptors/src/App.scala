package app

// example interceptor uses:
// - gzip compression for requests and responses
// - metrics collection and logging
// - authentication and authorization

//snippet:start
import java.util.zip.{GZIPInputStream, GZIPOutputStream}

object GzipInterceptor extends keg.main.Interceptor:
  def intercept(request: keg.Request, ep: keg.Endpoint, next: keg.Request => keg.WebResponse): keg.WebResponse =
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

@keg.get("/hello")
def hello() =
  "hello world\n"

val routes = keg.routes()

@main
def main() =
  val server = keg.Server(routes.endpoints, interceptors = Seq(GzipInterceptor))
  server.start()
//snippet:end
/* usage snippet
$ curl "http://localhost:8080/hello" --compressed -i
HTTP/1.1 200 OK
...
Content-Encoding: gzip
...
hello world
*/
