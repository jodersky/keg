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
    uri: String,
    headers: Map[String, Seq[String]],
    pathParams: Map[String, String],
    remainingPathSegments: collection.IndexedSeq[String],
    cookies: Map[String, Cookie],
    session: Map[String, String],
    flash: Map[String, String],
    getBody: () => java.io.InputStream
):
  def body = getBody()

  lazy val path: String =
    uri.indexOf('?') match
      case -1  => uri
      case idx => uri.substring(0, idx)

  lazy val queryParams: Map[String, Seq[String]] =
    Util.decodeQueryString(
      uri,
      uri.indexOf('?') match
        case -1  => uri.length
        case idx => idx + 1
    )

object Util:
  def decodeQueryString(uri: String, idx: Int): Map[String, Seq[String]] =
    if idx > uri.length then Map()
    else
      var limit = 1024 // params limit
      var done = false
      var i = idx
      val params = scala.collection.mutable
        .Map[String, scala.collection.mutable.ListBuffer[String]]()

      var nameStart = idx
      var valueStart = -1

      def addParam(): Boolean =
        if nameStart >= i then false
        else
          if valueStart <= nameStart then valueStart = i + 1
          val name = java.net.URLDecoder
            .decode(uri.substring(nameStart, valueStart - 1), "UTF-8")
          val value = java.net.URLDecoder
            .decode(uri.substring(valueStart - 1, i), "UTF-8")
          val values = params.getOrElseUpdate(
            name,
            scala.collection.mutable.ListBuffer[String]()
          )
          values += value
          true

      while i < uri.length && !done do
        uri.charAt(i) match
          case '=' =>
            if nameStart == i then nameStart = i + 1
            else if valueStart < nameStart then valueStart = i + 1
          case '&' =>
            if addParam() then
              limit -= 1
              if limit <= 0 then done = true
            nameStart = i + 1
          case '#' => done = true
          case _   => // continue
        i += 1
      addParam()

      params.view.mapValues(_.toSeq).toMap
