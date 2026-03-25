import utest.*

object ServerTest extends TestSuite:

  def withServer(f: String => Unit): Unit =
    val p = os.proc(sys.env("SERVER_EXECUTABLE")).spawn()
    Thread.sleep(1000) // Wait for the server to start
    try f("http://[::1]:8080")
    finally
      p.destroy()
      p.waitFor()

  val tests = Tests:
    test("index") - withServer: url =>
      val response = requests.get(s"$url/")
      assert(response.statusCode == 200)
      assert(response.text() == "Hello, World!")
    test("echo") - withServer: url =>
      val response = requests.post(s"$url/echo/42?q=hello", data = "world")
      assert(response.statusCode == 200)
      assert(response.text() == "Echo (param = 42, q = hello, body = world)")
