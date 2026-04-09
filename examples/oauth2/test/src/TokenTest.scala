import utest.*

object TokenTest extends TestSuite:

  val tests = Tests:

    test("random generates token of requested length"):
      val t = oauth2.Token.random(16)
      assert(t.toHex.length == 32) // 16 bytes = 32 hex chars

      val t2 = oauth2.Token.random(64)
      assert(t2.toHex.length == 128)

    test("random generates unique tokens"):
      val t1 = oauth2.Token.random()
      val t2 = oauth2.Token.random()
      assert(t1.toHex != t2.toHex)

    test("hex roundtrip"):
      val t = oauth2.Token.random()
      val hex = t.toHex
      val t2 = oauth2.Token.fromHex(hex)
      assert(t2.toHex == hex)

    test("fromHex known value"):
      val t = oauth2.Token.fromHex("deadbeef")
      assert(t.toHex == "deadbeef")

    test("sha256 produces 32-byte hash"):
      val t = oauth2.Token.random(16)
      val h = t.sha256()
      assert(h.toHex.length == 64) // SHA-256 = 32 bytes = 64 hex chars

    test("sha256 is deterministic"):
      val t = oauth2.Token.fromHex("cafebabe")
      val h1 = t.sha256()
      val h2 = t.sha256()
      assert(h1.toHex == h2.toHex)

    test("sha256 differs for different tokens"):
      val t1 = oauth2.Token.fromHex("00")
      val t2 = oauth2.Token.fromHex("01")
      assert(t1.sha256().toHex != t2.sha256().toHex)

    test("HashedToken.fromHex roundtrip"):
      val h = oauth2.HashedToken.fromHex("abcdef0123456789")
      assert(h.toHex == "abcdef0123456789")

    test("equalsCst returns true for equal hashes"):
      val t = oauth2.Token.fromHex("deadbeef")
      val h1 = t.sha256()
      val h2 = t.sha256()
      assert(h1 == h2)

    test("equalsCst returns false for different hashes"):
      val h1 = oauth2.Token.fromHex("00").sha256()
      val h2 = oauth2.Token.fromHex("01").sha256()
      assert(h1 != h2)
