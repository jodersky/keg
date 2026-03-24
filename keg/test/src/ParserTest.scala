import utest.*

import keg.transport.uhttp

object ParserTest extends TestSuite:

  def mkStream(s: String) = new java.io.ByteArrayInputStream(s.getBytes("UTF-8"))

  def ok(r: uhttp.ParseResult): uhttp.RequestHead =
    assert(r.isInstanceOf[uhttp.ParseResult.Ok])
    r.asInstanceOf[uhttp.ParseResult.Ok].request

  val tests = Tests:

    // --- request line ---

    test("get"):
      val req = ok(uhttp.parse(mkStream("GET /foo HTTP/1.1\r\n\r\n")))
      assert(req.method == "GET")
      assert(req.uri == "/foo")
      assert(req.headers.isEmpty)

    test("post"):
      val req = ok(uhttp.parse(mkStream("POST /submit HTTP/1.1\r\n\r\n")))
      assert(req.method == "POST")
      assert(req.uri == "/submit")

    test("put"):
      val req = ok(uhttp.parse(mkStream("PUT /resource/1 HTTP/1.1\r\n\r\n")))
      assert(req.method == "PUT")
      assert(req.uri == "/resource/1")

    test("delete"):
      val req = ok(uhttp.parse(mkStream("DELETE /item/42 HTTP/1.1\r\n\r\n")))
      assert(req.method == "DELETE")
      assert(req.uri == "/item/42")

    test("patch"):
      val req = ok(uhttp.parse(mkStream("PATCH /update HTTP/1.1\r\n\r\n")))
      assert(req.method == "PATCH")

    test("head"):
      val req = ok(uhttp.parse(mkStream("HEAD / HTTP/1.1\r\n\r\n")))
      assert(req.method == "HEAD")

    test("options"):
      val req = ok(uhttp.parse(mkStream("OPTIONS /api HTTP/1.1\r\n\r\n")))
      assert(req.method == "OPTIONS")

    test("rootUri"):
      val req = ok(uhttp.parse(mkStream("GET / HTTP/1.1\r\n\r\n")))
      assert(req.uri == "/")

    test("uriWithQueryString"):
      val req = ok(uhttp.parse(mkStream("GET /search?q=hello&page=2 HTTP/1.1\r\n\r\n")))
      assert(req.uri == "/search?q=hello&page=2")

    test("uriWithDeepPath"):
      val req = ok(uhttp.parse(mkStream("GET /a/b/c/d HTTP/1.1\r\n\r\n")))
      assert(req.uri == "/a/b/c/d")

    test("urlEncodedUri"):
      val req = ok(uhttp.parse(mkStream("GET /hello%20world HTTP/1.1\r\n\r\n")))
      assert(req.uri == "/hello world")

    test("urlEncodedQueryParam"):
      val req = ok(uhttp.parse(mkStream("GET /search?q=hello%20world HTTP/1.1\r\n\r\n")))
      assert(req.uri == "/search?q=hello world")

    // --- headers ---

    test("singleHeader"):
      val req = ok(uhttp.parse(mkStream("GET / HTTP/1.1\r\nHost: example.com\r\n\r\n")))
      assert(req.headers("host").head == "example.com")

    test("multipleHeaders"):
      val raw = "GET / HTTP/1.1\r\nHost: example.com\r\nContent-Type: application/json\r\n\r\n"
      val req = ok(uhttp.parse(mkStream(raw)))
      assert(req.headers("host").head == "example.com")
      assert(req.headers("content-type").head == "application/json")

    test("headersAreLowercased"):
      val raw = "GET / HTTP/1.1\r\nX-Custom-Header: value\r\n\r\n"
      val req = ok(uhttp.parse(mkStream(raw)))
      assert(req.headers.contains("x-custom-header"))
      assert(!req.headers.contains("X-Custom-Header"))

    test("headerWithLeadingWhitespace"):
      val raw = "GET / HTTP/1.1\r\nHost:   example.com\r\n\r\n"
      val req = ok(uhttp.parse(mkStream(raw)))
      assert(req.headers("host").head == "example.com")

    test("duplicateHeaderNames"):
      val raw = "GET / HTTP/1.1\r\nAccept: text/html\r\nAccept: application/json\r\n\r\n"
      val req = ok(uhttp.parse(mkStream(raw)))
      val values = req.headers("accept")
      assert(values.toSeq.contains("text/html"))
      assert(values.toSeq.contains("application/json"))

    test("contentLengthHeader"):
      val raw = "POST /data HTTP/1.1\r\nContent-Length: 13\r\n\r\nHello, World!"
      val stream = mkStream(raw)
      val req = ok(uhttp.parse(stream))
      assert(req.headers("content-length").head == "13")
      // body is not consumed by parse
      val body = new String(stream.readAllBytes(), "UTF-8")
      assert(body == "Hello, World!")

    // --- body not consumed ---

    test("bodyNotConsumed"):
      val raw  = "POST / HTTP/1.1\r\n\r\nsome body data"
      val stream = mkStream(raw)
      uhttp.parse(stream)
      val remaining = new String(stream.readAllBytes(), "UTF-8")
      assert(remaining == "some body data")

    test("streamExhaustedWhenNoBody"):
      val stream = mkStream("GET / HTTP/1.1\r\n\r\n")
      uhttp.parse(stream)
      assert(stream.read() == -1)

    // --- unsupported protocol ---

    test("http10"):
      val result = uhttp.parse(mkStream("GET / HTTP/1.0\r\n\r\n"))
      assert(result == uhttp.ParseResult.UnsupportedProtocol)

    test("http20"):
      val result = uhttp.parse(mkStream("GET / HTTP/2.0\r\n\r\n"))
      assert(result == uhttp.ParseResult.UnsupportedProtocol)

    test("http09"):
      val result = uhttp.parse(mkStream("GET / HTTP/0.9\r\n\r\n"))
      assert(result == uhttp.ParseResult.UnsupportedProtocol)

    test("bogusProtocol"):
      val result = uhttp.parse(mkStream("GET / BOGUS\r\n\r\n"))
      assert(result == uhttp.ParseResult.UnsupportedProtocol)

    // --- malformed requests ---

    test("missingFinalCRLF"):
      val result = uhttp.parse(mkStream("GET / HTTP/1.1\r\nHost: example.com\r\n"))
      assert(result == uhttp.ParseResult.MalformedRequest)

    test("lfOnlyLineEnding"):
      // LF-only without CR is not valid HTTP/1.1
      val result = uhttp.parse(mkStream("GET / HTTP/1.1\n\n"))
      assert(result != uhttp.ParseResult.Ok(uhttp.RequestHead("GET", "/", Map.empty)))

    // --- too large ---

    test("methodTooLong"):
      val longMethod = "A" * 100
      val result = uhttp.parse(mkStream(s"$longMethod / HTTP/1.1\r\n\r\n"), maxMethodLength = 10)
      assert(result == uhttp.ParseResult.RequestLineTooLarge)

    test("methodExactlyAtLimit"):
      // A method of exactly maxMethodLength chars should be rejected (limit is exclusive)
      val result = uhttp.parse(mkStream("GET / HTTP/1.1\r\n\r\n"), maxMethodLength = 3)
      assert(result == uhttp.ParseResult.RequestLineTooLarge)

    test("uriTooLong"):
      val longUri = "/" + "a" * 3000
      val result = uhttp.parse(mkStream(s"GET $longUri HTTP/1.1\r\n\r\n"), maxUriLength = 64)
      assert(result == uhttp.ParseResult.RequestLineTooLarge)

    test("tooManyHeaders"):
      val headers = (1 to 5).map(i => s"X-Header-$i: value\r\n").mkString
      val result = uhttp.parse(mkStream(s"GET / HTTP/1.1\r\n${headers}\r\n"), maxHeaders = 3)
      assert(result == uhttp.ParseResult.HeadersTooLarge)

    test("headerLineTooLong"):
      val longValue = "x" * 9000
      val result = uhttp.parse(
        mkStream(s"GET / HTTP/1.1\r\nX-Long: $longValue\r\n\r\n"),
        maxHeaderLineLength = 100
      )
      assert(result == uhttp.ParseResult.HeadersTooLarge)
    // --- unicode ---

    // URI percent-encoding is decoded via URLDecoder, so multi-byte UTF-8
    // sequences encoded as %XX%XX should round-trip correctly.

    test("uriPercentEncodedAccentedChar"):
      // é = U+00E9, UTF-8: 0xC3 0xA9
      val req = ok(uhttp.parse(mkStream("GET /caf%C3%A9 HTTP/1.1\r\n\r\n")))
      assert(req.uri == "/café")

    test("uriPercentEncodedCJK"):
      // 中 = U+4E2D, UTF-8: 0xE4 0xB8 0xAD
      val req = ok(uhttp.parse(mkStream("GET /%E4%B8%AD HTTP/1.1\r\n\r\n")))
      assert(req.uri == "/中")

    test("uriPercentEncodedEmoji"):
      // 😀 = U+1F600, UTF-8: 0xF0 0x9F 0x98 0x80
      val req = ok(uhttp.parse(mkStream("GET /%F0%9F%98%80 HTTP/1.1\r\n\r\n")))
      assert(req.uri == "/😀")

    test("uriPercentEncodedLowercaseHex"):
      // URLDecoder must handle lowercase hex digits
      val req = ok(uhttp.parse(mkStream("GET /caf%c3%a9 HTTP/1.1\r\n\r\n")))
      assert(req.uri == "/café")

    test("uriPercentEncodedNullByte"):
      // %00 decodes to the null character — documents current behavior
      val req = ok(uhttp.parse(mkStream("GET /foo%00bar HTTP/1.1\r\n\r\n")))
      assert(req.uri == "/foo\u0000bar")

    test("uriPercentEncodedSlash"):
      // %2F is a literal slash after decoding — not a path separator
      val req = ok(uhttp.parse(mkStream("GET /foo%2Fbar HTTP/1.1\r\n\r\n")))
      assert(req.uri == "/foo/bar")

    test("uriMixedAsciiAndUnicode"):
      // Mix of plain ASCII and percent-encoded multi-byte sequence
      val req = ok(uhttp.parse(mkStream("GET /hello-%E4%B8%96%E7%95%8C HTTP/1.1\r\n\r\n")))
      assert(req.uri == "/hello-世界")

    test("uriQueryParamWithUnicode"):
      val req = ok(uhttp.parse(mkStream("GET /search?q=%F0%9F%94%8D HTTP/1.1\r\n\r\n")))
      assert(req.uri == "/search?q=🔍")

    test("headerValueNonAsciiBytesPreservedAsLatin1"):
      // The parser reads raw bytes and casts each to Char (no charset decoding),
      // so a UTF-8 encoded header value is interpreted byte-by-byte as Latin-1.
      // "é" in UTF-8 is bytes 0xC3 0xA9 — those become chars '\u00C3' and '\u00A9'.
      val raw = "GET / HTTP/1.1\r\nX-Name: caf\u00C3\u00A9\r\n\r\n"
      val bytes = raw.getBytes("ISO-8859-1") // encode as Latin-1 so high bytes round-trip
      val stream = new java.io.ByteArrayInputStream(bytes)
      val req = ok(uhttp.parse(stream))
      assert(req.headers("x-name").head == "caf\u00C3\u00A9")

    test("headerValueWithArrow"):
      // Fancy Unicode arrows are > 0xFF and can only appear in header values if
      // the client sends them percent-encoded or in a charset-aware layer; raw
      // bytes > 0x7F passed through a Latin-1 stream are stored verbatim.
      val raw = "GET / HTTP/1.1\r\nX-Arrow: \u00E2\u0086\u0092\r\n\r\n" // → in UTF-8 bytes as Latin-1 chars
      val bytes = raw.getBytes("ISO-8859-1")
      val stream = new java.io.ByteArrayInputStream(bytes)
      val req = ok(uhttp.parse(stream))
      // bytes 0xE2 0x86 0x92 stored as chars '\u00E2' '\u0086' '\u0092'
      assert(req.headers("x-arrow").head == "\u00E2\u0086\u0092")

    test("bodyWithUnicodeNotConsumed"):
      // Unicode body bytes must not be touched by the parser
      val body = "こんにちは"
      val bodyBytes = body.getBytes("UTF-8")
      val header = "POST / HTTP/1.1\r\n\r\n".getBytes("UTF-8")
      val all = header ++ bodyBytes
      val stream = new java.io.ByteArrayInputStream(all)
      uhttp.parse(stream)
      val remaining = new String(stream.readAllBytes(), "UTF-8")
      assert(remaining == body)
