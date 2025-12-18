import utest.*
import keg.main.DispatchTrie
import keg.main.DispatchTrie.AddResult
import keg.main.DispatchTrie.DispatchLookup

object DispatchTrieTest extends TestSuite:

  val tests = Tests:
    test("add and lookup static paths"):
      val trie = DispatchTrie[String]()
      assert(trie.add(Seq("foo", "bar"), "GET", "value1") == AddResult.Ok)
      assert(trie.add(Seq("foo", "baz"), "POST", "value2") == AddResult.Ok)

      val lookup1 = trie.lookup(IndexedSeq("foo", "bar"))
      assert(lookup1.items == Map("GET" -> "value1"))
      assert(lookup1.bindings.isEmpty)
      assert(lookup1.remainingPathSegments.isEmpty)

      val lookup2 = trie.lookup(IndexedSeq("foo", "baz"))
      assert(lookup2.items == Map("POST" -> "value2"))
      assert(lookup2.bindings.isEmpty)
      assert(lookup2.remainingPathSegments.isEmpty)

    test("add method conflict"):
      val trie = DispatchTrie[String]()
      assert(trie.add(Seq("foo", "bar"), "GET", "value1") == AddResult.Ok)
      val result = trie.add(Seq("foo", "bar"), "GET", "value2")
      assert(result.isInstanceOf[AddResult.MethodConflict[?]])
      val err = result.asInstanceOf[AddResult.MethodConflict[?]]
      assert(err.existingPath == "/foo/bar/")
      assert(err.existingItems == Map("GET" -> "value1"))

    test("add subpath conflict"):
      val trie = DispatchTrie[String]()
      assert(trie.add(Seq("foo", "bar", "*"), "GET", "value1") == AddResult.Ok)
      val result = trie.add(Seq("foo", "bar", "baz"), "POST", "value2")
      assert(result.isInstanceOf[AddResult.SubpathConflict[?]])
      val err = result.asInstanceOf[AddResult.SubpathConflict[?]]
      assert(err.existingPath == "/foo/bar/")
      assert(err.existingItems == Map("GET" -> "value1"))

    test("lookup with remaining path segments"):
      val trie = DispatchTrie[String]()
      assert(trie.add(Seq("foo", "bar", "*"), "GET", "value1") == AddResult.Ok)

      val lookup = trie.lookup(IndexedSeq("foo", "bar", "baz", "qux"))
      assert(lookup.items == Map("GET" -> "value1"))
      assert(lookup.bindings.isEmpty)
      assert(lookup.remainingPathSegments == Seq("baz", "qux"))

    test("lookup non-existing path"):
      val trie = DispatchTrie[String]()
      assert(trie.add(Seq("foo", "bar"), "GET", "value1") == AddResult.Ok)

      val lookup = trie.lookup(IndexedSeq("foo", "baz"))
      assert(lookup.items.isEmpty)
      assert(lookup.bindings.isEmpty)
      assert(lookup.remainingPathSegments == Seq("baz"))

    test("lookup empty trie"):
      val trie = DispatchTrie[String]()

      val lookup = trie.lookup(IndexedSeq("any", "path"))
      assert(lookup.items.isEmpty)
      assert(lookup.bindings.isEmpty)
      assert(lookup.remainingPathSegments == Seq("any", "path"))

    test("lookup empty path"):
      val trie = DispatchTrie[String]()
      assert(trie.add(Seq(), "GET", "rootValue") == AddResult.Ok)

      val lookup = trie.lookup(IndexedSeq())
      assert(lookup.items == Map("GET" -> "rootValue"))
      assert(lookup.bindings.isEmpty)
      assert(lookup.remainingPathSegments.isEmpty)

    test("lookup dynamic path"):
      val trie = DispatchTrie[String]()
      assert(trie.add(Seq("foo", ":id", "details"), "GET", "value3") == AddResult.Ok)

      val lookup = trie.lookup(IndexedSeq("foo", "123", "details"))
      assert(lookup.items == Map("GET" -> "value3"))
      assert(lookup.bindings == Map("id" -> "123"))
      assert(lookup.remainingPathSegments.isEmpty)

    test("multiple methods same path"):
      val trie = DispatchTrie[String]()
      assert(trie.add(Seq("foo", "bar"), "GET", "value1") == AddResult.Ok)
      assert(trie.add(Seq("foo", "bar"), "POST", "value2") == AddResult.Ok)

      val lookup = trie.lookup(IndexedSeq("foo", "bar"))
      assert(lookup.items == Map("GET" -> "value1", "POST" -> "value2"))
      assert(lookup.bindings.isEmpty)
      assert(lookup.remainingPathSegments.isEmpty)

    test("static dynamic priority"):
      val trie = DispatchTrie[String]()
      assert(trie.add(Seq("foo", "bar"), "GET", "staticValue") == AddResult.Ok)
      assert(trie.add(Seq("foo", ":id"), "GET", "dynamicValue") == AddResult.Ok)

      val lookupStatic = trie.lookup(IndexedSeq("foo", "bar"))
      assert(lookupStatic.items == Map("GET" -> "staticValue"))
      assert(lookupStatic.bindings.isEmpty)
      assert(lookupStatic.remainingPathSegments.isEmpty)

      val lookupDynamic = trie.lookup(IndexedSeq("foo", "123"))
      assert(lookupDynamic.items == Map("GET" -> "dynamicValue"))
      assert(lookupDynamic.bindings == Map("id" -> "123"))
      assert(lookupDynamic.remainingPathSegments.isEmpty)
