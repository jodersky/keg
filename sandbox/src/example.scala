
@keg.get("/")
def index() =
  val html =
    s"""|<!DOCTYPE html>
        |<html>
        |<head>
        |  <title>Keg Example</title>
        |</head>
        |<body>
        |  <h1>Welcome to the Keg Example Server!</h1>
        |  <p>Try accessing <a href="/hello?name=World">/hello?name=World</a></p>
        |
        |</body>
        |</html>
        |""".stripMargin

  keg.Response(
    data = html,
    headers = Seq("content-type" -> "text/html; charset=UTF-8")
  )

@keg.get("/index2")
def index2() =
  import scalatags.Text.all.*

  val result = html(
    head(
    ),
    body(
      h1("Welcome to the Keg Example Server!"),
      button("Click Me"),
      p("Try accessing ", a(href := "/hello?name=World")("/hello?name=World"))
    )
  )

  keg.Response(
    data = doctype("html")(result),
    // headers = Seq("content-type" -> "text/html; charset=UTF-8")
  )

@keg.notFound()
def notFoundHandler(request: keg.model.Request) =
  keg.Response(
    status = 404,
    data = s"404 Not Found: ${request.path}",
    headers = Seq("content-type" -> "text/plain; charset=UTF-8")
  )

val routes = keg.routes()

@main def runExampleServer(): Unit =
  println("Starting example server on http://localhost:8080")
  val server = keg.main.Server(routes.endpoints, debugMode = true)
  server.start()

  // while true do
  //   Thread.sleep(2000)
  //   keg.main.Reloader.reload()


  // server.start()
