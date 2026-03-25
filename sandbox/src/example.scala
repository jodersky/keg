// import keg.forms.{Form, FormData}

// case class ChangePasswordRequest(
//     oldPassword: String,
//     newPassword: Int,
//     confirmNewPassword: String
// ) derives keg.forms.FromForm

// @keg.post("/change-password")
// def changePassword(form: keg.forms.Form[ChangePasswordRequest]): keg.Response =
//   println(form)
//   keg.Response(
//     data = s"Received form: $form",
//     status = 200
//   )

// @keg.get("/")
// def index() =
//   val html =
//     s"""|<!DOCTYPE html>
//         |<html>
//         |<head>
//         |  <title>Keg Example</title>
//         |</head>
//         |<body>
//         |  <h1>Welcome to the Keg Example Server!</h1>
//         |  <p>Try accessing <a href="/hello?name=World">/hello?name=World</a></p>
//         |  <form action="/submit" method="post" enctype="multipart/form-data">
//         |    <label for="name">Name:</label>
//         |    <input type="text" id="name" name="name"><br><br>
//         |    <label for="value">Value:</label>
//         |    <input type="text" id="value" name="email"><br><br>
//         |    <label for="file">File:</label>
//         |    <input type="file" id="file" name="file"><br><br>
//         |    <input type="checkbox" id="subscribe" name="subscribe">
//         |    <input type="submit" value="Submit">
//         |
//         |  </form>
//         |
//         |  <form action="/change-password" method="post">
//         |    <label for="username">Username:</label>
//         |    <input type="text" id="username" name="username"><br><br>
//         |    <label for="oldPassword">Old Password:</label>
//         |    <input type="password" id="oldPassword" name="oldPassword"><br><br>
//         |    <label for="newPassword">New Password:</label>
//         |    <input type="password" id="newPassword" name="newPassword"><br><br>
//         |    <label for="confirmNewPassword">Confirm New Password:</label>
//         |    <input type="password" id="confirmNewPassword" name="confirmNewPassword"><br><br>
//         |    <input type="submit" value="Change Password">
//         |    <input type="submit" value="submit">
//         |  </form>
//         |
//         |</body>
//         |</html>
//         |""".stripMargin

//   keg.Response(
//     data = html,
//     headers = Seq("content-type" -> "text/html; charset=UTF-8")
//   )

// @keg.post("/submit")
// def submitForm(req: keg.Request, data: keg.forms.FormData): keg.Response =
//   // val allData = req.body.readAllBytes()
//   // val bodyStr = new String(allData, "UTF-8")
//   // println(bodyStr)
//   keg.Response(
//     data = data.toString,
//     status = 200
//   )

//   //

// @keg.get("/index2")
// def index2() =
//   import scalatags.Text.all.*

//   val result = html(
//     head(
//     ),
//     body(
//       h1("Welcome to the Keg Example Server!"),
//       button("Click Me"),
//       p("Try accessing ", a(href := "/hello?name=World")("/hello?name=World"))
//     )
//   )

//   keg.Response(
//     data = doctype("html")(result),
//     // headers = Seq("content-type" -> "text/html; charset=UTF-8")
//   )

// @keg.notFound()
// def notFoundHandler(request: keg.model.Request) =
//   keg.Response(
//     status = 404,
//     data = s"404 Not Found: ${request.path}",
//     headers = Seq("content-type" -> "text/plain; charset=UTF-8")
//   )

// val icept = new keg.main.TimingInterceptor()

// @keg.get("/metrics")
// def metrics() =
//   keg.Response(
//     ustats.Registry.global,
//     200
//   )

// val routes = keg.routes()


// object CustomInterceptor extends keg.main.Interceptor:

//   override def intercept(
//     request: keg.model.Request,
//     endpoint: keg.model.Endpoint,
//     next: keg.model.Request => (keg.model.Response | keg.model.WsResponse)
//   ): keg.model.Response | keg.model.WsResponse =
//     println(s"Intercepting request to ${request.path}")
//     val response = next(request)
//     response match
//       case resp: keg.model.Response =>
//         println(s"Response status: ${resp.status}")
//         resp
//       case wsResp: keg.model.WsResponse =>
//         println(s"WebSocket response")
//         wsResp


// @main def runExampleServer(): Unit =
//   println("Starting example server on http://localhost:8080")
//   val server = keg.main.Server(routes.endpoints, debugMode = true, interceptors = Seq(icept))
//   server.start()

//   // while true do
//   //   Thread.sleep(2000)
//   //   keg.main.Reloader.reload()


//   // server.start()


// case class Foo(
//     namea: String,
//     age: Int
// ) derives keg.forms.FromForm2

// @main()
// def testFoo(): Unit =
//   summon[keg.forms.FromForm2[Foo]]




@keg.get("/echo/:n")
def foo(n: Int, x: String) =
  // println(s"Received n: $n, x: $x")
  s"Echo: n=$n, x=$x"

@keg.get("/")
def index() = "hello, world"

val plain = keg.Endpoint(
  selector = keg.EndpointSelector.Simple("/foo", Seq("GET")),
  invoke = (req: keg.model.Request) => keg.EndpointResult.Success(
    keg.Response(
      "ok"
    )
  )
)

val routes: keg.model.Routes = keg.routes()

@main()
def main() =
  // val key = java.nio.file.Files.readString(java.nio.file.Paths.get("key.pem"))
  // val cert = java.nio.file.Files.readString(java.nio.file.Paths.get("cert.pem"))

  val server = keg.Server(
    routes.endpoints ++ Seq(plain),
    // debugMode = true,
    interceptors = Seq(
      // keg.main.RateLimiter(),
      keg.extras.TimingInterceptor()
    ),
    listeners = Seq(
      keg.Listener.Http("::1", 8000),
      keg.Listener.Http("::1", 8081),
      // keg.Listener.Https("::1", 8443, key, cert)
    ),
    // transportBuilder = new keg.transport.uhttp.UhttpTransportBuilder
  )


  server.start()
