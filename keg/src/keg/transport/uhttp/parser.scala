package keg.transport.uhttp

enum ParseResult:
  case Ok(request: RequestHead)
  case UnsupportedProtocol
  case MalformedRequest
  case RequestLineTooLarge
  case HeadersTooLarge

case class RequestHead(
  method: String,
  uri: String,
  headers: collection.Map[String, collection.Seq[String]] // headers are lowercased
)

private inline def isAlphaNum(byte: Int): Boolean =
  (byte >= 'a' && byte <= 'z') ||
  (byte >= 'A' && byte <= 'Z') ||
  (byte >= '0' && byte <= '9')

/** Read an HTTP request from the given input stream, returning either a parsed
  * Request or an error.
  *
  * Note that this function only reads the request line and headers, it does not
  * consume the body. On success, it is up to the caller to read the body from
  * the stream (e.g. using the Content-Length header to determine how many bytes
  * to read).
  */
def parse(
  stream: java.io.InputStream,
  maxMethodLength: Int = 32,
  maxUriLength: Int = 2048,
  maxVersionLength: Int = 16,
  maxHeaderLineLength: Int = 8192,
  maxHeaders: Int = 100
): ParseResult =
  var byte = -1
  def readNext() =
    byte = stream.read()

  val buffer = new StringBuilder(1024)
  var method: String = ""
  var uri: String = ""

  var res: ParseResult = null

  def parseRequestLine(): Int =
    buffer.clear()
    var n = 0
    while isAlphaNum(byte) && n < maxMethodLength do
      buffer.append(byte.asInstanceOf[Char])
      readNext()
      n += 1

    if n >= maxMethodLength || byte != ' ' then
      res = ParseResult.RequestLineTooLarge
      return -1

    method = buffer.toString()

    buffer.clear()
    n = 0
    readNext()
    while byte != -1 && byte != ' ' && byte != '\r' && n < maxUriLength do
      buffer.append(byte.asInstanceOf[Char])
      readNext()
      n += 1

    if n >= maxUriLength || byte != ' ' then
      res = ParseResult.RequestLineTooLarge
      return -1
    uri = buffer.toString()
    uri = java.net.URLDecoder.decode(uri, "UTF-8")

    buffer.clear()
    n = 0
    readNext()
    while byte != -1 && byte != '\r' && n < maxVersionLength do
      buffer.append(byte.asInstanceOf[Char])
      readNext()
      n += 1
    if n >= maxVersionLength || (byte == -1) then
      res = ParseResult.RequestLineTooLarge
      return -1
    val version = buffer.toString()

    // We only support HTTP/1.1 for now
    if version != "HTTP/1.1" then
      res = ParseResult.UnsupportedProtocol
      return -1

    if byte != '\r' then
      res = ParseResult.RequestLineTooLarge
      return -1
    readNext()

    if byte != '\n' then
      res = ParseResult.RequestLineTooLarge
      return -1
    readNext()
    0

  val headers = scala.collection.mutable.Map[String, collection.mutable.ListBuffer[String]]()

  def parseHeaderLine(): Int =
    buffer.clear()
    var n = 0
    while byte != -1 && byte != ':' && byte != '\r' && n < maxHeaderLineLength do
      val lower = byte.asInstanceOf[Char].toLower
      buffer.append(lower)
      readNext()
      n += 1

    if n >= maxHeaderLineLength || byte != ':' then
      res = ParseResult.HeadersTooLarge
      return -1

    val name = buffer.toString()

    buffer.clear()
    readNext() // skip ':'
    while byte == ' ' || byte == '\t' && n < maxHeaderLineLength do
      readNext()
      n += 1

    if n >= maxHeaderLineLength then
      res = ParseResult.HeadersTooLarge
      return -1

    while byte != -1 && byte != '\r' && n < maxHeaderLineLength do
      buffer.append(byte.asInstanceOf[Char])
      readNext()
      n += 1

    if n >= maxHeaderLineLength || (byte == -1) then
      res = ParseResult.HeadersTooLarge
      return -1

    val value = buffer.toString()

    if byte != '\r' then
      res = ParseResult.MalformedRequest
      return -1
    readNext()
    if byte != '\n' then
      res = ParseResult.MalformedRequest
      return -1
    readNext()

    headers.getOrElseUpdate(name, collection.mutable.ListBuffer[String]()) += value
    0

  readNext()

  if parseRequestLine() != 0 then return res

  var headerCount = 0
  while byte != -1 && byte != '\r' && headerCount < maxHeaders do
    if parseHeaderLine() != 0 then return res
    headerCount += 1

  if headerCount >= maxHeaders then
    return ParseResult.HeadersTooLarge

  if byte != '\r' then return ParseResult.MalformedRequest
  readNext()

  if byte != '\n' then return ParseResult.MalformedRequest

  // at this point the request line, headers and empty line have been read,
  // the body (if any) is the remainder of the stream
  ParseResult.Ok(RequestHead(method, uri, headers))

def parseCookieHeaders(cookies: collection.Seq[String]): Map[String, keg.model.Cookie] =
  val result = scala.collection.mutable.Map[String, keg.model.Cookie]()

  for rawCookie <- cookies do
    val parts = rawCookie.split(";").nn.map(_.nn.trim.nn)
    if parts.nonEmpty then
      val nameValue = parts(0)
      val eqIdx = nameValue.indexOf('=')
      val (name, value) =
        if eqIdx < 0 then (nameValue, "")
        else (nameValue.substring(0, eqIdx).trim.nn, nameValue.substring(eqIdx + 1).trim.nn)

      var comment: String  = null
      var domain: String   = null
      var expires: java.time.Instant = null
      var maxAge: Integer  = null
      var path: String     = null
      var version: Int     = 1
      var discard          = false
      var httpOnly         = false
      var secure           = false
      var sameSite: String = "Lax"

      for part <- parts.drop(1) do
        val attrEq = part.indexOf('=')
        val (attrName, attrValue) =
          if attrEq < 0 then (part.toLowerCase.nn, "")
          else (part.substring(0, attrEq).trim.toLowerCase.nn, part.substring(attrEq + 1).trim.nn)

        attrName match
          case "comment"  => comment  = attrValue
          case "domain"   => domain   = attrValue
          case "path"     => path     = attrValue
          case "samesite" => sameSite = attrValue
          case "version"  => version  = attrValue.toIntOption.getOrElse(1)
          case "max-age"  => maxAge   = attrValue.toIntOption.map(Integer.valueOf).orNull
          case "discard"  => discard  = true
          case "httponly" => httpOnly = true
          case "secure"   => secure   = true
          case "expires"  =>
            try
              expires = java.time.Instant.from(
                java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME.parse(attrValue)
              )
            catch case _: Exception => () // ignore unparseable dates
          case _ => () // ignore unknown attributes

      result(name) = keg.model.Cookie(
        name     = name,
        value    = value,
        comment  = comment,
        domain   = domain,
        expires  = expires,
        maxAge   = maxAge,
        path     = path,
        version  = version,
        discard  = discard,
        httpOnly = httpOnly,
        secure   = secure,
        sameSite = sameSite,
      )

  result.toMap

