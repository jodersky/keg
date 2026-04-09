import utest.*

import keg.util.url
import keg.util.makeUrl

object UrlInterpolatorTest extends TestSuite:

  val tests = Tests:
    test("simple"):
      val s = url"hello"
      assert(s == "hello")

    test("withArgs"):
      val name = "world"
      val s = url"hello $name"
      assert(s == "hello world")

    test("urlencoded path"):
      val name = "keg framework"
      val s = url"/hello/$name"
      assert(s == "/hello/keg%20framework")

    test("urlencoded path2"):
      val name = "/keg framework"
      val s = url"/hello/$name"
      assert(s == "/hello/%2Fkeg%20framework")

    test("withQueryParams"):
      val base = "search"
      val s = url"""/$base?q=${"keg framework"}&page=${1}"""
      assert(s == "/search?q=keg+framework&page=1")

    test("makeUrl"):
      test("simple"):
        val s = makeUrl(paths = Seq("hello", "world"), queryParams = Seq("q" -> "keg framework"))
        assert(s == "/hello/world?q=keg+framework")
      test("special characters"):
        val s = makeUrl(paths = Seq("hello/world?a=b"), queryParams = Seq("q" -> "keg/framework"))
        assert(s == "/hello%2Fworld%3Fa%3Db?q=keg%2Fframework")
      test("empty queryParams"):
        val s = makeUrl(paths = Seq("hello", "world"), queryParams = Seq.empty)
        assert(s == "/hello/world")
      test("empty paths"):
        val s = makeUrl(paths = Seq.empty, queryParams = Seq("q" -> "keg framework"))
        assert(s == "?q=keg+framework")
      test("empty paths and queryParams"):
        val s = makeUrl(paths = Seq.empty, queryParams = Seq.empty)
        assert(s == "")

      test("full"):
        val s = makeUrl(
          scheme = "https",
          host = "example.com",
          port = 8080,
          paths = Seq("api", "search"),
          queryParams = Seq("q" -> "keg framework", "page" -> "1")
        )
        assert(s == "https://example.com:8080/api/search?q=keg+framework&page=1")

      test("full no port"):
        val s = makeUrl(
          scheme = "https",
          host = "example.com",
          paths = Seq("api", "search"),
          queryParams = Seq("q" -> "keg framework", "page" -> "1")
        )
        assert(s == "https://example.com/api/search?q=keg+framework&page=1")
