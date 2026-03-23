package keg.main

import keg.util.MultiDict

// utilities for classic web applications (e.g. CSRF protection, cookie signing)
object WebAppUtil:

  /** Check if the current request is allowed under cross-site request forgery
    * (CSRF) protection rules.
    *
    * See https://words.filippo.io/csrf/ and
    * https://cs.opensource.google/go/go/+/refs/tags/go1.25rc2:src/net/http/csrf.go;l=122
    * for details and rationale behind the rules implemented here.
    *
    * @param method The HTTP method of the request (case sensitive)
    * @param headers The HTTP headers of the request, as a map from header name
    * to a sequence of values.
    * @param allowedOrigins The set of allowed origins for cross-origin
    * requests. This is used to allow certain cross-origin requests, e.g. from a
    * trusted single-page application running on a different origin.
    */
  def checkCsrf(
      method: String,
      headers: MultiDict[String, String],
      allowedOrigins: Set[String]
  ): Boolean =
    // val method = method.toUpperCase()

    def isExempt(): Boolean =
      val origin = headers.get("origin")
      origin match
        case None         => false
        case Some(origin) =>
          allowedOrigins.contains(origin)

    if method == "GET" || method == "HEAD" || method == "OPTIONS" then
      // safe methods are always allowed
      return true

    headers.get("sec-fetch-site") match
      case None                               => // continue
      case Some("same-origin") | Some("none") =>
        return true
      case _ =>
        return isExempt()

    val origin = headers.get("origin")
    origin match
      case None =>
        // Neither Sec-Fetch-Site nor Origin headers are present.
        // Either the request is same-origin or not from a browser
        return true
      case Some(origin) =>
        try
          val originUrl = new java.net.URI(origin)
          val originHost = originUrl.getHost()
          val host = headers.get("host").getOrElse("")
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

  /** Sign (HMAC) a map of key-value pairs, with an expiration time.
    *
    * The resulting string can be safely included in URLs and cookies.
    *
    * @param payload The map of key-value pairs to sign
    * @param expires Expiration time as a Unix timestamp (seconds since epoch)
    * @param secret The secret (symmetric) key to use for signing
    */
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

  /** Verify a signed map, returning the original map if the signature is valid
    * and the expiration time has not passed, or an empty map otherwise.
    *
    * This is the inverse of `signMap`, and accepts multiple secrets to allow
    * for key rotation.
    *
    * @param data The signed string to verify
    * @param secrets The secrets to use for verification
    * @return The original map if the signature is valid and not expired, or an empty map otherwise.
    */
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
