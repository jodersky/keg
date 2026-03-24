package keg.transport.uhttp

import keg.transport.Transport
import keg.transport.TransportBuilder
import keg.model.{Request, Response, WebResponse, WsResponse}
import keg.main.Listener

case class Limits(
  maxMethodLength: Int = 32,
  maxUriLength: Int = 2048,
  maxVersionLength: Int = 16,
  maxHeaderLineLength: Int = 8192,
  maxHeaders: Int = 100
)

class UhttpTransportBuilder extends TransportBuilder:
  override type TransportImpl = UhttpTransport

  var limits: Limits = Limits()
  var workerPool = java.util.concurrent.Executors.newFixedThreadPool(4)

  override def build(
    listeners: Seq[Listener],
    handler: Request => WebResponse,
    log: String => Unit
  ): UhttpTransport =
    new UhttpTransport(limits, workerPool, listeners, handler, log)

/** A simple HTTP/1.1 server implementation using plain java.net.* classes, with
  * no external dependencies. It supports both HTTP and HTTPS, but does not
  * support WebSockets or HTTP/2.
  *
  * It is available under ScalaNative too, thus it is well-suited for use in
  * short-lived processes such as CLI tools.
  */
class UhttpTransport(
  limits: Limits,
  workerPool: java.util.concurrent.ExecutorService,
  listeners: Seq[Listener],
  handler: Request => WebResponse,
  log: String => Unit
) extends Transport:
  private var serverSockets: Seq[(Listener, java.net.ServerSocket)] = Seq.empty

  def start(): Unit =
    serverSockets = for listener <- listeners yield
      listener match
        case Listener.Http(host, port) =>
          log(s"Creating HTTP listener on $host:$port")
          (listener, new java.net.ServerSocket(port, 50, java.net.InetAddress.getByName(host)))
        case Listener.Https(host, port, key, cert) =>
          log(s"Creating HTTPS listener on $host:$port with cert $cert and key $key")
          val sslContext = keg.main.Util.mkSslContext(key, cert)
          val serverSocketFactory = sslContext.getServerSocketFactory
          (listener, serverSocketFactory.createServerSocket(port, 50, java.net.InetAddress.getByName(host)))

    for (_, socket) <- serverSockets do
      log(s"Listening on ${socket.getLocalSocketAddress}")
      workerPool.execute(() => acceptLoop(socket))

  def getListeners: Seq[Listener] =
    serverSockets.map(_._1) // TODO: return actual socket addresses instead of configured listeners, to handle the case where port 0 was used

  private def acceptLoop(serverSocket: java.net.ServerSocket): Unit =
    while true do
      val clientSocket = try
        serverSocket.accept()
      catch
        case _: java.net.SocketException =>
          return

      workerPool.execute(() => handleClient(clientSocket))

  private def handleClient(socket: java.net.Socket): Unit =
    val input = socket.getInputStream
    val output = socket.getOutputStream

    def writeErrorResponse(body: String,code: Int, message: String) =
      val bytes = body.getBytes("UTF-8")
      val length = bytes.length
      output.write(s"HTTP/1.1 $code $message\r\nClose: true\r\nContent-Length: $length\r\n\r\n".getBytes("UTF-8"))
      output.write(bytes)
      output.flush()
      socket.close()

    parse(
      stream = input,
      maxMethodLength = limits.maxMethodLength,
      maxUriLength = limits.maxUriLength,
      maxVersionLength = limits.maxVersionLength,
      maxHeaderLineLength = limits.maxHeaderLineLength,
      maxHeaders = limits.maxHeaders
    ) match
      case ParseResult.Ok(head) =>

        val request = Request(
          method = head.method,
          path = head.uri.split('?').headOption.getOrElse(""),
          queryParams = keg.util.MultiDict.empty, // TODO: parse query params from URI
          headers = keg.util.MultiDict(head.headers),
          pathParams = Map.empty,
          remainingPathSegments = IndexedSeq.empty,
          cookies = head.headers.get("cookie").map(cs => parseCookieHeaders(cs)).getOrElse(Map.empty),
          session = Map.empty,
          flash = Map.empty,
          getBody = () => input,
          remoteIp = socket.getInetAddress
        )
        val response: Response[keg.model.Data] =
          try
            handler(request) match
              case simple: Response[keg.model.Data] => simple
              case wsResponse: WsResponse =>
                throw UnsupportedOperationException("WebSockets are not supported by this transport")
          catch
            case e: Throwable =>
              // this should never happen in well-behaved applications; the
              // recovery function should have dealt with any exceptions thrown
              // by the application code
              e.printStackTrace()
              Response("Internal Server Error", 500)

        var closeConnection = false
        if !request.headers.get("Connection").flatMap(_.headOption).contains("keep-alive") then
          closeConnection = true

        val reasonPhrase = UhttpTransport.StandardReasonPhrases.getOrElse(response.status, "Unknown")
        output.write(s"HTTP/1.1 ${response.status} $reasonPhrase\r\n".getBytes("UTF-8"))

        for (key, value) <- response.headers do
          output.write(s"$key: $value\r\n".getBytes("UTF-8"))

        for (key, value) <- response.data.headers do
          output.write(s"$key: $value\r\n".getBytes("UTF-8"))

        if response.data.contentLength.isEmpty then
          // No content length provided, so we need to close the connection after sending the response
          output.write("Connection: close\r\n".getBytes("UTF-8"))
          closeConnection = true
        else
          output.write(s"Content-Length: ${response.data.contentLength.get}\r\n".getBytes("UTF-8"))

        output.write("\r\n".getBytes("UTF-8"))
        response.data.write(output)
        output.flush()
        if closeConnection then socket.close()

      case ParseResult.RequestLineTooLarge =>
        writeErrorResponse("Request line too large", 414, "Request-URI Too Long")
      case ParseResult.HeadersTooLarge =>
        writeErrorResponse("Request header fields too large", 431, "Request Header Fields Too Large")
      case ParseResult.MalformedRequest =>
        writeErrorResponse("Bad request", 400, "Bad Request")
      case ParseResult.UnsupportedProtocol =>
        writeErrorResponse("HTTP Version Not Supported", 505, "HTTP Version Not Supported")

  def stop(): Unit =
    for (_, socket) <- serverSockets do
      socket.close()
    workerPool.shutdown()

  def awaitTermination(): Unit =
    workerPool.awaitTermination(Long.MaxValue, java.util.concurrent.TimeUnit.NANOSECONDS)

  def kill(): Unit =
    for (_, socket) <- serverSockets do socket.close()
    workerPool.shutdownNow()

// @main
// def main() =
//   val server = HttpServer(Seq(Listener.Http("localhost", 8080)))
//   server.start()
//   Thread.sleep(10000)
//   server.stop()
//   server.awaitTermination()
//   println("Server stopped")

object UhttpTransport:

  val StandardReasonPhrases = Map(
    100 -> "Continue",
    101 -> "Switching Protocols",
    102 -> "Processing",
    200 -> "OK",
    201 -> "Created",
    202 -> "Accepted",
    203 -> "Non-Authoritative Information",
    204 -> "No Content",
    205 -> "Reset Content",
    206 -> "Partial Content",
    207 -> "Multi-Status",
    208 -> "Already Reported",
    226 -> "IM Used",
    300 -> "Multiple Choices",
    301 -> "Moved Permanently",
    302 -> "Found",
    303 -> "See Other",
    304 -> "Not Modified",
    305 -> "Use Proxy",
    307 -> "Temporary Redirect",
    308 -> "Permanent Redirect",
    400 -> "Bad Request",
    401 -> "Unauthorized",
    402 -> "Payment Required",
    403 -> "Forbidden",
    404 -> "Not Found",
    405 -> "Method Not Allowed",
    406 -> "Not Acceptable",
    407 -> "Proxy Authentication Required",
    408 -> "Request Timeout",
    409 -> "Conflict",
    410 -> "Gone",
    411 -> "Length Required",
    412 -> "Precondition Failed",
    413 -> "Payload Too Large",
    414 -> "Request-URI Too Long",
    415 -> "Unsupported Media Type",
    416 -> "Requested Range Not Satisfiable",
    417 -> "Expectation Failed",
    418 -> "I'm a teapot",
    421 -> "Misdirected Request",
    422 -> "Unprocessable Entity",
    423 -> "Locked",
    424 -> "Failed Dependency",
    425 -> "Too Early",
    426 -> "Upgrade Required",
    428 -> "Precondition Required",
    429 -> "Too Many Requests",
    431 -> "Request Header Fields Too Large",
    451 -> "Unavailable For Legal Reasons",
    500 -> "Internal Server Error",
    501 -> "Not Implemented",
    502 -> "Bad Gateway",
    503 -> "Service Unavailable",
    504 -> "Gateway Timeout",
    505 -> "HTTP Version Not Supported",
    506 -> "Variant Also Negotiates",
    507 -> "Insufficient Storage",
    508 -> "Loop Detected",
    510 -> "Not Extended",
    511 -> "Network Authentication Required"
  )
