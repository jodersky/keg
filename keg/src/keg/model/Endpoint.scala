package keg.model

/** Common sealed base for all route entries (endpoints and recovery handlers). */
sealed trait Route

/** An endpoint represents a function that is called in response to an HTTP
  * request.
  *
  * The function of an endpoint is passed the request and should return a
  * response. The function may also throw, which will cause a recovery to be
  * invoked. By convention, `AbortException` should be thrown when the
  * application code wished to respond with a specific kind of HTTP error.v
  *
  * The `meta` field contains metadata about the endpoint, such as the source
  * file and line number where the endpoint was defined. This is used when
  * reporting errors or exceptions that occur during endpoint invocation, to
  * help the developer identify the source of the problem.
  *
  * An endpoint is one of the core building blocks of a Keg web application,
  * serving as the main interface between the user-level annotated code and the
  * main routing layer. While you can build endpoints manually, it is more
  * common to use the annotations (e.g. `@get`, `@post` etc) in conjunction with
  * the `keg.routes()` macro to define endpoints in a more concise and
  * user-friendly way.
  *
  * @see `Recovery` on how to handle errors and exceptions that occur during
  * endpoint invocation
  *
  * @param invoke The function that is called when the endpoint is invoked.
  * @param meta Metadata about the endpoint, such as the source file and line
  *   number where the endpoint was defined.
  */
case class Endpoint(path: String, methods: Iterable[String], invoke: Request => RawResponse, meta: EndpointMeta) extends Route
object Endpoint:
  def apply(path: String, methods: Iterable[String] = Seq("GET"), invoke: Request => RawResponse)(using pos: keg.util.Pos): Endpoint =
    Endpoint(path, methods, invoke, EndpointMeta())

case class EndpointMeta(
  file: String,
  line: Int,
  column: Int,
  function: String
):
  override def toString: String =
    s"$function ($file:$line:$column)"

object EndpointMeta:
  def apply()(using pos: keg.util.Pos): EndpointMeta =
    EndpointMeta(
      file = pos.file,
      line = pos.line,
      column = pos.column,
      function = pos.function
    )

/** A recovery handler is responsible for generating a response when an endpoint
  * invocation fails. The server will call the recovery handler's `invoke`
  * function when an endpoint invocation returns a failure result, passing in
  * the original request, the endpoint that was invoked, and the failure result.
  *
  * The `invoke` function should return a response to be sent to the client.
  * This allows you to customize the error handling logic for your application,
  * and to provide more informative error messages or responses to the client
  * when something goes wrong during endpoint invocation.
  *
  * @param invoke The function that is called when an endpoint invocation fails.
  * It takes the original request, the endpoint that was invoked, and the
  * failure result, and returns a response to be sent to the client.
  */
case class Recovery(
  invoke: (Request, Throwable, Option[Endpoint]) => RawResponse
) extends Route
