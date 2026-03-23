import utest.*

object ExampleTests extends TestSuite:

  def withServer[T](server: keg.main.Server)(f: String => T): T = {
    server.start()
    val res =
      try f("http://localhost:8080")
      finally server.stop()
    res
  }

  val tests = Tests:
    test("Endpoints"):
      withServer(app.server): baseUrl =>
        val response = requests.post(
          url = s"$baseUrl/form",
          data = Map("name" -> "Alice", "value" -> "42")
        )
        assert(response.statusCode == 200)
        assert(response.text() == "Hello, Alice! Value is: 42")
