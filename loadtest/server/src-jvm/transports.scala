package loadtest

val transports: Map[String, keg.transport.Transport] = Map(
  "undertow" -> keg.transport.UndertowTransport,
  "uhttp" -> keg.transport.uhttp.UhttpTransport
)

val defaultTransport = "undertow"
