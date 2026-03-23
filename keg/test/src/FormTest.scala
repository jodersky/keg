import utest.*

object FormTest extends TestSuite:

  def withServer(endpoints: Seq[keg.Endpoint])(fn: String => Unit): Unit =
    val server = keg.Server(
      endpoints = endpoints,
      port = 0,
      debugMode = true
    )
    server.start()
    try
      fn(s"http://localhost:${server.serverPort()}")
    finally
      server.stop()

  val tests = Tests {
    test("basic form derivation"):
      object app:
        @keg.post("/submit")
        def submitForm(form: keg.forms.FormData): keg.Response[keg.Data] =
          val name = form.fields("name")
          val value = form.fields("value")
          keg.Response(s"Hello, $name! Value is: $value")

        val routes = keg.routes()

      withServer(app.routes.endpoints): url =>
        val response = requests.post(
          url = s"$url/submit",
          data = Map("name" -> "Alice", "value" -> "42")
        )
        assert(response.statusCode == 200)
        assert(response.text() == "Hello, Alice! Value is: 42")
  }
