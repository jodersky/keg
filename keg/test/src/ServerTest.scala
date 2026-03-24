import utest.*

object ServerTest extends TestSuite:

  val tests = Tests:
    test("basic"):
      val endpoints = Seq(
        keg.Endpoint(
          selector = keg.EndpointSelector.Simple("/hello", Seq("GET")),
          invoke = (req: keg.Request) =>
            keg.EndpointResult.Success(
              keg.Response(
                "Hello, World!"
              )
            ),
          meta = keg.EndpointMeta()
        )
      )

      val server = keg.Server(
        endpoints = endpoints,
        listeners = Seq(keg.main.Listener.Http("::1", 8081)),
      )
      server.start()
      try
        val response = requests.get(s"http://[::1]:8081/hello")
        assert(response.statusCode == 200)
        assert(response.text() == "Hello, World!")
      finally
        server.stop()

    test("basic uhttp"):
      val endpoints = Seq(
        keg.Endpoint(
          selector = keg.EndpointSelector.Simple("/hello", Seq("GET")),
          invoke = (req: keg.Request) =>
            keg.EndpointResult.Success(
              keg.Response(
                "Hello, World!"
              )
            ),
          meta = keg.EndpointMeta()
        )
      )

      val server = keg.Server(
        endpoints = endpoints,
        listeners = Seq(keg.main.Listener.Http("::1", 8081)),
        transportBuilder = new keg.transport.uhttp.UhttpTransportBuilder
      )
      server.start()
      try
        val response = requests.get(s"http://[::1]:8081/hello")
        assert(response.statusCode == 200)
        assert(response.text() == "Hello, World!")
      finally
        server.stop()


    test("not found"):
      val server = keg.Server(
        endpoints = Seq(),
        listeners = Seq(keg.main.Listener.Http("::1", 8081)),
      )
      server.start()
      try
        val response = requests.get(s"http://[::1]:8081/unknown", check = false)
        assert(response.statusCode == 404)
      finally
        server.stop()

    test("method not allowed"):
      val endpoints = Seq(
        keg.Endpoint(
          selector = keg.EndpointSelector.Simple("/hello", Seq("GET")),
          invoke = (req: keg.Request) =>
            keg.EndpointResult.Success(
              keg.Response(
                "Hello, World!",
              )
            ),
          meta = keg.EndpointMeta()
        )
      )
      val server = keg.Server(
        endpoints = endpoints,
        listeners = Seq(keg.main.Listener.Http("::1", 8081)),
      )
      server.start()
      try
        val response = requests.post(s"http://[::1]:8081/hello", check = false)
        assert(response.statusCode == 405)
      finally
        server.stop()

    test("multiple endpoints"):
      val endpoints = Seq(
        keg.Endpoint(
          selector = keg.EndpointSelector.Simple("/hello", Seq("GET")),
          invoke = (req: keg.Request) =>
            keg.EndpointResult.Success(
              keg.Response("Hello, World!")
            ),
          meta = keg.EndpointMeta()
        ),
        keg.Endpoint(
          selector = keg.EndpointSelector.Simple("/goodbye", Seq("GET")),
          invoke = (req: keg.Request) =>
            keg.EndpointResult.Success(
              keg.Response("Goodbye, World!")
            ),
          meta = keg.EndpointMeta()
        ),
        keg.Endpoint(
          selector = keg.EndpointSelector.Simple("/all-methods", Seq("GET", "POST", "PUT", "DELETE", "PATCH")),
          invoke = (req: keg.Request) =>
            keg.EndpointResult.Success(
              keg.Response(s"Hello via ${req.method}!")
            ),
          meta = keg.EndpointMeta()
        )
      )

      val server = keg.Server(
        endpoints = endpoints,
        listeners = Seq(keg.main.Listener.Http("::1", 8081)),
      )
      server.start()
      try
        val response1 = requests.get(s"http://[::1]:8081/hello")
        assert(response1.statusCode == 200)
        assert(response1.text() == "Hello, World!")

        val response2 = requests.get(s"http://[::1]:8081/goodbye")
        assert(response2.statusCode == 200)
        assert(response2.text() == "Goodbye, World!")

        val methods = Seq("GET", "POST", "PUT", "DELETE", "PATCH")
        for method <- methods do
          val response = requests.send(method)(
            s"http://[::1]:8081/all-methods"
          )
          assert(response.statusCode == 200)
          assert(response.text() == s"Hello via $method!")
      finally
        server.stop()

