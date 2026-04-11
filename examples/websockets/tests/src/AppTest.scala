import utest.*

object AppTest extends TestSuite:

  def withServer[T](server0: keg.Server)(f: Int => T): T =
    val server = server0.copy(listeners = List(keg.Listener.Http("::", 0)))
    server.start()
    try f(server.serverPort())
    finally server.stop()

  val tests = Tests:

    test("unauthorized"):
      withServer(app.server): port =>
        val ex = assertThrows[java.util.concurrent.ExecutionException]:
          java.net.http.HttpClient
            .newHttpClient()
            .newWebSocketBuilder()
            .buildAsync(
              java.net.URI.create(s"ws://localhost:$port/hello/notthesecret"),
              new java.net.http.WebSocket.Listener {}
            )
            .get()
        assert(ex.getCause.isInstanceOf[java.net.http.WebSocketHandshakeException])
        val handshakeEx = ex.getCause.asInstanceOf[java.net.http.WebSocketHandshakeException]
        assert(handshakeEx.getResponse.statusCode() == 401)

    test("echo"):
      withServer(app.server): port =>
        val latch = java.util.concurrent.CountDownLatch(1)
        val received = collection.mutable.ArrayBuffer.empty[String]

        object Listener extends java.net.http.WebSocket.Listener:
          override def onOpen(ws: java.net.http.WebSocket): Unit =
            ws.request(1)

          override def onText(ws: java.net.http.WebSocket, data: CharSequence, last: Boolean)
              : java.util.concurrent.CompletionStage[?] =
            received += data.toString
            if data.toString.startsWith("echo:") then
              latch.countDown()
            else
              ws.request(1)
            java.util.concurrent.CompletableFuture.completedFuture(null)

        java.net.http.HttpClient
          .newHttpClient()
          .newWebSocketBuilder()
          .buildAsync(
            java.net.URI.create(s"ws://localhost:$port/hello/secret"),
            Listener
          )
          .get()
          .sendText("hi", true)
          .get()

        assert(latch.await(10, java.util.concurrent.TimeUnit.SECONDS))
        assert(received.contains("hello world"))
        assert(received.exists(_.startsWith("echo:")))
