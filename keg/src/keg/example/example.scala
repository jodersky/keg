package keg.example

val server = keg.Server(
  endpoints = Seq(
    keg.model.Endpoint(
      selector = keg.model.EndpointSelector.Simple("/hello", Seq("GET")),
      invoke = req =>
        keg.Response(
          data = "ok",
          status = 200
        )
    ),
    keg.model.Endpoint(
      selector = keg.model.EndpointSelector.WebSocketUpgrade("/ws"),
      invoke = req =>
        import keg.model.Ws

        keg.model.WsResponse(
          channel =>
            channel.send(Ws.Text("hello from server!"))

            keg.model.WsActor:
              case Ws.Text(txt) =>
                println(s"Received from client: $txt")
                channel.send(Ws.Text(s"echo: $txt"))
              case Ws.ChannelClosed() =>
                println("WebSocket channel closed")
        )
    )
  )
)

@main def runExampleServer(): Unit =
  println("Starting example server on http://localhost:8080")
  server.start()
