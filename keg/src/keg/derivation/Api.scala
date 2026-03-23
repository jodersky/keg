package keg.derivation

import keg.model.Request
import keg.model.Response
import keg.model.WsResponse
import keg.model.WebResponse
import keg.model.Data
import java.io.InputStream


// case class HttpException(code: Int, message: String) extends Exception(message)


// @api.error(404)
// def handleError

// @api.aborted()
// def handleAbort(code: Int, message: String) = ???

// @api.error()
// def foo(ex: Exception) = ???

// enum EpRes:
//   case Success(response: WebResponse)

//   // invoking the endpoint produced an exception, which should be handled
//   case Exception(ex: Throwable)

//   case InvalidArguments(errors: Seq[InvalidArgumentError])

// case class InvalidArgumentError(param: String, message: String)



// enum Ep:
//   /** An endpoint representing a simple HTTP route, selected by its path and methods. */
//   case Simple(path: String, methods: Seq[String], invoke: (Request) => WebResponse)

//   /** An endpoint representing an upgrade to a WebSocket connection. */
//   case Websocket(path: String, invoke: (Request) => WebResponse)

//   /** An endpoint representing an error handler for a specific HTTP status code. */
//   case Aborted(invoke: (Request, Int, String) => WebResponse)

//   /** Endpoint called if no other endpoint matches the request. */
//   case NotFound(invoke: Request => WebResponse)

//   /** Endpoint called if the request method is not allowed. */
//   case MethodNotAllowed(invoke: Request => WebResponse)

//   /** Endpoint called if an error occurs while processing the request. */
//   case Error(handler: (Request, Exception) => WebResponse)

/** */
enum Result[+A]:

  /** Continue processing the request, returning a value of type `A`. */
  case Continue(value: A)

  /** The parameter could not be read for some reason. */
  case Abort(code: Int, message: String)

  /** Respond immediately with the given response. */
  case Respond(response: WebResponse)

trait BaseApi:

  /** A type representing a "shared" context between all `FromRequest`s.
    * Essentially, this type will be instantiated once per request, and will
    * then be passed to any `FromRequest` instances.
    *
    * The context type is intended to be used to store data accessed by all
    * endpoints, specifically data which is expensive to compute and likely
    * accessed by many `FromRequest`s per endpoint. Example: user authentication
    * information.
    *
    * On a logical level, the context type sits between interceptors and
    * individual `FromRequest`.
    */
  type Context

  /** Create a context from a request.
    *
    * This method is called by the endpoint macro, and is intended to be
    * implemented by the user.
    */
  def parseContext(request: Request): Context

  /** Type class for extracting values from a path segment. */
  trait FromPath[+A]:
    def fromPath(path: String, ctx: Context): Result[A]

  /** Type class for extracting values from a request.
    *
    * The implementation can either return a value of type A, or an early
    * response. For example, you can use this to validate input or return a 400
    * response, or perform authentication and redirect if the user is not logged
    * in.
    *
    * A `FromRequest` instance is invoked for every parameter of an `@route`
    * annotated method, in the order of the parameters. The `Context` returned
    * by `parseContext` is passed to each `FromRequest`, so it can be used to
    * share data between `FromRequest`s without needing to re-parse the same
    * data multiple times.
    *
    * Note that a `FromRequest` can extract a value from the request's metadata
    * (e.g., headers, query parameters, path, cookies, uri, etc.) or from the
    * request's body. Since the body is a stream, it only makes sense to have
    * one `FromRequest` which extracts data from the body, however there is
    * currently no way to enforce this statically at compile time.
    */
  trait FromRequest[+A]:

    /** Extract a value of type `A` from the request.
      *
      * Note: generic FromRequests (such as those found in code keg code),
      * should never return a `Respond` result, since that would not allow end
      * users to customize the rendering of error responses. Instead, they
      * should return an `Abort`. `Respond` is only intended to be used in user
      * code.
      */
    def fromRequest(req: Request, ctx: Context): Result[A]

  /** Type class for converting values to a response. */
  trait ToResponse[-A]:
    def toResponse(value: A, ctx: Context): keg.model.WebResponse

  trait EndpointAnnotation extends scala.annotation.StaticAnnotation

  /** An annotation for marking endpoint methods.
    *
    * Each method annotated with this will be turned into a "simple" endpoint by
    * the `route()` macro, using path and methods as selector. The macro will
    * generate code to extract the parameters of the method using `FromRequest`
    * instances, and to convert the return value of the method to a response
    * using a `ToResponse` instance.
    *
    * Note that a concrete class extending this trait MUST declare a `path:
    * String` argument as the first argument. This is necessary for the
    * `route()` macro to work correctly.
    */
  class request(val path: String, val methods: Seq[String]) extends EndpointAnnotation
  class get(path: String) extends request(path, Seq("GET"))
  class post(path: String) extends request(path, Seq("POST"))
  class put(path: String) extends request(path, Seq("PUT"))
  class delete(path: String) extends request(path, Seq("DELETE"))
  class patch(path: String) extends request(path, Seq("PATCH"))

  /** An annotation for marking websocket endpoint methods. This is the same as
    * `EndpointAnnotation`, but will generate an endpoint using the "websocket"
    * selector, which means the endpoint will only be invoked for websocket
    * upgrade requests.
    */
  class websocket(path: String) extends EndpointAnnotation

  class notFound() extends EndpointAnnotation
  class methodNotAllowed() extends EndpointAnnotation

  class error() extends scala.annotation.StaticAnnotation

trait StandardFroms extends BaseApi:

  given StringFromPath: FromPath[String] with
    def fromPath(path: String, ctx: Context): Result[String] = Result.Continue(path)

  given ByteFromPath: FromPath[Byte] with
    def fromPath(path: String, ctx: Context): Result[Byte] =
      try Result.Continue(path.toByte)
      catch case _: NumberFormatException => Result.Abort(400, "Invalid byte in path")

  given ShortFromPath: FromPath[Short] with
    def fromPath(path: String, ctx: Context): Result[Short] =
      try Result.Continue(path.toShort)
      catch case _: NumberFormatException => Result.Abort(400, "Invalid short integer in path")

  given IntFromPath: FromPath[Int] with
    def fromPath(path: String, ctx: Context): Result[Int] =
      try Result.Continue(path.toInt)
      catch case _: NumberFormatException => Result.Abort(400, "Invalid integer in path")

  given LongFromPath: FromPath[Long] with
    def fromPath(path: String, ctx: Context): Result[Long] =
      try Result.Continue(path.toLong)
      catch case _: NumberFormatException => Result.Abort(400, "Invalid long integer in path")

  given FloatFromPath: FromPath[Float] with
    def fromPath(path: String, ctx: Context): Result[Float] =
      try Result.Continue(path.toFloat)
      catch case _: NumberFormatException => Result.Abort(400, "Invalid float in path")

  given DoubleFromPath: FromPath[Double] with
    def fromPath(path: String, ctx: Context): Result[Double] =
      try Result.Continue(path.toDouble)
      catch case _: NumberFormatException => Result.Abort(400, "Invalid double in path")

  given BooleanFromPath: FromPath[Boolean] with
    def fromPath(path: String, ctx: Context): Result[Boolean] =
      path.toLowerCase() match
        case "true"  => Result.Continue(true)
        case "false" => Result.Continue(false)
        case _       => Result.Abort(400, "Invalid boolean in path")

  given UUIDFromPath: FromPath[java.util.UUID] with
    def fromPath(path: String, ctx: Context): Result[java.util.UUID] =
      try Result.Continue(java.util.UUID.fromString(path))
      catch case _: IllegalArgumentException => Result.Abort(400, "Invalid UUID in path")

  given RequestFromRequest: FromRequest[Request] with
    def fromRequest(req: Request, ctx: Context): Result[Request] = Result.Continue(req)

  given ContextFromRequest: FromRequest[Context] with
    def fromRequest(req: Request, ctx: Context): Result[Context] = Result.Continue(ctx)

  given FormFromRequest: FromRequest[keg.forms.FormData] with
    def fromRequest(req: Request, ctx: Context): Result[keg.forms.FormData] =
      val visitor = new keg.forms.FormDataVisitor()

      keg.forms.FormParser.tryParse(
        req.getBody(),
        "application/x-www-form-urlencoded", // TODO: get actual content type from request header
        visitor,
        maxSize = 30*1024, // 30 KB
        maxFileSize = 100*1024*1024 // 100 MB
      ) match
        case Left(_: keg.forms.FormParser.FormTooLargeException) =>
          Result.Abort(413, "Form part is too large")
        case Left(_: keg.forms.FormParser.InvalidFormEncodingException) =>
          Result.Abort(400, "Form data is malformed")
        case Right(data)    =>
          Result.Continue(data)

  given StringFromRequest: FromRequest[String] with
    def fromRequest(req: Request, ctx: Context): Result[String] =
      try
        val bytes = req.getBody().readAllBytes()
        Result.Continue(new String(bytes, java.nio.charset.StandardCharsets.UTF_8))
      catch case _: Exception => Result.Abort(400, "Request body is not valid UTF-8")

  given ByteArrayFromRequest: FromRequest[Array[Byte]] with
    def fromRequest(req: Request, ctx: Context): Result[Array[Byte]] =
      try Result.Continue(req.getBody().readAllBytes())
      catch case _: Exception => Result.Abort(400, "Invalid request body")

  given ReadableFromRequest: FromRequest[geny.Readable] with
    def fromRequest(req: Request, ctx: Context): Result[geny.Readable] =
      val readable = new geny.Readable:
        def readBytesThrough[T](f: InputStream => T): T = f(req.getBody())
      Result.Continue(readable)

  given InputStreamFromRequest: FromRequest[InputStream] with
    def fromRequest(req: Request, ctx: Context): Result[InputStream] =
      try Result.Continue(req.getBody())
      catch case _: Exception => Result.Abort(400, "Invalid request body")

  given dataToResponse: ToResponse[Data] with
    def toResponse(data: Data, ctx: Context): Response[Data] = Response(data)

  given dataToDataToResponse[A](using c: Conversion[A, Data]): ToResponse[A] with
    def toResponse(data: A, ctx: Context): Response[Data] = Response(c(data))

  given dataResponseToResponse[A](using c: Conversion[A, Data]): ToResponse[Response[A]] with
    def toResponse(response: Response[A], ctx: Context): Response[Data] =
      response.copy(data = c(response.data))

  given responseToResponse: ToResponse[Response[Data]] with
    def toResponse(response: Response[Data], ctx: Context): WebResponse = response

  given wsToResponse: ToResponse[WsResponse] with
    def toResponse(ws: WsResponse, ctx: Context) = ws

trait ExtraFroms extends StandardFroms:


  given jsonFromRequest[A](using upickle.default.CaseClassReader[A]): FromRequest[A] with
    def fromRequest(req: Request, ctx: Context): Result[A] =
      try
        val obj = upickle.default.read[A](req.body)
        Result.Continue(obj)
      catch
        case e: upickle.core.AbortException =>
          Result.Abort(400, s"Invalid JSON: ${e.getMessage}")
        case e: Exception =>
          Result.Abort(400, s"Error reading request body: ${e.getMessage}")

  given jsonToResponse[A](using upickle.default.CaseClassWriter[A]): ToResponse[A] with
    def toResponse(value: A, ctx: Context): Response[Data] =
      val jsonData = new Data:
        override def write(output: java.io.OutputStream): Unit =
          upickle.default.stream(value).writeBytesTo(output)

        override def headers: Seq[(String, String)] = Seq(
          "Content-Type" -> "application/json; charset=UTF-8"
        )
      Response(jsonData)

trait Api extends ExtraFroms:
  type Context = Unit
  def parseContext(request: Request): Context = ()

// trait Api2 extends BaseApi2:

//   given RequestFromRequest: FromRequest[Request] with
//     def fromRequest(req: Request, ctx: Context): Result[Request] = Result.Continue(req)



// case class User(id: String, name: String)

// trait Custom extends YoApi:
//   override type Context = User
//   override def parseContext(request: Request): Context =
//     // In a real app, you would extract user info from the request (e.g., from a token)
//     User(id = "123", name = "Alice")


//   given UserFromRequest: FromRequest[User] with
//     def fromRequest(req: Request, ctx: Context): Result[User] =
//       Result.Continue(ctx) // no re-parsing needed since we already have the user in the context

//   summon[FromRequest[Request]]




// /*
// @app.get("/:org/:timeline", authz = "timeline:<timeline>#viewer@<user>")
// def getTimeline(org: String, timeline: String, user: User, authzInfo: AuthzInfo): keg.Response =
//   authzInfo.checkPermission(s"timeline:$timeline#admin@${user.id}") // then also show settings
//   ...


// class AdminAccess
// class ViewAccess
// class EditAccess
// class PublicAccess

// case class CanAdminTimeline(timelineId: String)
// case class CanEditTimeline(timelineId: String)
// case class CanViewTimeline(timelineId: String)

// given FromRequest[CanAdminTimeline] with
//   def fromRequest(req: Request, ctx: Context): Result[CanAdminTimeline] =
//     val user = ??? // authenticate(req)
//     val timelineId = ??? // extract from path or query

//     if checkAdminAccess("timeline", timelineId, "user", user) then
//       Result.Continue(CanAdminTimeline(timelineId))
//     else
//       Result.Respond(Response(403, data = "Forbidden"))

// class get(path: String, authz: (String, String)) extends EndpointAnnotation:
//   def wrap(req: Request, next: (Context, Request) => keg.model.WebResponse): keg.model.WebResponse =
//     val user = authenticate(req) // get user from request (e.g., from a token)
//     val authzInfo = authorize(req, authz, user)

//     next(parseContext(req), req)

// @app.get("/:org/:timeline")
// def getBuild(org: String, timeline: String, authz: CanEditTimeline) =
//   val isAdmin = authorize(org, timeline, "timeline", "admin")
//   tabs(
//     ...,
//     if isAdmin then tab("Settings", showSettings(tlId)) else None
//   )

//   timelineService.getTimeline(tlId)



//   authorize(org, timeline, "timeline", "viewer"): tl =>
//     // If the user has viewer access, they can see the timeline
//     showTimeline(tl)
//     ???

// */
