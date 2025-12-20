package keg.derivation

import keg.model.Request
import keg.model.Response
import keg.model.WsResponse
import java.util.UUID

enum Result[+A]:
  case Continue(value: A)
  case Respond(response: Response)

trait BaseApi:

  trait FromPath[+A]:
    def fromPath(segment: String): Result[A]

  trait FromRequest[+A]:
    def fromRequest(request: Request): Result[A]

  trait ToResponse[-A]:
    def toResponse(value: A): (Response | WsResponse)

  given responseToResponse: ToResponse[Response] = response => response
  given wsResponseToResponse: ToResponse[WsResponse] = response => response

  given dataToResponse: ToResponse[keg.model.Data] with
    def toResponse(value: keg.model.Data): Response =
      Response(data = value, 200)

  given dataToDataToResponse[A](using c: Conversion[A, keg.model.Data]): ToResponse[A] with
    def toResponse(value: A): Response =
      Response(data = c(value), 200)

  // NOTE: classes extending this trait MUST declare a (path: String) argument
  // as first argument. This is necessary for the `route()` macro to work
  // correctly.
  trait EndpointAnnotation extends scala.annotation.Annotation:
    def path: String
    def methods: Seq[String]

  trait WebsocketAnnotation extends EndpointAnnotation

  trait NotFoundAnnotation extends EndpointAnnotation:
    def path: String = ""
    def methods: Seq[String] = Seq()
  trait MethodNotAllowedAnnotation extends EndpointAnnotation:
    def path: String = ""
    def methods: Seq[String] = Seq()
  trait CsrfErrorAnnotation extends EndpointAnnotation:
    def path: String = ""
    def methods: Seq[String] = Seq()

trait Api extends BaseApi:

  class request(val path: String, val methods: Seq[String]) extends EndpointAnnotation
  class get(path: String) extends request(path, Seq("GET"))
  class post(path: String) extends request(path, Seq("POST"))
  class put(path: String) extends request(path, Seq("PUT"))
  class delete(path: String) extends request(path, Seq("DELETE"))
  class patch(path: String) extends request(path, Seq("PATCH"))

  class websocket(val path: String) extends WebsocketAnnotation:
    val methods: Seq[String] = Seq("GET")

  class notFound() extends NotFoundAnnotation
  class methodNotAllowed() extends MethodNotAllowedAnnotation
  class csrfError() extends CsrfErrorAnnotation

  def badRequest(message: String): Response =
    Response("Bad Request " + message, 400)

  given StringFromPath: FromPath[String] = segment => Result.Continue(segment)
  given IntFromPath: FromPath[Int] = segment =>
    try
      Result.Continue(segment.toInt)
    catch
      case _: NumberFormatException =>
        Result.Respond(badRequest("Invalid integer in path"))
  given BooleanFromPath: FromPath[Boolean] = segment =>
    segment.toLowerCase() match
      case "true"  => Result.Continue(true)
      case "false" => Result.Continue(false)
      case _       => Result.Respond(badRequest("Invalid boolean in path"))
  given LongFromPath: FromPath[Long] = segment =>
    try
      Result.Continue(segment.toLong)
    catch
      case _: NumberFormatException =>
        Result.Respond(badRequest("Invalid long integer in path"))
  given UUIDFromPath: FromPath[UUID] = segment =>
    try
      Result.Continue(UUID.fromString(segment))
    catch
      case _: IllegalArgumentException =>
        Result.Respond(badRequest("Invalid UUID in path"))

  given RequestFromRequest: FromRequest[Request] = req => Result.Continue(req)
  given StringFromRequest: FromRequest[String] = req =>
    try
      val str = new String(req.body.readAllBytes(), "UTF-8")
      Result.Continue(str)
    catch
      case e: Exception =>
        Result.Respond(badRequest(s"Error reading request body: ${e.getMessage}"))

  given JsonFromRequest[A](using upickle.default.Reader[A]): FromRequest[A] with
    def fromRequest(request: Request): Result[A] =
      try
        // val str = new String(request.body.readAllBytes(), "UTF-8")
        val obj = upickle.default.read[A](request.body)
        Result.Continue(obj)
      catch
        // case e: upickle.core.AbortException =>
        //   Result.Respond(badRequest(s"Invalid JSON: ${e}"))
        case e: Exception =>
          Result.Respond(badRequest(s"Invalid JSON: ${e}"))


  // given toJsonResponse[A](using upickle.default.Writer[A]): ToResponse[A] with
  //   def toResponse(value: A): Response =
  //     val jsonData = new keg.model.Data:
  //       override def writeTo(output: java.io.OutputStream): Unit =
  //         upickle.default.stream(value).writeBytesTo(output)


  //     val jsonStr = upickle.default.write(value)
  //     Response(
  //       data = jsonStr,
  //       status = 200,
  //       headers = Seq("Content-Type" -> "application/json")
  //     )

  given UtilFormDataFromRequest: FromRequest[keg.forms.FormData] with
    def fromRequest(request: Request): Result[keg.forms.FormData] =
      try
        val headers = request.headers.mapValues(_.last).toMap
        val parts = keg.forms.FormParser.parse(request.getBody(), headers,
          keg.forms.FormParser.FileDest.Null
        )
        Result.Continue(keg.forms.FormData(parts))
      catch
        case e: keg.forms.FormParser.FormTooLargeException =>
          Result.Respond(badRequest(s"Form data too large: ${e.getMessage}"))
        case e: keg.forms.FormParser.InvalidFormEncodingException =>
          Result.Respond(badRequest(s"Invalid form encoding: ${e.getMessage}"))
        case e: keg.forms.FormParser.FormPartTooLargeException =>
          Result.Respond(badRequest(s"Form part too large: ${e.getMessage}"))
        case e: Exception =>
          Result.Respond(badRequest(s"Error parsing form data: ${e.getMessage}"))

  given FormFromRequest[A](using ff: keg.forms.FromForm[A]): FromRequest[keg.forms.Form[A]] with
    def fromRequest(request: Request): Result[keg.forms.Form[A]] =
      UtilFormDataFromRequest.fromRequest(request) match
        case Result.Respond(resp) => Result.Respond(resp)
        case Result.Continue(formData) =>
          Result.Continue(
            ff.parse(formData)
          )
