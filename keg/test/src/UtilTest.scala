import utest.*
import keg.main.Util

object UtilTest extends TestSuite:
  val tests = Tests:
    test("decodeQueryString"):
      val qs1 = "name=alice&age=30&hobby=reading&hobby=swimming"
      val result1 = Util.decodeQueryString(qs1)
      assert(
        result1("name") == "alice",
        result1("age") == "30",
        result1.getAll("hobby") == Seq("reading", "swimming")
      )

      val qs2 = "empty=&noValue&encoded=%26%3D%2B"
      val result2 = Util.decodeQueryString(qs2)
      assert(
        result2("empty") == "",
        result2("noValue") == "",
        result2("encoded") == "&=+"
      )

      val qs3 = ""
      val result3 = Util.decodeQueryString(qs3)
      assert(result3.isEmpty)
