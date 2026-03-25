package keg.transport

object Platform:

  def makeSslSocket(
    key: String,
    cert: String
  ): java.net.ServerSocket =
    throw new UnsupportedOperationException("SSL sockets are not supported on this platform")

  def mkDefaultTransport: Transport.BuilderFunc =
    (listeners, handler, log) => new uhttp.UhttpTransport(listeners, handler, log)
