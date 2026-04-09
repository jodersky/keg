package keg.transport

import java.nio.file.Path

object Platform:

  def mkSslSocket(
    key: Path,
    cert: Path,
    clientCa: Option[Path] = None
  ): java.net.ServerSocket =
    throw new UnsupportedOperationException("SSL sockets are not supported on this platform")

  def extractClientCertificate(socket: java.net.Socket): Option[keg.model.ClientCertificate] = None

  def mkDefaultTransport: Transport.BuilderFunc =
    (listeners, handler) => new uhttp.UhttpTransport(listeners, handler)
