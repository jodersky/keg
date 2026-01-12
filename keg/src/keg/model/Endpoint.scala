package keg.model

/** An endpoint represents a function that is called at the end of an HTTP
  * request.
  *
  * All types of endpoint always have an invoke function that takes a Request
  * and returns a Response, which will be called by the server when the endpoint
  * is matched. The matching of the endpoint however depends on the type of
  * endpoint:
  *
  * - Simple endpoints are matched by path and HTTP method.
  * - WebSocketUpgrade endpoints are matched by path and the presence of an
  *   `Upgrade: websocket` header.
  * - NotFound, MethodNotAllowed, and CsrfError endpoints are special endpoints
  *   that are called when no other endpoint matches, when the method is not
  *   allowed, or when a CSRF check fails, respectively.
  *
  * An endpoint is one of the core building blocks of a Keg web application,
  * serving as the main interface between the user-level annotated code and the
  * "main routing" layer. While you can build endpoints manually, it is more
  * common to use the provided macros (e.g. `@get`, `@post`, etc) to define
  * endpoints in a more concise and user-friendly way.
  */
case class Endpoint(
  selector: EndpointSelector,
  invoke: Request => (Response | WsResponse), // TODO: also allow websocket response
  meta: EndpointMeta
)
object Endpoint:

  def apply(selector: EndpointSelector, invoke: Request => (Response | WsResponse))(using pos: keg.util.Pos): Endpoint =
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
  case CsrfError

case class EndpointMeta(
  file: String,
  line: Int,
  column: Int,
  function: String
):
  override def toString: String =
    s"$function ($file:$line:$column)"

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
