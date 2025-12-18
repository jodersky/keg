import utest.*

object WsTest extends TestSuite:
  val tests = Tests:
    test("ws"):
      val latch = java.util.concurrent.CountDownLatch(1)

      val server = keg.Server(
        endpoints = Seq(
          keg.Endpoint(
            selector = keg.EndpointSelector.WebSocketUpgrade("/ws"),
            invoke = req =>
              keg.WsResponse(
                channel =>
                  channel.send(keg.Ws.Text("hello from server!"))
                  keg.WsActor:
                    case keg.Ws.Text(txt) =>
                      channel.send(keg.Ws.Text(s"echo: $txt"))
                    case keg.Ws.Close(code, reason) =>
                      println(s"Received close: $code - $reason")
                      latch.countDown()
                    case keg.Ws.ChannelClosed() =>
                      println("WebSocket channel closed")
              )
          )
        ),
        port = 0
      )

      object Listener extends java.net.http.WebSocket.Listener:
        override def onOpen(webSocket: java.net.http.WebSocket): Unit =
          println("WebSocket opened")
          webSocket.request(1)

        override def onText(webSocket: java.net.http.WebSocket, data: CharSequence, last: Boolean): java.util.concurrent.CompletionStage[?] =
          println(s"Received from server: $data")
          if data.toString.startsWith("echo:") then
            println("Closing WebSocket")
            webSocket.sendClose(java.net.http.WebSocket.NORMAL_CLOSURE, "bye")
          else
            webSocket.request(1)
          java.util.concurrent.CompletableFuture.completedFuture(null)

        override def onClose(webSocket: java.net.http.WebSocket, statusCode: Int, reason: String): java.util.concurrent.CompletionStage[?] =
          println(s"WebSocket closed: $statusCode - $reason")
          java.util.concurrent.CompletableFuture.completedFuture(null)

        override def onError(webSocket: java.net.http.WebSocket, error: Throwable): Unit =
          println(s"WebSocket error: $error")

      server.start()
      try
        java.net.http.HttpClient
          .newHttpClient()
          .newWebSocketBuilder()
          .buildAsync(
            java.net.URI.create(s"ws://localhost:${server.serverPort()}/ws"),
            Listener
          )
          .get()
          .sendText("hello server!", true).get()

        assert(latch.await(10, java.util.concurrent.TimeUnit.SECONDS))
      finally
        server.stop()

