package keg.model

/** A HTTP request.
  * @param method
  *   The HTTP method (e.g. GET, POST, etc).
  * @param uri
  *   The full request URI, including query string.
  * @param headers
  *   The request headers.
  * @param pathParams
  *   The path parameters extracted from the URL. For example, for a route
  *   `/users/:id`, the path parameter `id` would be extracted here.
  * @param remainingPathSegments
  *   The remaining path segments not matched by the endpoint path. This is only
  *   relevant for wildcard routes (e.g. `/files/...`).
  * @param cookies
  *   The cookies sent with the request.
  * @param session
  *   The session data associated with the request. This is typically stored in
  *   a signed cookie which persists across requests.
  * @param flash
  *   The flash data associated with the request. Flash data is accessible only
  *   in the current request, and will automatically be cleared in the next
  *   request. It is typically used for one-time messages (e.g. success or error
  *   messages after a redirect)..
  * @param body
  *   The request body as an InputStream.
  */
case class Request(
    // exchange: HttpServerExchange,
    method: String,
    path: String,
    queryParams: Map[String, Seq[String]],
    headers: Map[String, Seq[String]],
    pathParams: Map[String, String],
    remainingPathSegments: collection.IndexedSeq[String],
    cookies: Map[String, Cookie],
    session: Map[String, String],
    flash: Map[String, String],
    getBody: () => java.io.InputStream
):
  def body = getBody()
