import keg.util.MultiDict
import utest.*

object MultiDictTest extends TestSuite:

  val tests = Tests:
    test("get returns first value"):
      val md = MultiDict(Map("a" -> Seq(1, 2, 3), "b" -> Seq(4, 5)))
      assert(md.get("a") == Some(1))
      assert(md.get("b") == Some(4))
      assert(md.get("c") == None)

    test("getAll returns all values"):
      val md = MultiDict(Map("a" -> Seq(1, 2, 3), "b" -> Seq(4, 5)))
      assert(md.getAll("a") == Seq(1, 2, 3))
      assert(md.getAll("b") == Seq(4, 5))
      assert(md.getAll("c") == Seq.empty)

    test("apply returns first value or throws"):
      val md = MultiDict(Map("a" -> Seq(1, 2, 3)))
      assert(md("a") == 1)
      assertMatch(try md("c") catch case _: NoSuchElementException => "error"):
        case "error" =>

    test("map transforms key-value pairs"):
      val md = MultiDict(Map("a" -> Seq(1, 2), "b" -> Seq(3)))
      val mapped = md.map((k, v) => (k, v * 2))
      assert(mapped.get("a") == Some(2))
      assert(mapped.getAll("a") == Seq(2, 4))
      assert(mapped.getAll("b") == Seq(6))

    test("flatMap transforms and flattens key-value pairs"):
      val md = MultiDict(Map("a" -> Seq(1, 2)))
      val flatMapped = md.flatMap((k, v) => Seq((k, v), (k, v * 10)))
      assert(flatMapped.getAll("a") == Seq(1, 10, 2, 20))

    test("mapValues transforms all values"):
      val md = MultiDict(Map("a" -> Seq(1, 2), "b" -> Seq(3)))
      val mapped = md.mapValues(_ * 2)
      assert(mapped.getAll("a") == Seq(2, 4))
      assert(mapped.getAll("b") == Seq(6))

    test("mapKeys transforms all keys"):
      val md = MultiDict(Map("a" -> Seq(1, 2), "b" -> Seq(3)))
      val mapped = md.mapKeys(k => k.toUpperCase)
      assert(mapped.getAll("A") == Seq(1, 2))
      assert(mapped.getAll("B") == Seq(3))

    test("filterKeys keeps only matching keys"):
      val md = MultiDict(Map("a" -> Seq(1, 2), "b" -> Seq(3), "c" -> Seq(4)))
      val filtered = md.filterKeys(_ != "b")
      assert(filtered.get("a") == Some(1))
      assert(filtered.get("b") == None)
      assert(filtered.get("c") == Some(4))

    test("filterValues keeps only matching values"):
      val md = MultiDict(Map("a" -> Seq(1, 2, 3), "b" -> Seq(4, 5)))
      val filtered = md.filterValues(_ > 2)
      assert(filtered.getAll("a") == Seq(3))
      assert(filtered.getAll("b") == Seq(4, 5))

    test("filter keeps only matching key-value pairs"):
      val md = MultiDict(Map("a" -> Seq(1, 2, 3), "b" -> Seq(4, 5)))
      val filtered = md.filter((k, v) => k == "a" && v > 1)
      assert(filtered.getAll("a") == Seq(2, 3))
      assert(filtered.getAll("b") == Seq.empty)

    test("keys returns all unique keys"):
      val md = MultiDict(Map("a" -> Seq(1, 2), "b" -> Seq(3)))
      assert(md.keys.toSet == Set("a", "b"))

    test("values returns all values"):
      val md = MultiDict(Map("a" -> Seq(1, 2), "b" -> Seq(3)))
      assert(md.values.toSet == Set(1, 2, 3))

    test("items returns all key-value pairs"):
      val md = MultiDict(Map("a" -> Seq(1, 2), "b" -> Seq(3)))
      assert(md.items.toSet == Set(("a", 1), ("a", 2), ("b", 3)))

    test("empty MultiDict"):
      val md = MultiDict(Map[String, Seq[Int]]())
      assert(md.get("a") == None)
      assert(md.getAll("a") == Seq.empty)
      assert(md.keys.toSeq == Seq.empty)
      assert(md.values.toSeq == Seq.empty)
      assert(md.items.toSeq == Seq.empty)
