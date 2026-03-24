import utest.*

object ExampleTests extends TestSuite:

  def withServer[T](server0: keg.Server)(f: String => T): T =
    val server = server0.copy(listeners = List(keg.Listener.Http("::1", 0)))
    server.start()
    val port = server.serverPort()
    val res =
      try f(s"http://[::1]:$port")
      finally server.stop()
    res

  val tests = Tests:
    test("Endpoints"):
      withServer(app.server): baseUrl =>
        val response = requests.post(
          url = s"$baseUrl/form",
          data = Map("name" -> "Alice", "value" -> "42")
        )
        assert(response.statusCode == 200)
        assert(response.text() == "Hello, Alice! Value is: 42")
