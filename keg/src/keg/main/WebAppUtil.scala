package keg.main

// utilities for classic web applications (e.g. CSRF protection, cookie signing)
object WebAppUtil:

  /** Check if the current request is allowed under CSRF protection rules.
    * https://words.filippo.io/csrf/
    * https://cs.opensource.google/go/go/+/refs/tags/go1.25rc2:src/net/http/csrf.go;l=122
    */
  def checkCsrf(
      method: String,
      headers: Map[String, Seq[String]],
      allowedOrigins: Set[String]
  ): Boolean =
    // val method = method.toUpperCase()

    def isExempt(): Boolean =
      val origin = headers.get("Origin").map(_.head)
      origin match
        case None         => false
        case Some(origin) =>
          allowedOrigins.contains(origin)

    if method == "GET" || method == "HEAD" || method == "OPTIONS" then
      // safe methods are always allowed
      return true

    headers.get("Sec-Fetch-Site").map(_.head) match
      case None                               => // continue
      case Some("same-origin") | Some("none") =>
        return true
      case _ =>
        return isExempt()

    val origin = headers.get("Origin").map(_.head)
    origin match
      case None =>
        // Neither Sec-Fetch-Site nor Origin headers are present.
        // Either the request is same-origin or not from a browser
        return true
      case Some(origin) =>
        try
          val originUrl = new java.net.URI(origin)
          val originHost = originUrl.getHost()
          val host = headers.get("Host").map(_.head).getOrElse("")
          if originHost == host then
            // The Origin header matches the Host header. Note that the Host header
            // doesn't include the scheme, so we don't know if this might be an
            // HTTP→HTTPS cross-origin request. We fail open, since all modern
            // browsers support Sec-Fetch-Site since 2023, and running an older
            // browser makes a clear security trade-off already. Sites can mitigate
            // this with HTTP Strict Transport Security (HSTS).
            return true
        catch
          case _: Exception =>
            return false

    return isExempt()

  end checkCsrf

  def signMap(
      payload: Map[String, String],
      expires: Long,
      secret: Secret
  ): String =
    val b64 = java.util.Base64.getUrlEncoder.withoutPadding()

    val buffer = new StringBuilder
    val kvs = payload.iterator
    if kvs.hasNext then
      val (key, value) = kvs.next()
      buffer.append(
        b64.encodeToString(
          key.getBytes(java.nio.charset.StandardCharsets.UTF_8)
        )
      )
      buffer.append("|")
      buffer.append(
        b64.encodeToString(
          value.getBytes(java.nio.charset.StandardCharsets.UTF_8)
        )
      )

      while kvs.hasNext do
        buffer.append("|")
        val (key, value) = kvs.next()
        buffer.append(
          b64.encodeToString(
            key.getBytes(java.nio.charset.StandardCharsets.UTF_8)
          )
        )
        buffer.append("|")
        buffer.append(
          b64.encodeToString(
            value.getBytes(java.nio.charset.StandardCharsets.UTF_8)
          )
        )

    buffer.append(".")
    buffer.append(expires.toString)

    val toSign = buffer.toString()
    val hmac = javax.crypto.Mac.getInstance("HmacSHA256")
    val keySpec = new javax.crypto.spec.SecretKeySpec(
      secret.bytes,
      "HmacSHA256"
    )
    hmac.init(keySpec)
    val rawHmac =
      hmac.doFinal(toSign.getBytes(java.nio.charset.StandardCharsets.UTF_8))
    val signature =
      java.util.Base64.getUrlEncoder.withoutPadding().encodeToString(rawHmac)
    s"$toSign.$signature"

  def verifyMap(data: String, secrets: Iterable[Secret]): Map[String, String] =
    val b64 = java.util.Base64.getUrlDecoder
    val parts = data.split('.')
    if parts.length != 3 then return Map.empty
    val payload = parts(0)
    val expires = parts(1)
    val signature = parts(2)

    val now = System.currentTimeMillis() / 1000L
    try
      val exp = expires.toLong
      if exp < now then return Map.empty
    catch case _: Exception => return Map.empty

    val it = secrets.iterator
    while it.hasNext do
      val secret = it.next()
      val toVerify = s"$payload.$expires"
      val hmac = javax.crypto.Mac.getInstance("HmacSHA256")
      val keySpec = new javax.crypto.spec.SecretKeySpec(
        secret.bytes,
        "HmacSHA256"
      )
      hmac.init(keySpec)
      val rawHmac =
        hmac.doFinal(toVerify.getBytes(java.nio.charset.StandardCharsets.UTF_8))
      val expectedSignature = java.util.Base64.getUrlEncoder
        .withoutPadding()
        .encodeToString(rawHmac)
      if expectedSignature == signature then
        // parse payload
        val kvParts = payload.split('|')
        if kvParts.length % 2 != 0 then return Map.empty
        val result = scala.collection.mutable.Map[String, String]()
        var i = 0
        while i < kvParts.length do
          try
            val key = new String(
              b64.decode(kvParts(i)),
              java.nio.charset.StandardCharsets.UTF_8
            )
            val value = new String(
              b64.decode(kvParts(i + 1)),
              java.nio.charset.StandardCharsets.UTF_8
            )
            result.put(key, value)
          catch case _: Exception => return Map.empty
          i += 2
        return result.toMap
    Map.empty
