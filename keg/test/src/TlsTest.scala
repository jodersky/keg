import utest.*

object TlsTest extends TestSuite:

  // openssl req -new -x509 -newkey ec:<(openssl ecparam -name P-256) -nodes -keyout ca-key.pem -out ca-cert.pem -days 3650 -subj "/CN=Test CA" -addext "subjectAltName=DNS:Test CA"



  // openssl genpkey -algorithm EC -pkeyopt ec_paramgen_curve:P-256 -out key.pem
  val privatePem =
    """|-----BEGIN PRIVATE KEY-----
       |MIGHAgEAMBMGByqGSM49AgEGCCqGSM49AwEHBG0wawIBAQQgJKKxADMR517+wiox
       |f7nmJiqxQQjfuRcCmipiydV2cHChRANCAATrOq8dB0br9zWdHPGX6F1XleITwaYh
       |fwKPEYZxJc+BQXgW6+kOP4OJbrCZ9sYxjAD3B5+NG/GrhDqz/MZFEhQf
       |-----END PRIVATE KEY-----
       |""".stripMargin

  // openssl req -new -x509 -key key.pem -out cert.pem -days 3650 -subj "/CN=localhost" -addext "subjectAltName=DNS:localhost,IP:0:0:0:0:0:0:0:1"
  val certPem =
    """|-----BEGIN CERTIFICATE-----
       |MIIBpDCCAUugAwIBAgIUbV56iXHnowcCPoft1bBYZZO/lbAwCgYIKoZIzj0EAwIw
       |FDESMBAGA1UEAwwJbG9jYWxob3N0MB4XDTI2MDMyMzE5NTkwMFoXDTM2MDMyMDE5
       |NTkwMFowFDESMBAGA1UEAwwJbG9jYWxob3N0MFkwEwYHKoZIzj0CAQYIKoZIzj0D
       |AQcDQgAE6zqvHQdG6/c1nRzxl+hdV5XiE8GmIX8CjxGGcSXPgUF4FuvpDj+DiW6w
       |mfbGMYwA9wefjRvxq4Q6s/zGRRIUH6N7MHkwHQYDVR0OBBYEFGXoq09Q6eeEKnCc
       |D+hVB0a2iaHlMB8GA1UdIwQYMBaAFGXoq09Q6eeEKnCcD+hVB0a2iaHlMA8GA1Ud
       |EwEB/wQFMAMBAf8wJgYDVR0RBB8wHYIJbG9jYWxob3N0hxAAAAAAAAAAAAAAAAAA
       |AAABMAoGCCqGSM49BAMCA0cAMEQCIAPVKw0HSBZpndj2YEegKXEc2+lXPz983UF6
       |/sYr1NtnAiAQwrxLEHkB5Z89ARaMLGcGTawzKAgY5MrJ7SNIyqvX1A==
       |-----END CERTIFICATE-----
       |""".stripMargin

  def withServer(key: String, cert: String)(fn: String => Unit): Unit =
    val endpoints = Seq(
      keg.Endpoint(
        selector = keg.EndpointSelector.Simple("/hello", Seq("GET")),
        invoke = _ => keg.EndpointResult.Success(
          keg.Response("Hello, world!"))
        )
    )

    val server = keg.Server(
      endpoints = endpoints,
      listeners = Seq(keg.Listener.Https("::1", 0, key = key, cert = cert))
    )
    server.start()
    try
      fn(s"https://[::1]:${server.serverPort()}")
    finally
      server.stop()
      server.awaitTermination()

  val tests = Tests:
    test("ca"):
      val caKey = os.proc("openssl", "genpkey", "-algorithm", "EC", "-pkeyopt", "ec_paramgen_curve:prime256v1").call().out.text().trim()
      val caCert = os.proc("openssl", "req", "-new", "-x509", "-key", "/dev/stdin", "-days", "3650", "-subj", "/CN=Test CA", "-addext", "subjectAltName=DNS:Test CA").call(stdin = caKey).out.text().trim()

      val serverKey = os.proc("openssl", "genpkey", "-algorithm", "EC", "-pkeyopt", "ec_paramgen_curve:prime256v1").call().out.text().trim()
      val serverCsr = os.proc("openssl", "req", "-new", "-key", "/dev/stdin", "-subj", "/CN=localhost", "-addext", "subjectAltName=DNS:localhost,IP:0:0:0:0:0:0:0:1").call(stdin = serverKey).out.text().trim()

      val tmp = os.temp.dir()
      try
        os.write(tmp / "ca-key.pem", caKey)
        os.write(tmp / "ca-cert.pem", caCert)
        os.write(tmp / "server-key.pem", serverKey)

        val serverCert = os.proc("openssl", "x509", "-req", "-in", "/dev/stdin", "-CA", tmp / "ca-cert.pem", "-CAkey", tmp / "ca-key.pem", "-copy_extensions", "copyall", "-days", "3650").call(stdin = serverCsr).out.text().trim()

        val sslContext = keg.main.Util.mkSslContext(trustedCa = caCert)
        withServer(serverKey, serverCert): url =>
          val response = requests.get(s"$url/hello", sslContext = sslContext)
          assert(response.statusCode == 200)
          assert(response.text() == "Hello, world!")
      finally
        os.remove.all(tmp)

      // val serverCert = os.proc("openssl", "x509", "-req", "-in", "/dev/stdin", "-CA", "/dev/stdin", "-CAkey", "/dev/stdin", "-CAcreateserial")
      //   .call(stdin = serverCsr + "\n" + caCert + "\n" + caKey).out.text().trim()


    // test("sslContext"):
    //   val sslContext = keg.main.Util.mkSslContext(privatePem, certPem)

    //   withServer: url =>

    //     val response = requests.get(s"$url/hello", verifySslCerts = false)
    //     assert(response.statusCode == 200)
    //     assert(response.text() == "Hello, world!")



