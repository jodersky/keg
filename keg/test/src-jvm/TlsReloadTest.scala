import utest.*

/** Tests that the SSL context hot-reloads when the certificate/key files on
  * disk are replaced while the server is running.
  */
object TlsReloadTest extends TestSuite:

  val tmpDir = os.temp.dir()

  override def utestAfterAll(): Unit =
    os.remove.all(tmpDir)

  /** Generate a self-signed CA + leaf cert pair into the given directory,
    * using a CN so we can tell certs apart in assertions.
    */
  private def generateCerts(dir: os.Path, cn: String): (os.Path, os.Path, os.Path) =
    os.makeDir(dir)
    val caKey  = dir / s"$cn-ca-key.pem"
    val caCert = dir / s"$cn-ca-cert.pem"
    val srvKey = dir / s"$cn-server-key.pem"
    val srvCsr = dir / s"$cn-server-csr.pem"
    val srvCert = dir / s"$cn-server-cert.pem"

    os.proc("openssl", "genpkey", "-algorithm", "EC", "-pkeyopt", "ec_paramgen_curve:prime256v1", "-out", caKey).call()
    os.proc("openssl", "req", "-new", "-x509", "-key", caKey, "-days", "1", "-subj", s"/CN=$cn CA", "-out", caCert).call()

    os.proc("openssl", "genpkey", "-algorithm", "EC", "-pkeyopt", "ec_paramgen_curve:prime256v1", "-out", srvKey).call()
    os.proc("openssl", "req", "-new", "-key", srvKey, "-subj", "/CN=localhost",
      "-addext", "subjectAltName=DNS:localhost,IP:0:0:0:0:0:0:0:1", "-out", srvCsr).call()
    os.proc("openssl", "x509", "-req", "-in", srvCsr, "-CA", caCert, "-CAkey", caKey,
      "-copy_extensions", "copyall", "-days", "1", "-out", srvCert).call()

    (caKey, caCert, srvKey, srvCert) match
      case _ => (caCert, srvKey, srvCert)

  /** Poll fn until it returns without throwing, or fail after timeout. */
  private def eventually(timeoutMs: Long = 5000, intervalMs: Long = 100)(fn: => Unit): Unit =
    val deadline = System.currentTimeMillis() + timeoutMs
    var lastError: Throwable = null
    while System.currentTimeMillis() < deadline do
      try
        fn
        return
      catch
        case e: Throwable =>
          lastError = e
          Thread.sleep(intervalMs)
    throw lastError

  val tests = Tests:
    test("server cert reload"):
      val certDir = tmpDir / "reload-test"
      os.makeDir(certDir)

      // Generate two independent CA + server cert pairs
      val (ca1Cert, srv1Key, srv1Cert) = generateCerts(certDir / "gen1", "gen1")
      val (ca2Cert, srv2Key, srv2Cert) = generateCerts(certDir / "gen2", "gen2")

      // These are the files the server watches
      val liveKey  = certDir / "server-key.pem"
      val liveCert = certDir / "server-cert.pem"

      // Start with cert 1
      os.copy(srv1Key, liveKey)
      os.copy(srv1Cert, liveCert)

      val server = keg.Server(
        endpoints = Seq(
          keg.Endpoint(
            selector = keg.EndpointSelector.Simple("/ping", Seq("GET")),
            invoke = _ => keg.EndpointResult.Success(keg.Response("pong"))
          )
        ),
        listeners = Seq(keg.Listener.Https("::1", 0, key = liveKey.toNIO, cert = liveCert.toNIO, clientCa = None)),
        transportBuilder = keg.transport.UndertowTransport(_, _)
      )
      server.start()
      val url = s"https://[::1]:${server.serverPort()}"

      try
        // Request with CA1 should succeed immediately
        val sslCtx1 = keg.transport.Platform.mkSslContext(srv1Key.toNIO, srv1Cert.toNIO, Some(ca1Cert.toNIO))
        val r1 = requests.get(s"$url/ping", sslContext = sslCtx1)
        assert(r1.statusCode == 200)

        // Swap in cert 2
        os.write.over(liveKey, os.read(srv2Key))
        os.write.over(liveCert, os.read(srv2Cert))

        val sslCtx2 = keg.transport.Platform.mkSslContext(srv2Key.toNIO, srv2Cert.toNIO, Some(ca2Cert.toNIO))

        // After reload the server should serve cert 2, accepted by CA2
        eventually():
          val r2 = requests.get(s"$url/ping", sslContext = sslCtx2)
          assert(r2.statusCode == 200)

        // CA1 should no longer trust the server cert. Use a fresh SSLContext to
        // force a full handshake — sslCtx1 has a cached session from the first
        // request and would skip cert verification via session resumption.
        assertThrows[Exception]:
          val freshCtx = keg.transport.Platform.mkSslContext(srv1Key.toNIO, srv1Cert.toNIO, Some(ca1Cert.toNIO))
          requests.get(s"$url/ping", sslContext = freshCtx)

      finally
        server.stop()
        server.awaitTermination()
