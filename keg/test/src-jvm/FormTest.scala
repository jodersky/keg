import utest.*

object FormTest extends TestSuite:

  def withServer(endpoints: Seq[keg.Endpoint | keg.Recovery])(fn: String => Unit): Unit =
    val server = keg.Server(
      endpoints = endpoints,
      listeners = Seq(keg.Listener.Http("::1", 0)),
      debugMode = true
    )
    server.start()
    try
      fn(s"http://[::1]:${server.serverPort()}")
    finally
      server.stop()

  val tests = Tests:
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

    test("multipart"):
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
          data = requests.MultiPart(
            requests.MultiItem("name", "Bob"),
            requests.MultiItem("value", "42")
          )
        )
        assert(response.statusCode == 200)
        assert(response.text() == "Hello, Bob! Value is: 42")

    test("simple multipart reject upload"):
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
          data = requests.MultiPart(
            requests.MultiItem("name", "Bob"),
            requests.MultiItem("value", "42"),
            requests.MultiItem("file", "This should not be accepted.", filename = "test.txt")
          ),
          check = false
        )
        assert(response.statusCode == 400)

    test("file upload"):
      object app:
        @keg.post("/submit")
        def submitForm(form: keg.model.UploadFormData): keg.Response[keg.Data] =
          val name = form.fields("name")
          val file = form.files("file")
          val content = java.nio.file.Files.readString(file.path)
          keg.Response(s"Hello, $name! File content is: $content")

        val routes = keg.routes()

      withServer(app.routes.endpoints): url =>
        val response = requests.post(
          url = s"$url/submit",
          data = requests.MultiPart(
            requests.MultiItem("name", "Charlie"),
            requests.MultiItem("file", "This is the file content.", filename = "test.txt")
          )
        )
        assert(response.statusCode == 200)
        assert(response.text() == "Hello, Charlie! File content is: This is the file content.")
