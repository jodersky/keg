package keg.example

val server = keg.Server(
  endpoints = Seq(
    keg.model.Endpoint(
      selector = keg.model.EndpointSelector.Simple("/hello", Seq("POST")),
      invoke = req =>
        val data = req.body.readAllBytes()

        val foo = ujson.Obj(
          "message" -> new String(data),
          "length" -> data.length
        )

        keg.Response(
          data = foo,
          status = 200,
          headers = Seq("x-foo" -> "bar")
        )
    ),
    keg.model.Endpoint(
      selector= keg.model.EndpointSelector.Simple(
        path = "/hello",
        methods = Seq("GET")
      ),
      invoke = req =>
        sys.error("failed!")
        ujson.Obj(
          "message" -> "Hello, World!",
          "method" -> req.method
        )
    ),
    keg.model.Endpoint(
      selector= keg.model.EndpointSelector.Simple(
        path = "/set",
        methods = Seq("GET")
      ),
      invoke = req =>
        keg.Response(
          data = "Goodbye, World!",
          status = 200,
          session = Map("user" -> "john_doe"),
          flash = Map("info" -> "Welcome back!")
        )
    ),
    keg.model.Endpoint(
      selector= keg.model.EndpointSelector.Simple(
        path = "/get",
        methods = Seq("GET")
      ),
      invoke = req =>
        keg.Response(
          data = ujson.Obj(
            "session" -> ujson.Obj.from(req.session.mapValues(ujson.Str)),
            "flash" -> ujson.Obj.from(req.flash.mapValues(ujson.Str))
          )
        )
    ),
    keg.model.Endpoint(
      selector= keg.model.EndpointSelector.Simple(
        path = "/user/:id/profile",
        methods = Seq("GET")
      ),
      invoke = req =>
        val userId = req.pathParams.getOrElse("id", "unknown")
        keg.Response(
          data = ujson.Obj(
            "userId" -> userId,
            "profile" -> ujson.Obj(
              "name" -> "John Doe",
              "age" -> 30
            )
          )
        )
    )
  ) ++ cool.routes.endpoints,
  secrets = Seq(keg.Secret("hello"))
)

@main def runExampleServer(): Unit =
  println("Starting example server on http://localhost:8080")
  server.start()

object cool:

  @keg.get("/cool/:id")
  def coolEndpoint(id: Int, req: keg.Request): keg.Response =
    keg.Response(
      data = ujson.Obj(
        "message" -> s"You're so cool, user #$id!",
        // "method" -> req.method,
        "req" -> req.toString()
      )
    )

  case class User(name: String, age: Int = 5) derives upickle.default.ReadWriter

  @keg.post("/cool/:id/2")
  def coolEndpoint2(id: Int, user: User): keg.Response =
    keg.Response(
      data = ujson.Obj(
        "message" -> s"User #$id is ${user.name}, age ${user.age}"
      )
    )

  val routes = keg.routes()

  println(
    scala.compiletime.codeOf(keg.routes())
  )

// object withApp:
//   case class User(name: String, age: Int)
//   class MyApp extends keg.derivation.Api:
//     given userFromRequest: FromRequest[User] with
//       def fromRequest(request: keg.Request): keg.derivation.Result[User] =
//         keg.derivation.Result.Continue(User("Alice", 25))

//   val app = new MyApp()

//   @app.get("/app")
//   def appEndpoint(user: User): String =
//     s"Hello, ${user.name}! You are ${user.age} years old."

//   val routes = keg.routes()
