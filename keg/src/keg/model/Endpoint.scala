package keg.model

/** An endpoint represents a function that is called in response to an HTTP
  * request.
  *
  * Each endpoint has a *selector* that determines when the endpoint should be
  * invoked based on criteria from the incoming request:
  *
  * - Simple endpoints are matched by path and HTTP method.
  * - WebSocketUpgrade endpoints are matched by path and the presence of an
  *   `Upgrade: websocket` header.
  * - NotFound and MethodNotAllowed endpoints are special endpoints that are
  *   called when no other endpoint matches or when the method is not allowed,
  *   respectively
  *
  * The `invoke` function of the endpoint is called when the endpoint is
  * selected, and is passed the incoming request. It returns an
  * `EndpointResult`, which can be either a success (with a response) or a
  * failure. See `EndpointResult` for more details on the possible results and
  * how to handle them. In any case, the invoke function must never throw an
  * exception; any exceptions should be caught and returned as a failure result.
  *
  * The `meta` field contains metadata about the endpoint, such as the source
  * file and line number where the endpoint was defined. This is used when
  * reporting errors or exceptions that occur during endpoint invocation, to
  * help the developer identify the source of the problem.
  *
  * An endpoint is one of the core building blocks of a Keg web application,
  * serving as the main interface between the user-level annotated code and the
  * "main routing" layer. While you can build endpoints manually, it is more
  * common to use the provided macros (e.g. `@get`, `@post`, etc) to define
  * endpoints in a more concise and user-friendly way.
  *
  * @see `Recovery` on how to handle errors and exceptions that occur during
  * endpoint invocation
  *
  * @param selector
  *   The selector that determines when this endpoint should be invoked based on
  *   the incoming request.
  * @param invoke
  *   The function that is called when the endpoint is invoked.
  * @param meta
  *   Metadata about the endpoint, such as the source file and line number where
  *   the endpoint was defined.
  *
  */
case class Endpoint(
  selector: EndpointSelector,
  invoke: Request => EndpointResult,
  meta: EndpointMeta
)

object Endpoint:

  def apply(selector: EndpointSelector, invoke: Request => EndpointResult)(using pos: keg.util.Pos): Endpoint =
    Endpoint(
      selector,
      invoke,
      EndpointMeta(
        file = pos.file,
        line = pos.line,
        column = pos.column,
        function = pos.function
      )
    )

enum EndpointSelector:
  case Simple(path: String, methods: Seq[String])
  case WebSocketUpgrade(path: String)
  case NotFound
  case MethodNotAllowed

/** Return value from an endpoint invocation. */
sealed trait EndpointResult
object EndpointResult:

  /** A successful result from an endpoint invocation, containing either a HTTP
    * response or a WebSocket response which will be returned to the client.
    *
    * Note that a successful result does not necessarily mean that a "success"
    * in business logic terms; it only means that the endpoint was able to
    * process the request and generate a response without any errors or
    * exceptions. The response itself may indicate an error (e.g. by having a
    * 4xx or 5xx status code), but as far as the endpoint invocation is
    * concerned, it is still a successful result since it was able to handle the
    * request and produce a response.
    */
  case class Success(response: Response[Data] | WsResponse) extends EndpointResult

  /** The endpoint invocation failed. Sub types represent different failure scenarios. */
  sealed trait Failure extends EndpointResult
  object Failure:

    /** The endpoint invocation failed due to an error extracting a value for a
      * parameter (scala method parameter, not HTTP).
      *
      * @param param The name of the parameter that failed to be extracted.
      * @param code The suggested HTTP status code to return to the client (e.g. 400 for bad request).
      * @param message A message describing the reason for the failure.
      */
    case class ParamAbort(param: String, code: Int, message: String) extends Failure

    /** The endpoint invocation failed due to an explicit refusal in the scala
      * method implementing the invocation. E.g. a 403 forbidden error returned
      * from the method.
      *
      * @param code The suggested HTTP status code to return to the client.
      * @param message A message describing the reason for the failure.
      */
    case class MethodAbort(code: Int, message: String) extends Failure

    /** The endpoint invocation failed due to an exception being thrown during
      * the execution of the scala method implementing the invocation.
      *
      * @param throwable The exception that was thrown.
      */
    case class Exception(throwable: Throwable) extends Failure

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
  invoke: (Request, Endpoint, EndpointResult.Failure) => Response[Data]
)

/** A route is grouping of endpoints and metadata such as the generated paths.
  *
  * It is intended to be instantiated by the `routes()` macro, which will refine
  * the `paths` member to contain the actual path methods, which can then be
  * used to generate URLs to the endpoints in a type-safe way.
  */
trait Routes:
  def endpoints: Seq[Endpoint]
  def paths: Paths

/** This will be refined by macros to contain the actual paths.
  *
  * E..g. for route
  *
  * ```
  * @get("/users/:id")
  * def getUser(id: String)
  * ```
  *
  * it will contain a method `getUser(id: String): String` that generates the
  * path `/users/{id}`.
  */
trait Paths
