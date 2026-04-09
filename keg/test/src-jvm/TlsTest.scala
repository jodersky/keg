import utest.*
import keg.transport.Transport.BuilderFunc

object TlsTest extends TestSuite:

  def withServer(
    key: os.Path,
    cert: os.Path,
    clientCa: Option[os.Path],
    transportBuilder: BuilderFunc
  )(fn: String => Unit): Unit =
    val endpoints = Seq(
      keg.Endpoint(
        selector = keg.EndpointSelector.Simple("/hello", Seq("GET")),
        invoke = req =>
          val name = req.clientCertificate.map(_.subject).getOrElse("world")
          keg.EndpointResult.Success(keg.Response(s"Hello, ${name}!"))
      ),
      keg.Endpoint(
        selector = keg.EndpointSelector.Simple("/altnames", Seq("GET")),
        invoke = req =>
          val altNames = req.clientCertificate
            .map(_.altNames.map((t, v) => s"$t:$v").mkString(","))
            .getOrElse("")
          keg.EndpointResult.Success(keg.Response(altNames))
      )
    )

    val server = keg.Server(
      endpoints = endpoints,
      listeners = Seq(keg.Listener.Https("::1", 0, key = key.toNIO, cert = cert.toNIO, clientCa = clientCa.map(_.toNIO))),
      transportBuilder = transportBuilder
    )
    server.start()
    try
      fn(s"https://[::1]:${server.serverPort()}")
    finally
      server.stop()
      server.awaitTermination()

  val tmpDir = os.temp.dir()
  val serverCaKey = tmpDir / "ca-key.pem"
  val serverCaCert = tmpDir / "ca-cert.pem"
  val serverKey = tmpDir / "server-key.pem"
  val serverCert = tmpDir / "server-cert.pem"

  val clientCaKey = tmpDir / "client-ca-key.pem"
  val clientCaCert = tmpDir / "client-ca-cert.pem"
  val clientKey = tmpDir / "client-key.pem"
  val clientCert = tmpDir / "client-cert.pem"
  val clientP12 = tmpDir / "client.p12"

  // Generate server CA
  os.proc("openssl", "genpkey", "-algorithm", "EC", "-pkeyopt", "ec_paramgen_curve:prime256v1", "-out", serverCaKey).call()
  os.proc("openssl", "req", "-new", "-x509", "-key", serverCaKey, "-days", "3650", "-subj", "/CN=Test CA", "-addext", "subjectAltName=DNS:Test CA", "-out", serverCaCert).call()

  // Generate server cert
  os.proc("openssl", "genpkey", "-algorithm", "EC", "-pkeyopt", "ec_paramgen_curve:prime256v1", "-out", serverKey).call()
  os.proc("openssl", "req", "-new", "-key", serverKey, "-subj", "/CN=localhost", "-addext", "subjectAltName=DNS:localhost,IP:0:0:0:0:0:0:0:1", "-out", tmpDir / "server-csr.pem").call()
  os.proc("openssl", "x509", "-req", "-in", tmpDir / "server-csr.pem", "-CA", serverCaCert, "-CAkey", serverCaKey, "-copy_extensions", "copyall", "-days", "3650", "-out", serverCert).call()

  // Generate client CA
  os.proc("openssl", "genpkey", "-algorithm", "EC", "-pkeyopt", "ec_paramgen_curve:prime256v1", "-out", clientCaKey).call()
  os.proc("openssl", "req", "-new", "-x509", "-key", clientCaKey, "-days", "3650", "-subj", "/CN=Test Client CA", "-addext", "subjectAltName=DNS:Test Client CA", "-out", clientCaCert).call()

  // Generate client cert
  os.proc("openssl", "genpkey", "-algorithm", "EC", "-pkeyopt", "ec_paramgen_curve:prime256v1", "-out", clientKey).call()
  os.proc("openssl", "req", "-new", "-key", clientKey, "-subj", "/CN=Test Client", "-addext", "subjectAltName=DNS:Test Client", "-out", tmpDir / "client-csr.pem").call()
  os.proc("openssl", "x509", "-req", "-in", tmpDir / "client-csr.pem", "-CA", clientCaCert, "-CAkey", clientCaKey, "-copy_extensions", "copyall", "-days", "3650", "-out", clientCert).call()

  // client p12
  os.proc("openssl", "pkcs12", "-export", "-inkey", clientKey, "-in", clientCert, "-certfile", clientCaCert, "-out", clientP12, "-passout", "pass:").call()

  // Generate client cert with multiple SAN types for altNames parsing tests
  val multiSanClientKey = tmpDir / "multi-san-client-key.pem"
  val multiSanClientCert = tmpDir / "multi-san-client-cert.pem"

  os.proc("openssl", "genpkey", "-algorithm", "EC", "-pkeyopt", "ec_paramgen_curve:prime256v1", "-out", multiSanClientKey).call()
  os.proc("openssl", "req", "-new", "-key", multiSanClientKey, "-subj", "/CN=Multi SAN Client",
    "-addext", "subjectAltName=DNS:example.com,email:user@example.com,IP:127.0.0.1,URI:https://example.com",
    "-out", tmpDir / "multi-san-client-csr.pem").call()
  os.proc("openssl", "x509", "-req", "-in", tmpDir / "multi-san-client-csr.pem", "-CA", clientCaCert, "-CAkey", clientCaKey,
    "-copy_extensions", "copyall", "-days", "3650", "-out", multiSanClientCert).call()

  override def utestAfterAll(): Unit =
    os.remove.all(tmpDir)

  val tests = Tests:
    test("server"):
      test("undertow"):
        withServer(serverKey, serverCert, None, keg.transport.UndertowTransport(_, _)): url =>
          val response = requests.get(s"$url/hello", sslContext = keg.transport.Platform.mkSslContext(serverKey.toNIO, serverCert.toNIO, Some(serverCaCert.toNIO)))
          assert(response.statusCode == 200)
          assert(response.text() == "Hello, world!")

      test("uhttp"):
        withServer(serverKey, serverCert, None, keg.transport.uhttp.UhttpTransport(_, _)): url =>
          val response = requests.get(s"$url/hello", sslContext = keg.transport.Platform.mkSslContext(serverKey.toNIO, serverCert.toNIO, Some(serverCaCert.toNIO)))
          assert(response.statusCode == 200)
          assert(response.text() == "Hello, world!")

    test("valid mtls"):
      def testValid(builder: BuilderFunc) = withServer(serverKey, serverCert, Some(clientCaCert), transportBuilder = builder): url =>
          val sslContext = keg.transport.Platform.mkSslContext(clientKey.toNIO, clientCert.toNIO, Some(serverCaCert.toNIO))
          val response = requests.get(s"$url/hello", sslContext = sslContext)
          assert(response.statusCode == 200)
          assert(response.text() == "Hello, CN=Test Client!")

      test("undertow"):
        testValid(keg.transport.UndertowTransport(_, _))

      test("uhttp"):
        testValid(keg.transport.uhttp.UhttpTransport(_, _))

    test("client cert not configured"):
      // if client cert is not configured server side, it should be ignored
      def testValid(builder: BuilderFunc) = withServer(serverKey, serverCert, None, transportBuilder = builder): url =>
          val sslContext = keg.transport.Platform.mkSslContext(clientKey.toNIO, clientCert.toNIO, Some(serverCaCert.toNIO))
          val response = requests.get(s"$url/hello", sslContext = sslContext)
          assert(response.statusCode == 200)
          assert(response.text() == "Hello, world!")

      test("undertow"):
        testValid(keg.transport.UndertowTransport(_, _))

      test("uhttp"):
        testValid(keg.transport.uhttp.UhttpTransport(_, _))

    test("altnames"):
      def testAltNames(builder: BuilderFunc) = withServer(serverKey, serverCert, Some(clientCaCert), transportBuilder = builder): url =>
        val sslContext = keg.transport.Platform.mkSslContext(multiSanClientKey.toNIO, multiSanClientCert.toNIO, Some(serverCaCert.toNIO))
        val response = requests.get(s"$url/altnames", sslContext = sslContext)
        assert(response.statusCode == 200)
        val altNames = response.text().split(",").toSet
        assert(altNames.contains("DNS:example.com"))
        assert(altNames.contains("Email:user@example.com"))
        assert(altNames.contains("IP:127.0.0.1"))
        assert(altNames.contains("URI:https://example.com"))

      test("undertow"):
        testAltNames(keg.transport.UndertowTransport(_, _))

      test("uhttp"):
        testAltNames(keg.transport.uhttp.UhttpTransport(_, _))

    test("invalid mtls"):
      def testInvalid(builder: BuilderFunc) = withServer(serverKey, serverCert, Some(clientCaCert), transportBuilder = builder): url =>
          val sslContext = keg.transport.Platform.mkSslContext(clientKey.toNIO, clientCert.toNIO, None)
          assertThrows[requests.InvalidCertException]:
            requests.get(s"$url/hello", sslContext = sslContext)

      test("undertow"):
        testInvalid(keg.transport.UndertowTransport(_, _))

      test("uhttp"):
        testInvalid(keg.transport.uhttp.UhttpTransport(_, _))
