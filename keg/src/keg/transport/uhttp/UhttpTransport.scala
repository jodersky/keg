package keg.transport.uhttp

import keg.transport.Transport
import keg.model.{Request, Response, RawResponse, WsResponse}
import keg.transport.Listener

case class Limits(
  maxMethodLength: Int = 32,
  maxUriLength: Int = 2048,
  maxVersionLength: Int = 16,
  maxHeaderLineLength: Int = 8192,
  maxHeaders: Int = 100
)

/** An experimental HTTP/1.1 server implementation using plain java.net.*
  * classes, with no external dependencies. It supports both HTTP and HTTPS, but
  * does not support WebSockets or HTTP/2.
  *
  * It is available under ScalaNative too, thus it is well-suited for use in
  * short-lived processes such as CLI tools. Note however that HTTPS support is
  * not available under ScalaNative.
  */
class UhttpTransport(
  listeners: Seq[Listener],
  handler: Request => RawResponse,
  limits: Limits = Limits(),
  workerPool: java.util.concurrent.ExecutorService = java.util.concurrent.Executors.newCachedThreadPool()
) extends Transport:
  private var serverSockets: Seq[(Listener, java.net.ServerSocket)] = Seq.empty

  def start(): Unit =
    serverSockets = for listener <- listeners yield
      listener match
        case Listener.Http(host, port) =>
          val s = new java.net.ServerSocket()
          s.setReuseAddress(true)
          s.bind(new java.net.InetSocketAddress(host, port), 50)
          (listener, s)
        case Listener.Https(host, port, key, cert, clientCa) =>
          val s = keg.transport.Platform.mkSslSocket(key, cert, clientCa)
          s.setReuseAddress(true)
          s.bind(new java.net.InetSocketAddress(host, port), 50)
          (listener, s)

    for (_, socket) <- serverSockets do
      scribe.info(s"Listening on ${socket.getLocalSocketAddress}")
      workerPool.execute(() => acceptLoop(socket))

  def getListeners: Seq[Listener] =
    for (listener, socket) <- serverSockets yield
      val actualPost = socket.getLocalPort()
      listener match
        case Listener.Http(host, _) => Listener.Http(host, actualPost)
        case Listener.Https(host, _, key, cert, clientCa) => Listener.Https(host, actualPost, key, cert, clientCa)

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

        // wrap the input stream in a way that it ends after reading the request
        // body, and that closing it doesn't close the underlying socket input
        // stream (since we might want to keep the connection alive for multiple requests)
        val contentLength = head.headers.get("content-length").flatMap(_.headOption).flatMap(cl => scala.util.Try(cl.toInt).toOption).getOrElse(0)
        var remaining = contentLength
        val bodyStream = new java.io.FilterInputStream(input):
          override def read(): Int =
            if remaining <= 0 then -1
            else
              val byte = super.read()
              if byte != -1 then remaining -= 1
              byte
          override def read(b: Array[Byte], off: Int, len: Int): Int =
            if remaining <= 0 then -1
            else
              val bytesRead = super.read(b, off, math.min(len, remaining))
              if bytesRead > 0 then remaining -= bytesRead
              bytesRead
          override def close(): Unit =
            // don't close the underlying stream, since we might want to read the next request on the same connection
            ()

        val clientCert = keg.transport.Platform.extractClientCertificate(socket)

        val request = Request(
          method = head.method,
          target = head.uri,
          headers = keg.util.MultiDict(head.headers),
          pathParams = Map.empty,
          remainingPathSegments = IndexedSeq.empty,
          cookies = head.headers.get("cookie").map(cs => parseCookieHeaders(cs)).getOrElse(Map.empty),
          session = Map.empty,
          flash = Map.empty,
          getBody = () => bodyStream,
          remoteIp = socket.getInetAddress,
          clientCertificate = clientCert
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

        // TODO: currently headers are streamed directly. This can lead to weird
        // behavior if a header is encountered multiple times but the http spec
        // only allows one. For example, if the user sets the `content-type`
        // header in the response, which is also set in the response *data*, the
        // header will be sent twice. In practice, most browsers will use the
        // last occurrence, so if we send the response headers after the data
        // headers, things *should* work out. This is not spec compliant
        // however, and we should look into alternative solutions.
        for (key, value) <- response.data.headers do
          output.write(s"$key: $value\r\n".getBytes("UTF-8"))
        for (key, value) <- response.headers do
          output.write(s"$key: $value\r\n".getBytes("UTF-8"))

        for cookie <- response.cookies do
          val sb = new StringBuilder(s"${cookie.name}=${cookie.value}")
          if cookie.path != null then sb.append(s"; Path=${cookie.path}")
          if cookie.domain != null then sb.append(s"; Domain=${cookie.domain}")
          if cookie.maxAge != null then sb.append(s"; Max-Age=${cookie.maxAge}")
          if cookie.expires != null then
            val fmt = java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME
            sb.append(s"; Expires=${fmt.format(cookie.expires.atZone(java.time.ZoneOffset.UTC))}")
          if cookie.httpOnly then sb.append("; HttpOnly")
          if cookie.secure then sb.append("; Secure")
          if cookie.sameSite != null then sb.append(s"; SameSite=${cookie.sameSite}")
          output.write(s"Set-Cookie: $sb\r\n".getBytes("UTF-8"))

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
