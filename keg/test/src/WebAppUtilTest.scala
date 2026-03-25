import utest.*
import keg.main.WebAppUtil
import keg.main.Secret
import keg.util.MultiDict

object WebAppUtilTest extends TestSuite:

  val tests = Tests:

    test("checkCsrf"):
      test("allows safe methods"):
        assert(WebAppUtil.checkCsrf("GET", MultiDict.empty, Set.empty))
        assert(WebAppUtil.checkCsrf("HEAD", MultiDict.empty, Set.empty))
        assert(WebAppUtil.checkCsrf("OPTIONS", MultiDict.empty, Set.empty))
      test("allows with sec-fetch-site: same-origin"):
        assert(WebAppUtil.checkCsrf("POST", MultiDict(Map("sec-fetch-site" -> List("same-origin"))), Set.empty))
      test("allows with sec-fetch-site: none"):
        assert(WebAppUtil.checkCsrf("POST", MultiDict(Map("sec-fetch-site" -> List("none"))), Set.empty))
      test("blocks with sec-fetch-site: cross-site and no allowed origins"):
        assert(!WebAppUtil.checkCsrf("POST", MultiDict(Map("sec-fetch-site" -> List("cross-site"))), Set.empty))
      test("allows cross-site with allowed origin"):
        assert(WebAppUtil.checkCsrf("POST", MultiDict(Map("sec-fetch-site" -> List("cross-site"), "origin" -> List("https://allowed.com"))), Set("https://allowed.com")))
      test("blocks cross-site with origin not in allowed list"):
        assert(!WebAppUtil.checkCsrf("POST", MultiDict(Map("sec-fetch-site" -> List("cross-site"), "origin" -> List("https://evil.com"))), Set("https://allowed.com")))
      test("allows matching Origin and Host"):
        assert(WebAppUtil.checkCsrf("POST", MultiDict(Map("origin" -> List("https://example.com"), "host" -> List("example.com"))), Set.empty))
      test("blocks non-matching Origin and Host"):
        assert(!WebAppUtil.checkCsrf("POST", MultiDict(Map("origin" -> List("https://evil.com"), "host" -> List("example.com"))), Set.empty))
      test("allows non-matching Origin if in allowed list"):
        assert(WebAppUtil.checkCsrf("POST", MultiDict(Map("origin" -> List("https://partner.com"), "host" -> List("example.com"))), Set("https://partner.com")))
      test("allows POST without Origin or Sec-Fetch-Site"):
        assert(WebAppUtil.checkCsrf("POST", MultiDict.empty, Set.empty))
      test("handles invalid Origin URI"):
        assert(!WebAppUtil.checkCsrf("POST", MultiDict(Map("origin" -> List("not a valid uri"), "host" -> List("example.com"))), Set.empty))

    test("signMap and verifyMap"):
      val secret = Secret.check(Secret.random())
      val futureTime = System.currentTimeMillis() / 1000L + 3600
      val pastTime = System.currentTimeMillis() / 1000L - 100

      test("signs and verifies empty map"):
        val signed = WebAppUtil.signMap(Map.empty, futureTime, secret)
        val verified = WebAppUtil.verifyMap(signed, List(secret))
        assert(verified == Map.empty)

      test("signs and verifies single entry"):
        val payload = Map("user" -> "alice")
        val signed = WebAppUtil.signMap(payload, futureTime, secret)
        val verified = WebAppUtil.verifyMap(signed, List(secret))
        assert(verified == payload)

      test("signs and verifies multiple entries"):
        val payload = Map("user" -> "bob", "role" -> "admin", "id" -> "123")
        val signed = WebAppUtil.signMap(payload, futureTime, secret)
        val verified = WebAppUtil.verifyMap(signed, List(secret))
        assert(verified == payload)

      test("signs and verifies with special characters"):
        val payload = Map("key" -> "value with spaces", "email" -> "user@example.com", "unicode" -> "こんにちは")
        val signed = WebAppUtil.signMap(payload, futureTime, secret)
        val verified = WebAppUtil.verifyMap(signed, List(secret))
        assert(verified == payload)

      test("rejects expired signature"):
        val payload = Map("user" -> "charlie")
        val signed = WebAppUtil.signMap(payload, pastTime, secret)
        val verified = WebAppUtil.verifyMap(signed, List(secret))
        assert(verified.isEmpty)

      test("rejects tampered payload"):
        val payload = Map("user" -> "dave")
        val signed = WebAppUtil.signMap(payload, futureTime, secret)
        val tampered = WebAppUtil.signMap(Map("user" -> "eve"), futureTime, Secret.check(Secret.random()))
        val verified = WebAppUtil.verifyMap(tampered, List(secret))
        assert(verified.isEmpty)

      test("rejects tampered expiry"):
        val payload = Map("user" -> "dave")
        val signed = WebAppUtil.signMap(payload, futureTime, secret)
        val parts = signed.split('.')
        val tamperedExpiry = s"${parts(0)}.${futureTime + 1000}.${parts(2)}"
        val verified = WebAppUtil.verifyMap(tamperedExpiry, List(secret))
        assert(verified.isEmpty)

      test("rejects wrong secret"):
        val payload = Map("user" -> "eve")
        val signed = WebAppUtil.signMap(payload, futureTime, secret)
        val verified = WebAppUtil.verifyMap(signed, List(Secret.check(Secret.random())))
        assert(verified.isEmpty)

      test("verifies with multiple secrets (rotation)"):
        val payload = Map("user" -> "frank")
        val oldSecret = Secret.check(Secret.random())
        val newSecret = Secret.check(Secret.random())

        val signedWithOld = WebAppUtil.signMap(payload, futureTime, oldSecret)
        val verifiedWithBoth = WebAppUtil.verifyMap(signedWithOld, List(newSecret, oldSecret))
        assert(verifiedWithBoth == payload)

        val signedWithNew = WebAppUtil.signMap(payload, futureTime, newSecret)
        val verifiedWithNew = WebAppUtil.verifyMap(signedWithNew, List(newSecret, oldSecret))
        assert(verifiedWithNew == payload)

      test("rejects malformed data - wrong number of parts"):
        assert(WebAppUtil.verifyMap("only.two.parts", List(secret)).isEmpty)
        assert(WebAppUtil.verifyMap("one", List(secret)).isEmpty)
        assert(WebAppUtil.verifyMap("too.many.parts.here.now", List(secret)).isEmpty)

      test("rejects empty string"):
        assert(WebAppUtil.verifyMap("", List(secret)).isEmpty)

      test("rejects invalid expiry timestamp"):
        val payload = Map("user" -> "test")
        val signed = WebAppUtil.signMap(payload, futureTime, secret)
        val parts = signed.split('.')
        val invalidExpiry = s"${parts(0)}.notanumber.${parts(2)}"
        assert(WebAppUtil.verifyMap(invalidExpiry, List(secret)).isEmpty)

      test("rejects odd number of key-value parts"):
        // Construct a signed string with odd number of parts (invalid)
        val malformed = s"key1.${futureTime}.fakesignature"
        assert(WebAppUtil.verifyMap(malformed, List(secret)).isEmpty)

      test("rejects invalid base64 in payload"):
        val malformed = s"invalid!base64!data.${futureTime}.fakesignature"
        assert(WebAppUtil.verifyMap(malformed, List(secret)).isEmpty)
