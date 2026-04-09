package keg.transport

import java.nio.file.Path

object Platform:

  def mkSslSocket(
    key: Path,
    cert: Path,
    clientCa: Option[Path] = None
  ): java.net.ServerSocket =
    val sslContext = mkSslContext(key, cert, clientCa)
    val serverSocketFactory = sslContext.getServerSocketFactory
    val sock = serverSocketFactory.createServerSocket().asInstanceOf[javax.net.ssl.SSLServerSocket]
    if clientCa.isDefined then
      sock.setWantClientAuth(true)
    sock

  def mkSslContext(
    key: Path,
    cert: Path,
    clientCa: Option[Path] = None
  ): javax.net.ssl.SSLContext =
    Ssl.mkSslContext(key, cert, clientCa)

  def extractClientCertificate(sock: java.net.Socket): Option[keg.model.ClientCertificate] =
    sock match
      case ssls: javax.net.ssl.SSLSocket =>
        Ssl.extractClientCert(ssls.getSession())
      case _ => None

  def mkDefaultTransport: Transport.BuilderFunc =
    (listeners, handler) => UndertowTransport(listeners, handler)
