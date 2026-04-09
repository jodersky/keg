package keg.model

import keg.util.MultiDict

/** A HTTP request.
  * @param method
  *   The HTTP method (e.g. GET, POST, etc).
  * @param uri
  *   The full request URI, including query string.
  * @param headers
  *   The request headers. Always lowercase.
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
    target: String, // the raw request target (e.g. "/path?query=string"), before URL decoding
    headers: MultiDict[String, String],
    pathParams: Map[String, String],
    remainingPathSegments: collection.IndexedSeq[String],
    cookies: Map[String, Cookie],
    session: Map[String, String],
    flash: Map[String, String],
    getBody: () => java.io.InputStream,
    remoteIp: java.net.InetAddress,
    clientCertificate: Option[ClientCertificate]
):

  private val qi = target.indexOf('?')

  val path: String =
    val raw = if qi == -1 then target else target.substring(0, qi)
    java.net.URLDecoder.decode(raw, "UTF-8")

  val queryString: String =
    if qi == -1 then "" else target.substring(qi + 1)

  val queryParams: MultiDict[String, String] =
    if qi == -1 then MultiDict.empty
    else keg.util.Util.decodeQueryString(queryString)

  def body = getBody()

  def text(maxLength: Int = 30*1024): String =
    new String(body.readNBytes(maxLength), "UTF-8")
