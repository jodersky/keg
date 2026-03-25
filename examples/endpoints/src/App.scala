package app

val endpoints = Seq(
  keg.Endpoint(
    selector = keg.EndpointSelector.Simple("/", Seq("GET")),
    invoke = _ =>
      keg.model.EndpointResult.Success(
        keg.Response("Hello, World!", 200)
      )
  ),
  keg.Endpoint(
    selector = keg.EndpointSelector.Simple("/echo", Seq("POST")),
    invoke = req =>
      val body = new String(req.body.readAllBytes(), "UTF-8")
      keg.model.EndpointResult.Success(
        keg.Response(s"Echo: $body", 200)
      )
  ),
  keg.Endpoint(
    selector = keg.EndpointSelector.Simple("/fail", Seq("GET")),
    invoke = _ =>
      keg.model.EndpointResult.Failure.MethodAbort(418, "I'm a teapot")
  ),
  keg.Endpoint(
    selector = keg.EndpointSelector.NotFound,
    invoke = (req) =>
      keg.model.EndpointResult.Success(
        keg.Response("Custom 404 Not Found", 404)
      )
  ),
  keg.model.Recovery(
    invoke = (req, ep, err) =>
      keg.Response(s"Custom error page: ${err}", 500)
  )
)

val server = keg.Server(endpoints, debugMode = false)

@main
def main() =
  server.start()
