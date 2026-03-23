package uhttp

// for now
case class Response(data: geny.Writable, code: Int = 200, headers: Iterable[(String, String)] = Seq.empty)

enum Listener:
  case Http(host: String, port: Int)

case class Limits(
  maxMethodLength: Int = 32,
  maxUriLength: Int = 2048,
  maxVersionLength: Int = 16,
  maxHeaderLineLength: Int = 8192,
  maxHeaders: Int = 100
)

class HttpServer(
  listeners: Seq[Listener],
  workerThreads: Int = 4,
  handler: Request => Response = _ => Response("Hello, World!"),
  limits: Limits = Limits()
):
  private val threadPool = java.util.concurrent.Executors.newFixedThreadPool(workerThreads)

  private val sockets = for listener <- listeners yield
    listener match
      case Listener.Http(host, port) =>
        println(s"Creating HTTP listener on $host:$port")
        val serverSocket = new java.net.ServerSocket(port, 50, java.net.InetAddress.getByName(host))
        serverSocket

  def start(): Unit =
    for socket <- sockets do
      println(s"Listening on ${socket.getLocalSocketAddress}")
      threadPool.execute(() => acceptLoop(socket))

  private def acceptLoop(serverSocket: java.net.ServerSocket): Unit =
    while true do
      val clientSocket = try
        serverSocket.accept()
      catch
        case _: java.net.SocketException =>
          return

      threadPool.execute(() => handleClient(clientSocket))

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
      case ParseResult.Ok(request) =>
        var closeConnection = false
        val response = handler(request)

        if !request.headers.get("Connection").flatMap(_.headOption).contains("keep-alive") then
          closeConnection = true

        output.write(s"HTTP/1.1 ${response.code} OK\r\n".getBytes("UTF-8"))

        for (key, value) <- response.headers do
          output.write(s"$key: $value\r\n".getBytes("UTF-8"))

        response.data.contentLength match
          case Some(length) =>
            output.write(s"Content-Length: $length\r\n".getBytes("UTF-8"))
          case None =>
            // No content `length provided, so we need to close the connection after sending the response
            // TODO: handle chunked transfer encoding instead of closing the connection
            output.write("Connection: close\r\n".getBytes("UTF-8"))
            closeConnection = true

        output.write("\r\n".getBytes("UTF-8"))
        response.data.writeBytesTo(output)
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
    for socket <- sockets do
      socket.close()
    threadPool.shutdown()

  def awaitTermination(): Boolean =
    threadPool.awaitTermination(Long.MaxValue, java.util.concurrent.TimeUnit.NANOSECONDS)

  def kill(): Unit =
    for socket <- sockets do socket.close()
    threadPool.shutdownNow()

@main
def main() =
  val server = HttpServer(Seq(Listener.Http("localhost", 8080)))
  server.start()
  Thread.sleep(10000)
  server.stop()
  server.awaitTermination()
  println("Server stopped")
