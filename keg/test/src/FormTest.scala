import utest.*

object FormTest extends TestSuite:

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

  val tests = Tests {
    test("basic form derivation"):
      object app:
        @keg.post("/submit")
        def submitForm(form: keg.model.FormData): keg.Response =
          val name = form.entries.toMap.get("name").map(_.asInstanceOf[keg.model.FormEntry.Value])
            .map(_.value).getOrElse("unknown")
          val value = form.entries.toMap.get("value").map(_.asInstanceOf[keg.model.FormEntry.Value])
            .map(_.value).getOrElse("unknown")
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
