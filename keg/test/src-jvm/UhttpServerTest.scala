import utest.*

// Tests for connection handling in the uhttp transport. These use raw sockets
// rather than an HTTP client, to check exactly what is sent over a connection.
object UhttpServerTest extends TestSuite:

  val endpoints = Seq(
    keg.Endpoint(
      "/hello", Seq("GET"),
      invoke = (req: keg.Request) => keg.Response("Hello, World!"),
      meta = keg.EndpointMeta()
    ),
    keg.Endpoint(
      "/ignore-body", Seq("POST"),
      invoke = (req: keg.Request) => keg.Response("ignored"),
      meta = keg.EndpointMeta()
    ),
    keg.Endpoint(
      "/echo", Seq("POST"),
      invoke = (req: keg.Request) => keg.Response(req.text()),
      meta = keg.EndpointMeta()
    ),
    keg.Endpoint(
      "/streamed", Seq("GET"),
      invoke = (req: keg.Request) =>
        keg.Response[keg.Data](
          new keg.Data:
            def write(out: java.io.OutputStream) = out.write("hello".getBytes("UTF-8"))
            def headers = Seq("Content-Type" -> "text/plain")
            def contentLength = None
        ),
      meta = keg.EndpointMeta()
    )
  )

  // port 0 picks a free port, so that this can run in parallel with other tests
  def mkServer() = keg.Server(
    endpoints = endpoints,
    listeners = Seq(keg.transport.Listener.Http("::1", 0)),
    transport = keg.transport.uhttp.UhttpTransport
  )

  /** Starts a server and passes a connection to it. */
  def withConnection(f: java.net.Socket => Unit): Unit =
    val server = mkServer()
    server.start()
    try
      val socket = connect(server)
      try f(socket)
      finally socket.close()
    finally server.stop()

  def connect(server: keg.Server): java.net.Socket =
    val socket = new java.net.Socket("::1", server.serverPort())
    socket.setSoTimeout(5000)
    socket

  def send(socket: java.net.Socket, data: String): Unit =
    socket.getOutputStream.write(data.getBytes("UTF-8"))
    socket.getOutputStream.flush()

  /** Reads until the server closes the connection. */
  def readAll(socket: java.net.Socket): String =
    new String(socket.getInputStream.readAllBytes(), "UTF-8")

  /** Reads until the received data ends with the given suffix. */
  def readUntil(socket: java.net.Socket, suffix: String): String =
    val in = socket.getInputStream
    val received = new StringBuilder
    while !received.endsWith(suffix) do
      val byte = in.read()
      if byte == -1 then throw new java.io.EOFException(s"connection closed, received so far: $received")
      received.append(byte.toChar)
    received.toString

  def count(text: String, part: String): Int =
    text.sliding(part.length).count(_ == part)

  val tests = Tests:
    test("keep-alive"):
      withConnection: socket =>
        send(socket, "GET /hello HTTP/1.1\r\nHost: localhost\r\n\r\n")
        val first = readUntil(socket, "Hello, World!")
        assert(first.startsWith("HTTP/1.1 200 OK\r\n"))
        assert(!first.contains("Connection: close"))

        // the same connection is used for the next request
        send(socket, "GET /hello HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n")
        val second = readAll(socket)
        assert(second.startsWith("HTTP/1.1 200 OK\r\n"))
        assert(second.contains("Connection: close\r\n"))
        assert(second.endsWith("Hello, World!"))

    test("pipelining"):
      withConnection: socket =>
        send(
          socket,
          "POST /echo HTTP/1.1\r\nContent-Length: 3\r\n\r\nabc" +
            "POST /echo HTTP/1.1\r\nContent-Length: 3\r\n\r\ndef" +
            "GET /hello HTTP/1.1\r\nConnection: close\r\n\r\n"
        )
        val responses = readAll(socket)
        assert(count(responses, "HTTP/1.1 200 OK\r\n") == 3)
        assert(responses.indexOf("abc") < responses.indexOf("def"))
        assert(responses.endsWith("Hello, World!"))

    test("unread request body is skipped"):
      withConnection: socket =>
        send(
          socket,
          "POST /ignore-body HTTP/1.1\r\nContent-Length: 11\r\n\r\nhello world" +
            "GET /hello HTTP/1.1\r\nConnection: close\r\n\r\n"
        )
        val responses = readAll(socket)
        assert(count(responses, "HTTP/1.1 200 OK\r\n") == 2)
        assert(responses.endsWith("Hello, World!"))

    test("chunked response"):
      withConnection: socket =>
        send(socket, "GET /streamed HTTP/1.1\r\n\r\n")
        val response = readUntil(socket, "\r\n0\r\n\r\n")
        assert(response.contains("Transfer-Encoding: chunked\r\n"))
        assert(!response.contains("Connection: close"))
        assert(response.endsWith("\r\n\r\n5\r\nhello\r\n0\r\n\r\n"))

        // the connection is still usable
        send(socket, "GET /hello HTTP/1.1\r\nConnection: close\r\n\r\n")
        assert(readAll(socket).endsWith("Hello, World!"))

    test("chunked request is rejected"):
      withConnection: socket =>
        send(socket, "POST /echo HTTP/1.1\r\nTransfer-Encoding: chunked\r\n\r\n3\r\nabc\r\n0\r\n\r\n")
        val response = readAll(socket)
        assert(response.startsWith("HTTP/1.1 411 Length Required\r\n"))
        assert(response.contains("Connection: close\r\n"))

    test("invalid content length is rejected"):
      withConnection: socket =>
        send(socket, "POST /echo HTTP/1.1\r\nContent-Length: abc\r\n\r\n")
        assert(readAll(socket).startsWith("HTTP/1.1 400 Bad Request\r\n"))

    test("expect 100-continue"):
      withConnection: socket =>
        send(socket, "POST /echo HTTP/1.1\r\nContent-Length: 3\r\nExpect: 100-continue\r\nConnection: close\r\n\r\n")
        assert(readUntil(socket, "\r\n\r\n") == "HTTP/1.1 100 Continue\r\n\r\n")
        send(socket, "abc")
        val response = readAll(socket)
        assert(response.startsWith("HTTP/1.1 200 OK\r\n"))
        assert(response.endsWith("abc"))

    test("stop closes idle connections"):
      val server = mkServer()
      server.start()
      val socket = connect(server)
      try
        send(socket, "GET /hello HTTP/1.1\r\n\r\n")
        readUntil(socket, "Hello, World!")

        // the connection is idle and kept alive; stopping must not wait for it
        // to time out
        server.stop()
        val waiter = new Thread(() => server.awaitTermination())
        waiter.start()
        waiter.join(5000)
        assert(!waiter.isAlive)
        assert(socket.getInputStream.read() == -1)
      finally
        socket.close()
        server.kill()
