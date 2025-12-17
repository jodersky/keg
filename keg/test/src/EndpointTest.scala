import utest.*

object EndpointTest extends TestSuite:

  def withServer(endpoints: Seq[keg.Endpoint])(fn: String => Unit): Unit =
    val server = keg.Server(
      endpoints = endpoints,
      port = 0
    )
    server.start()
    try
      fn(s"http://localhost:${server.serverPort()}")
    finally
      server.stop()

  val tests = Tests:
    test("request"):
      val endpoints = Seq(
        keg.Endpoint(
          selector = keg.EndpointSelector.Simple("/echo", Seq("POST")),
          invoke = (req: keg.Request) =>

            keg.Response(
              data = new String(req.body.readAllBytes(), "UTF-8"),
              headers = Seq(
                "x-pong" -> req.headers.get("x-ping").map(_.last).getOrElse("no ping")
              ),
              status = req.headers.get("x-status").flatMap(_.lastOption).map(_.toInt).getOrElse(200)
            )
        )
      )

      withServer(endpoints) { baseUrl =>
        val response = requests.post(
          url = s"$baseUrl/echo",
          data = "Hello, Keg!"
        )
        assert(response.statusCode == 200)
        assert(response.text() == "Hello, Keg!")
        assert(response.headers.get("x-pong").contains(Seq("no ping")))

        val responseWithHeader = requests.post(
          url = s"$baseUrl/echo",
          headers = Map("x-ping" -> "ping-value")
        )
        assert(responseWithHeader.statusCode == 200)
        assert(responseWithHeader.text() == "")
        assert(responseWithHeader.headers.get("x-pong").contains(Seq("ping-value")))

        val responseWithStatus = requests.post(
          url = s"$baseUrl/echo",
          headers = Map("x-status" -> "201")
        )
        assert(responseWithStatus.statusCode == 201)
      }

    test("cookies"):
      val endpoints = Seq(
        keg.Endpoint(
          selector = keg.EndpointSelector.Simple("/cookies", Seq("GET")),
          invoke = (req: keg.Request) =>
            keg.Response(
              data = "Cookies!",
              cookies = Seq(
                keg.Cookie("session_id", "abc123", path = "/"),
                keg.Cookie("theme", "dark", path = "/")
              )
            )
        )
      )

      withServer(endpoints) { baseUrl =>
        val response = requests.get(s"$baseUrl/cookies")
        assert(response.statusCode == 200)
        assert(response.text() == "Cookies!")
        val setCookieHeaders = response.headers.get("set-cookie").getOrElse(Seq())
        assert(setCookieHeaders.exists(_.startsWith("session_id=abc123")))
        assert(setCookieHeaders.exists(_.startsWith("theme=dark")))
      }

    test("session"):
      val endpoints = Seq(
        keg.Endpoint(
          selector = keg.EndpointSelector.Simple("/set-session", Seq("GET")),
          invoke = (req: keg.Request) =>
            keg.Response(
              data = "Session Set!",
              session = Map("session_id" -> "xyz789")
            )
        ),
        keg.Endpoint(
          selector = keg.EndpointSelector.Simple("/get-session", Seq("GET")),
          invoke = (req: keg.Request) => {
            val sessionId = req.session.get("session_id").getOrElse("no-session")
            keg.Response(
              data = s"Session ID: $sessionId"
            )
          }
        )
      )

      withServer(endpoints): baseUrl =>
        val sess = requests.Session()

        val initialResponse = sess.get(s"$baseUrl/get-session")
        assert(initialResponse.statusCode == 200)
        assert(initialResponse.text() == "Session ID: no-session")

        val setResponse = sess.get(s"$baseUrl/set-session")
        assert(setResponse.statusCode == 200)

        val getResponse = sess.get(s"$baseUrl/get-session")
        assert(getResponse.statusCode == 200)
        assert(getResponse.text() == "Session ID: xyz789")

        val getResponseNoSession = requests.get(s"$baseUrl/get-session")
        assert(getResponseNoSession.statusCode == 200)
        assert(getResponseNoSession.text() == "Session ID: no-session")

    test("flash"):
      val endpoints = Seq(
        keg.Endpoint(
          selector = keg.EndpointSelector.Simple("/set-flash", Seq("GET")),
          invoke = (req: keg.Request) =>
            keg.Response(
              data = "Flash Set!",
              flash = Map("info" -> "This is a flash message")
            )
        ),
        keg.Endpoint(
          selector = keg.EndpointSelector.Simple("/get-flash", Seq("GET")),
          invoke = (req: keg.Request) => {
            val flashMsg = req.flash.get("info").getOrElse("no-flash")
            keg.Response(
              data = s"Flash Message: $flashMsg"
            )
          }
        ),
        keg.Endpoint(
          selector = keg.EndpointSelector.Simple("/redirect", Seq("GET")),
          invoke = (req: keg.Request) =>
            keg.Response(
              (),
              status = 302,
              headers = Seq("Location" -> "/get-flash"),
              flash = null // preserve existing flash
            )
        )
      )

      withServer(endpoints): baseUrl =>
        val sess = requests.Session()

        val initialResponse = sess.get(s"$baseUrl/get-flash")
        assert(initialResponse.statusCode == 200)
        assert(initialResponse.text() == "Flash Message: no-flash")

        val setResponse = sess.get(s"$baseUrl/set-flash")
        assert(setResponse.statusCode == 200)

        val getResponse = sess.get(s"$baseUrl/get-flash")
        assert(getResponse.statusCode == 200)
        assert(getResponse.text() == "Flash Message: This is a flash message")

        val getResponseNoFlash = sess.get(s"$baseUrl/get-flash")
        assert(getResponseNoFlash.statusCode == 200)
        assert(getResponseNoFlash.text() == "Flash Message: no-flash")

        val sess2 = requests.Session()
        val redirectSetResponse = sess2.get(s"$baseUrl/set-flash")
        assert(redirectSetResponse.statusCode == 200)

        val redirectResponse = sess2.get(s"$baseUrl/redirect")
        assert(redirectResponse.statusCode == 200)
        assert(redirectResponse.text() == "Flash Message: This is a flash message")

    test("not found"):
      withServer(Seq()): baseUrl =>
        val response = requests.get(s"$baseUrl/unknown", check = false)
        assert(response.statusCode == 404)

    test("custom not found"):
      val endpoints = Seq(
        keg.Endpoint(
          selector = keg.EndpointSelector.NotFound,
          invoke = (req: keg.Request) =>
            keg.Response(
              data = "This is a custom 404 page",
              status = 404
            )
        )
      )

      withServer(endpoints): baseUrl =>
        val response = requests.get(s"$baseUrl/unknown", check = false)
        assert(response.statusCode == 404)
        assert(response.text() == "This is a custom 404 page")

    test("method not allowed"):
      val endpoints = Seq(
        keg.Endpoint(
          selector = keg.EndpointSelector.Simple("/hello", Seq("GET")),
          invoke = (req: keg.Request) =>
            keg.Response(
              "Hello, World!",
            )
        )
      )
      withServer(endpoints): baseUrl =>
        val response = requests.post(s"$baseUrl/hello", check = false)
        assert(response.statusCode == 405)

    test("custom method not allowed"):
      val endpoints = Seq(
        keg.Endpoint(
          selector = keg.EndpointSelector.MethodNotAllowed,
          invoke = (req: keg.Request) =>
            keg.Response(
              data = "This method is not allowed here",
              status = 405
            )
        ),
        keg.Endpoint(
          selector = keg.EndpointSelector.Simple("/hello", Seq("GET")),
          invoke = (req: keg.Request) =>
            keg.Response(
              "Hello, World!",
            )
        )
      )

      withServer(endpoints): baseUrl =>
        val response = requests.post(s"$baseUrl/hello", check = false)
        assert(response.statusCode == 405)
        assert(response.text() == "This method is not allowed here")
