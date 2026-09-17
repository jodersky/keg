package loadtest

val transports: Map[String, keg.transport.Transport] = Map(
  "uhttp" -> keg.transport.uhttp.UhttpTransport
)

val defaultTransport = "uhttp"
