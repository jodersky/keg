package keg.derivation

trait EndpointApi:

  trait EndpointAnnotation[Return] extends scala.annotation.Annotation:
    val path: String
    val methods: Seq[String]

  /** A way to convert  */
  trait FromPath[+R]:
    def fromSegment(segment: String): keg.dispatch.Result[R, String]

  given StringFromPath: FromPath[String] with
    def fromSegment(segment: String): keg.dispatch.Result[String, String] =
      keg.dispatch.Result.Success(segment)

  given IntFromPath: FromPath[Int] with
    def fromSegment(segment: String): keg.dispatch.Result[Int, String] =
      try
        keg.dispatch.Result.Success(segment.toInt)
      catch
        case _: NumberFormatException =>
          keg.dispatch.Result.Failure(400, s"Invalid integer: $segment")

  trait FromRequest[+R]:
    type Error
    def fromRequest(request: keg.model.Request): keg.dispatch.Result[R, Error]

  given RequestFromRequest: FromRequest[keg.model.Request] =
    (req) => keg.dispatch.Result.Success(req)

  // // read request into intermediate representation
  // trait InputReader[R]:
  //   def read(input: keg.model.Request): keg.dispatch.Result[Map[String, R], String]
  //   type FI[+A] <: FromInput[R, A]

  // trait FromInput[Input, +A]:
  //   def fromInput(input: Input): keg.dispatch.Result[A, String]

  def handleParamFailure(
    missing: Seq[String],
    invalid: Seq[(String, String)]
  ): keg.dispatch.Result[Any, Nothing] =
    val messages = collection.mutable.ListBuffer[String]()
    if missing.nonEmpty then
      messages += s"Missing parameters: ${missing.mkString(", ")}"
    if invalid.nonEmpty then
      val invMsgs = invalid.map { case (name, msg) => s"$name: $msg" }
      messages += s"Invalid parameters: ${invMsgs.mkString(", ")}"
    keg.dispatch.Result.Success(400, messages.mkString("; "))

  def handleFailure(code: Int, value: Any): keg.dispatch.Result[Any, Nothing] =
    keg.dispatch.Result.Success(400, s"Bad Request: $value")

  def handleException(exception: Throwable): keg.dispatch.Result[Any, Nothing] =
    keg.dispatch.Result.Exception(exception)

trait BaseApi extends EndpointApi:
  class request(
    val path: String,
    val methods: Seq[String] = Seq("GET")
  ) extends EndpointAnnotation[keg.model.Response.Raw]

  class get(
    val path: String,
  ) extends EndpointAnnotation[keg.model.Response.Raw]:
    val methods = Seq("GET")

  class post(
    val path: String,
  ) extends EndpointAnnotation[keg.model.Response.Raw]:
    val methods = Seq("POST")

  class put(
    val path: String,
  ) extends EndpointAnnotation[keg.model.Response.Raw]:
    val methods = Seq("PUT")

  class delete(
    val path: String,
  ) extends EndpointAnnotation[keg.model.Response.Raw]:
    val methods = Seq("DELETE")

  class patch(
    val path: String,
  ) extends EndpointAnnotation[keg.model.Response.Raw]:
    val methods = Seq("PATCH")

// trait Api:
//   type Request

//   // def unwrapRequest(???)
//   // def wrapResponse()

// type Mycfg = App & Api

// case class App(
//   secrets: Seq[String]
// ) extends BaseApp

// case class MyApp(config: os.Path) extends BaseApp

//   val secrets = ???

//   val app = My

//   val db = ???

//   override def secrets = ???
//   override def toRequest()
//   override def toResponse()

//   given FromRequest[Req] with
//     def fromRequest(request: Request): Result[Req] =
//       // extract Req from request
//       Result.Ok(Req())

//   given FromRequest[User] with
//     def fromRequest(request: Request): Result[User] =
//       // extract user from request
//       Result.Ok(User("john_doe"))

//   given FromRequest[Db] with
//     def fromRequest(request: Request): Result[Db] =
//       // extract db from request
//       Result.Ok(Db("my_database"))



// // trait BaseApp:

// //   class get()


// class Routes1(app: keg.App):

//   @app.get()
//   def hello() = "Hello, World!"

//   val routes = keg.deriveRoutes()

// def main() =
//   val app = MyApp("secret")
//   val server = Server()
//   server.addRoutes(new Routes1(app).routes)
//   server.start()



// trait Mw:
//   trait FromRequest[+R]:
//     def fromRequest(request: Request): Result[R]

// @app.getJson("/hello/:id", body = Form)
