package app

// Websocket support is more-or-less copied directly from cask.

//snippet:start
@keg.websocket("/hello/:x")
def hello(x: String): keg.WebResponse =
  if x != "secret" then
    // you can also return a normal HTTP response in a websocket handler if you
    // want to
    keg.Response("Unauthorized", status = 401)
  else
    keg.WsResponse: channel =>
      channel.send(keg.Ws.Text("hello world"))

      keg.WsActor:
        case keg.Ws.Text(txt) =>
          channel.send(keg.Ws.Text(s"echo: $txt"))
        case keg.Ws.Close(code, reason) =>
          println(s"Received close: $code - $reason")
        case keg.Ws.ChannelClosed() =>
          println("WebSocket channel closed")
//snippet:end

// One thing which is different about keg however, is that not `actorContext` is
// needed to create a `WsActor`. Keg does this by injecting a context with a
// context function into `keg.WsResponse(ctx ?=> channel => actor)`.
val server = keg.Server(keg.routes().endpoints)

@main
def main() =
  server.start()
