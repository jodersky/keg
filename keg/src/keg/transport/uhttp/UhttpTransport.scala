package keg.transport.uhttp

import keg.transport.Transport
import keg.model.{Request, Response, RawResponse, WsResponse}
import keg.transport.Listener
import keg.transport.BackendServer
import java.nio.charset.StandardCharsets.UTF_8

case class Limits(
  maxMethodLength: Int = 32,
  maxUriLength: Int = 2048,
  maxVersionLength: Int = 16,
  maxHeaderLineLength: Int = 8192,
  maxHeaders: Int = 100,
  /** How long a connection may be idle (or stall while sending a request)
    * before it is closed. */
  keepAliveTimeoutMillis: Int = 60000,
  /** If a handler leaves more than this many bytes of a request body unread,
    * the connection is closed rather than skipping over the remainder. */
  maxDrainBytes: Long = 64 * 1024
)

/** An experimental HTTP/1.1 server implementation using plain java.net.*
  * classes, with no external dependencies. It supports both HTTP and HTTPS, but
  * does not support WebSockets or HTTP/2.
  *
  * Connections are kept alive between requests, unless the client asks
  * otherwise. Each connection occupies a thread from the worker pool while it
  * is open.
  *
  * It is available under ScalaNative too, thus it is well-suited for use in
  * short-lived processes such as CLI tools. Note however that HTTPS support is
  * not available under ScalaNative.
  */
class UhttpServer(
  listeners: Seq[Listener],
  handler: Request => RawResponse,
  limits: Limits = Limits(),
  workerPool: java.util.concurrent.ExecutorService = java.util.concurrent.Executors.newCachedThreadPool()
) extends BackendServer:
  private var serverSockets: Seq[(Listener, java.net.ServerSocket)] = Seq.empty

  private class Connection(val socket: java.net.Socket):
    /** Set while a request is being handled, as opposed to waiting for the
      * next one. */
    @volatile var busy = false

  private val connections = java.util.Collections.newSetFromMap(
    new java.util.concurrent.ConcurrentHashMap[Connection, java.lang.Boolean]()
  )
  @volatile private var stopping = false

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
        case Listener.Https(host, port, key, cert, clientCa) => Listener.Https(host, actualPost, key, cert, clientCa)

  private def acceptLoop(serverSocket: java.net.ServerSocket): Unit =
    while true do
      val clientSocket = try
        serverSocket.accept()
      catch
        case _: java.net.SocketException =>
          return

      workerPool.execute(() => handleConnection(clientSocket))

  private def handleConnection(socket: java.net.Socket): Unit =
    val connection = Connection(socket)
    connections.add(connection)
    try
      socket.setTcpNoDelay(true)
      socket.setSoTimeout(limits.keepAliveTimeoutMillis)
      // the parser reads byte by byte, so buffering is essential
      val input = new java.io.BufferedInputStream(socket.getInputStream, 8192)
      val output = new java.io.BufferedOutputStream(socket.getOutputStream, 8192)
      while !stopping && handleRequest(connection, input, output) do ()
    catch
      // the client went away, or the connection timed out
      case _: java.io.IOException => ()
    finally
      connections.remove(connection)
      try socket.close()
      catch case _: java.io.IOException => ()

  /** Reads a request from the connection and writes the response.
    *
    * @return whether the connection can be reused for another request
    */
  private def handleRequest(
    connection: Connection,
    input: java.io.BufferedInputStream,
    output: java.io.OutputStream
  ): Boolean =
    // wait for the next request; a client closing an idle connection is not an error
    input.mark(1)
    if input.read() == -1 then return false
    input.reset()
    connection.busy = true
    try handleRequest0(connection.socket, input, output)
    finally connection.busy = false

  private def handleRequest0(
    socket: java.net.Socket,
    input: java.io.InputStream,
    output: java.io.OutputStream
  ): Boolean =
    def writeErrorResponse(body: String, code: Int, message: String): Boolean =
      val bytes = body.getBytes(UTF_8)
      output.write(s"HTTP/1.1 $code $message\r\nConnection: close\r\nContent-Length: ${bytes.length}\r\n\r\n".getBytes(UTF_8))
      output.write(bytes)
      output.flush()
      false

    parse(
      stream = input,
      maxMethodLength = limits.maxMethodLength,
      maxUriLength = limits.maxUriLength,
      maxVersionLength = limits.maxVersionLength,
      maxHeaderLineLength = limits.maxHeaderLineLength,
      maxHeaders = limits.maxHeaders
    ) match
      case ParseResult.Ok(head) =>
        // comma-separated, case-insensitive header tokens, e.g. of `Connection`
        def headerTokens(name: String): Iterable[String] =
          head.headers.getOrElse(name, Nil).flatMap(_.split(',')).map(_.trim.toLowerCase)

        // Only bodies delimited by Content-Length are supported. A body in any
        // other framing (i.e. chunked) could not be told apart from the next
        // request.
        if head.headers.contains("transfer-encoding") then
          return writeErrorResponse("Request bodies must have a Content-Length", 411, "Length Required")

        val contentLength = head.headers.get("content-length").flatMap(_.headOption) match
          case None => 0L
          case Some(value) =>
            value.trim.toLongOption match
              case Some(n) if n >= 0 => n
              case _ => return writeErrorResponse("Invalid Content-Length", 400, "Bad Request")

        if contentLength > 0 && headerTokens("expect").exists(_ == "100-continue") then
          output.write("HTTP/1.1 100 Continue\r\n\r\n".getBytes(UTF_8))
          output.flush()

        // ends after the request body, and closing it doesn't close the
        // connection (so that it can be used for the next request)
        var remaining = contentLength
        val bodyStream = new java.io.InputStream:
          override def read(): Int =
            if remaining <= 0 then -1
            else
              val byte = input.read()
              if byte == -1 then throw java.io.EOFException("connection closed before end of request body")
              remaining -= 1
              byte
          override def read(b: Array[Byte], off: Int, len: Int): Int =
            if len == 0 then 0
            else if remaining <= 0 then -1
            else
              val bytesRead = input.read(b, off, math.min(len.toLong, remaining).toInt)
              if bytesRead == -1 then throw java.io.EOFException("connection closed before end of request body")
              remaining -= bytesRead
              bytesRead
          override def available(): Int =
            math.min(input.available().toLong, remaining).toInt
          override def close(): Unit = ()

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

        // HTTP/1.1 connections are persistent by default
        val keepAlive =
          !stopping &&
          !headerTokens("connection").exists(_ == "close") &&
          remaining <= limits.maxDrainBytes

        val reasonPhrase = UhttpTransport.StandardReasonPhrases.getOrElse(response.status, "Unknown")
        val responseHead = new StringBuilder(256)
        responseHead.append("HTTP/1.1 ").append(response.status).append(' ').append(reasonPhrase).append("\r\n")

        // TODO: currently headers are streamed directly. This can lead to weird
        // behavior if a header is encountered multiple times but the http spec
        // only allows one. For example, if the user sets the `content-type`
        // header in the response, which is also set in the response *data*, the
        // header will be sent twice. In practice, most browsers will use the
        // last occurrence, so if we send the response headers after the data
        // headers, things *should* work out. This is not spec compliant
        // however, and we should look into alternative solutions.
        for (key, value) <- response.data.headers do
          responseHead.append(key).append(": ").append(value).append("\r\n")
        for (key, value) <- response.headers do
          responseHead.append(key).append(": ").append(value).append("\r\n")

        for cookie <- response.cookies do
          responseHead.append("Set-Cookie: ").append(cookie.name).append('=').append(cookie.value)
          if cookie.path != null then responseHead.append("; Path=").append(cookie.path)
          if cookie.domain != null then responseHead.append("; Domain=").append(cookie.domain)
          if cookie.maxAge != null then responseHead.append("; Max-Age=").append(cookie.maxAge)
          if cookie.expires != null then
            val fmt = java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME
            responseHead.append("; Expires=").append(fmt.format(cookie.expires.atZone(java.time.ZoneOffset.UTC)))
          if cookie.httpOnly then responseHead.append("; HttpOnly")
          if cookie.secure then responseHead.append("; Secure")
          if cookie.sameSite != null then responseHead.append("; SameSite=").append(cookie.sameSite)
          responseHead.append("\r\n")

        response.data.contentLength match
          case Some(length) => responseHead.append("Content-Length: ").append(length).append("\r\n")
          // the length is not known upfront, so the body is sent in chunks
          case None => responseHead.append("Transfer-Encoding: chunked\r\n")

        if !keepAlive then responseHead.append("Connection: close\r\n")
        responseHead.append("\r\n")
        output.write(responseHead.toString.getBytes(UTF_8))

        response.data.contentLength match
          case Some(_) =>
            response.data.write(UnclosableOutputStream(output))
          case None =>
            val chunked = ChunkedOutputStream(output)
            response.data.write(chunked)
            chunked.finish()
        output.flush()

        // skip whatever the handler didn't read of the body, so that the next
        // request can be read
        if keepAlive && remaining > 0 then
          val skipBuffer = new Array[Byte](8192)
          while bodyStream.read(skipBuffer) != -1 do ()

        keepAlive

      case ParseResult.RequestLineTooLarge =>
        writeErrorResponse("Request line too large", 414, "Request-URI Too Long")
      case ParseResult.HeadersTooLarge =>
        writeErrorResponse("Request header fields too large", 431, "Request Header Fields Too Large")
      case ParseResult.MalformedRequest =>
        writeErrorResponse("Bad request", 400, "Bad Request")
      case ParseResult.UnsupportedProtocol =>
        writeErrorResponse("HTTP Version Not Supported", 505, "HTTP Version Not Supported")

  /** Closes connections that are waiting for a request. Connections that are
    * handling one close once the response is written. */
  private def closeIdleConnections(): Unit =
    connections.forEach: connection =>
      if !connection.busy then
        try connection.socket.close()
        catch case _: java.io.IOException => ()

  def stop(): Unit =
    stopping = true
    for (_, socket) <- serverSockets do
      socket.close()
    closeIdleConnections()
    workerPool.shutdown()

  def awaitTermination(): Unit =
    workerPool.awaitTermination(Long.MaxValue, java.util.concurrent.TimeUnit.NANOSECONDS)

  def kill(): Unit =
    stopping = true
    for (_, socket) <- serverSockets do socket.close()
    connections.forEach: connection =>
      try connection.socket.close()
      catch case _: java.io.IOException => ()
    workerPool.shutdownNow()

/** Prevents response data from closing the connection. */
private class UnclosableOutputStream(out: java.io.OutputStream) extends java.io.OutputStream:
  override def write(b: Int): Unit = out.write(b)
  override def write(b: Array[Byte], off: Int, len: Int): Unit = out.write(b, off, len)
  override def flush(): Unit = out.flush()
  override def close(): Unit = out.flush()

/** Writes data with HTTP/1.1 chunked transfer encoding.
  *
  * Small writes are collected into chunks of up to 8 KB. `finish()` writes the
  * last chunk; closing this stream does not close the underlying one.
  */
private class ChunkedOutputStream(out: java.io.OutputStream) extends java.io.OutputStream:
  private val buffer = new Array[Byte](8192)
  private var count = 0

  private def writeChunk(b: Array[Byte], off: Int, len: Int): Unit =
    if len > 0 then
      out.write(Integer.toHexString(len).getBytes(UTF_8))
      out.write('\r')
      out.write('\n')
      out.write(b, off, len)
      out.write('\r')
      out.write('\n')

  private def flushBuffer(): Unit =
    writeChunk(buffer, 0, count)
    count = 0

  override def write(b: Int): Unit =
    if count == buffer.length then flushBuffer()
    buffer(count) = b.toByte
    count += 1

  override def write(b: Array[Byte], off: Int, len: Int): Unit =
    if len >= buffer.length then
      flushBuffer()
      writeChunk(b, off, len)
    else
      if count + len > buffer.length then flushBuffer()
      System.arraycopy(b, off, buffer, count, len)
      count += len

  override def flush(): Unit =
    flushBuffer()
    out.flush()

  override def close(): Unit = flush()

  def finish(): Unit =
    flushBuffer()
    out.write("0\r\n\r\n".getBytes(UTF_8))

object UhttpTransport extends Transport:

  def init(listeners: Seq[Listener], handler: Request => RawResponse) =
    UhttpServer(listeners, handler)

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
