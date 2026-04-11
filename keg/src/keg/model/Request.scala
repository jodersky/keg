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

  /** Returns the request body as an InputStream, limited to the specified
    * maximum size. */
  def body(maxSize: Long = Request.DefaultMaxBodySize): java.io.InputStream =
    new Request.LimitedInputStream(getBody(), maxSize)

  /** Consumes the request body and returns it as a String, limited to the
    * specified maximum size and using the specified charset. */
  def text(maxSize: Long = Request.DefaultMaxBodySize, charset: String = "UTF-8"): String =
    new String(body(maxSize).readAllBytes(), charset)

  /** Consumes the request body and returns it as a byte array, limited to the
    * specified maximum size. */
  def bytes(maxSize: Long = Request.DefaultMaxBodySize): Array[Byte] =
    body(maxSize).readAllBytes()

object Request:
  // 30 KB default max body size to prevent denial of service attacks. Note that
  // this is mostly here for one-off endpoints. In a larger or more structured
  // application, you'd typically either use a dedicated FromRequest with its
  // own limit, or set the limit explicitly when calling
  // `request.body(maxSize)`.
  val DefaultMaxBodySize: Long = 30 * 1024

  private[model] class LimitedInputStream(underlying: java.io.InputStream, maxSize: Long)
      extends java.io.InputStream:
    private var bytesRead = 0L

    override def read(): Int =
      if bytesRead >= maxSize then return -1
      val b = underlying.read()
      if b != -1 then bytesRead += 1
      b

    override def read(buf: Array[Byte], off: Int, len: Int): Int =
      if bytesRead >= maxSize then return -1
      val n = underlying.read(buf, off, math.min(len, (maxSize - bytesRead).toInt))
      if n > 0 then bytesRead += n
      n

    override def close(): Unit = underlying.close()
