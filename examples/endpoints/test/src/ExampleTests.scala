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

        val r1 = requests.get(s"$baseUrl/")
        assert(r1.statusCode == 200)
        assert(r1.text() == "Hello, World!")

        val r2 = requests.post(s"$baseUrl/echo", data = "Test")
        assert(r2.statusCode == 200)
        assert(r2.text() == "Echo: Test")

        val r3 = requests.get(s"$baseUrl/fail", check = false)
        assert(r3.statusCode == 500)
        assert(r3.text() == "Custom error page: MethodAbort(418,I'm a teapot)")

        val r4 = requests.get(s"$baseUrl/nonexistent", check = false)
        assert(r4.statusCode == 404)
        assert(r4.text() == "Custom 404 Not Found")
