import utest.*

import keg.main.DispatchTrie
import keg.main.DispatchTrie.DispatchLookup

object DispatchTrie2Test extends TestSuite:

  /** Convenience constructor: build a trie from paths, failing the test if any
    * insertion hits an already existing entry.
    */
  def trieOf[A](entries: (Seq[String], A)*): DispatchTrie[A] =
    val trie = DispatchTrie[A]()
    for (path, value) <- entries do
      val (stored, _) = trie.getOrElseUpdate(path, value)
      assert(stored == value)
    trie

  val tests = Tests:

    test("getOrElseUpdate"):
      test("returns the inserted value and its param names"):
        val trie = DispatchTrie[String]()
        assert(trie.getOrElseUpdate(Seq("foo", ":id"), "v1") == ("v1", Seq("id")))
        assert(trie.getOrElseUpdate(Seq("foo", "bar"), "v2") == ("v2", Seq()))
        assert(trie.getOrElseUpdate(Seq(":a", "x", ":b"), "v3") == ("v3", Seq("a", "b")))

      test("returns the existing value on a second insertion"):
        val trie = DispatchTrie[String]()
        assert(trie.getOrElseUpdate(Seq("foo"), "v1") == ("v1", Seq()))
        assert(trie.getOrElseUpdate(Seq("foo"), "v2") == ("v1", Seq()))
        assert(trie.lookup(IndexedSeq("foo")).get.value == "v1")

      test("returns the caller's param names, not the registered ones"):
        // this is the point of storing only the parameter *count*: every
        // caller gets its own names back and can zip them with a lookup's
        // positional bindings
        val trie = DispatchTrie[String]()
        assert(trie.getOrElseUpdate(Seq("users", ":id"), "v1") == ("v1", Seq("id")))
        assert(trie.getOrElseUpdate(Seq("users", ":userId"), "v2") == ("v1", Seq("userId")))
        assert(trie.getOrElseUpdate(Seq("users", ":"), "v3") == ("v1", Seq("")))

      test("the value is by-name and evaluated at most once"):
        var evaluations = 0
        def mk(s: String): String =
          evaluations += 1
          s

        val trie = DispatchTrie[String]()
        assert(trie.getOrElseUpdate(Seq("foo"), mk("v1"))._1 == "v1")
        assert(evaluations == 1)
        assert(trie.getOrElseUpdate(Seq("foo"), mk("v2"))._1 == "v1")
        assert(evaluations == 1)

      test("the very same instance is returned"):
        class Box(val n: Int)
        val trie = DispatchTrie[Box]()
        val first = trie.getOrElseUpdate(Seq("foo"), Box(1))._1
        val second = trie.getOrElseUpdate(Seq("foo"), Box(2))._1
        assert(first eq second)
        assert(second.n == 1)

      test("leaves and wildcards are independent slots"):
        val trie = DispatchTrie[String]()
        assert(trie.getOrElseUpdate(Seq("foo"), "leaf") == ("leaf", Seq()))
        assert(trie.getOrElseUpdate(Seq("foo", "*"), "wild") == ("wild", Seq()))
        assert(trie.getOrElseUpdate(Seq("foo"), "leaf2") == ("leaf", Seq()))
        assert(trie.getOrElseUpdate(Seq("foo", "*"), "wild2") == ("wild", Seq()))

      test("a misplaced wildcard throws"):
        val trie = DispatchTrie[String]()
        assertThrows[IllegalArgumentException](trie.getOrElseUpdate(Seq("*", "a"), "v1"))
        assertThrows[IllegalArgumentException](trie.getOrElseUpdate(Seq("a", "*", "b"), "v1"))
        assertThrows[IllegalArgumentException](trie.getOrElseUpdate(Seq("*", "*"), "v1"))
        // a rejected insertion leaves the trie untouched
        assert(trie.lookup(IndexedSeq("a")).isEmpty)
        assert(trie.lookup(IndexedSeq("a", "b")).isEmpty)

    test("static"):
      test("basic"):
        val trie = trieOf(Seq("foo", "bar") -> "v1", Seq("foo", "baz") -> "v2")

        assert(
          trie.lookup(IndexedSeq("foo", "bar")) ==
            Some(DispatchLookup("v1", Seq(), IndexedSeq()))
        )
        assert(
          trie.lookup(IndexedSeq("foo", "baz")) ==
            Some(DispatchLookup("v2", Seq(), IndexedSeq()))
        )

      test("no match"):
        val trie = trieOf(Seq("foo", "bar") -> "v1")
        assert(trie.lookup(IndexedSeq("foo", "qux")).isEmpty)
        assert(trie.lookup(IndexedSeq("foo")).isEmpty)
        assert(trie.lookup(IndexedSeq("foo", "bar", "extra")).isEmpty)

      test("empty trie"):
        val trie = DispatchTrie[String]()
        assert(trie.lookup(IndexedSeq()).isEmpty)
        assert(trie.lookup(IndexedSeq("any", "path")).isEmpty)

      test("root path"):
        val trie = trieOf(Seq() -> "root")
        assert(trie.lookup(IndexedSeq()) == Some(DispatchLookup("root", Seq(), IndexedSeq())))
        assert(trie.lookup(IndexedSeq("a")).isEmpty)

      test("intermediate node without leaf"):
        // "foo" is only a prefix, it is not itself routable
        val trie = trieOf(Seq("foo", "bar") -> "v1")
        assert(trie.lookup(IndexedSeq("foo")).isEmpty)

    test("params"):
      test("basic"):
        val trie = trieOf(Seq("foo", ":id", "details") -> "v1")
        assert(
          trie.lookup(IndexedSeq("foo", "123", "details")) ==
            Some(DispatchLookup("v1", Seq("123"), IndexedSeq()))
        )

      test("bindings are positional and in path order"):
        val trie = trieOf(Seq(":a", "x", ":b", ":c") -> "v1")
        assert(
          trie.lookup(IndexedSeq("1", "x", "2", "3")) ==
            Some(DispatchLookup("v1", Seq("1", "2", "3"), IndexedSeq()))
        )

      test("bindings zip with the names returned at registration"):
        val trie = DispatchTrie[String]()
        val (_, names) = trie.getOrElseUpdate(Seq("users", ":user", "posts", ":post"), "v1")
        val lookup = trie.lookup(IndexedSeq("users", "bob", "posts", "7")).get
        assert(names.zip(lookup.bindings).toMap == Map("user" -> "bob", "post" -> "7"))

      test("static wins over param"):
        val trie = trieOf(Seq("foo", "bar") -> "static", Seq("foo", ":id") -> "dynamic")
        assert(trie.lookup(IndexedSeq("foo", "bar")).get.value == "static")
        assert(
          trie.lookup(IndexedSeq("foo", "123")) ==
            Some(DispatchLookup("dynamic", Seq("123"), IndexedSeq()))
        )

      test("param names are not part of the node identity"):
        // both paths address the very same leaf, no matter how they spell
        // their parameters
        val trie = trieOf(Seq("a", ":x", "p") -> "v1")
        assert(trie.getOrElseUpdate(Seq("a", ":y", "p"), "v2") == ("v1", Seq("y")))

      test("param names may differ across branches"):
        val trie = trieOf(Seq("a", ":x", "p") -> "v1", Seq("a", ":y", "q") -> "v2")
        assert(trie.lookup(IndexedSeq("a", "1", "p")).get.value == "v1")
        assert(trie.lookup(IndexedSeq("a", "1", "q")).get.value == "v2")

      test("a param matches a segment that itself looks like a param"):
        val trie = trieOf(Seq("foo", ":id") -> "v1")
        assert(trie.lookup(IndexedSeq("foo", ":id")).get.bindings == Seq(":id"))

      test("repeated param names are kept apart by position"):
        // a map would have collapsed these two
        val trie = DispatchTrie[String]()
        val (_, names) = trie.getOrElseUpdate(Seq(":x", ":x"), "v1")
        assert(names == Seq("x", "x"))
        assert(trie.lookup(IndexedSeq("a", "b")).get.bindings == Seq("a", "b"))

      test("no backtracking from a static match into a param"):
        // Documented limitation: once a static segment matched, the param
        // branch of that same level is never reconsidered, even though
        // "/a/:x/d" would have matched "/a/b/d".
        val trie = trieOf(Seq("a", "b", "c") -> "static", Seq("a", ":x", "d") -> "param")
        assert(trie.lookup(IndexedSeq("a", "b", "d")).isEmpty)
        assert(trie.lookup(IndexedSeq("a", "z", "d")).get.value == "param")

    test("wildcards"):
      test("basic"):
        val trie = trieOf(Seq("foo", "bar", "*") -> "v1")
        assert(
          trie.lookup(IndexedSeq("foo", "bar", "baz", "qux")) ==
            Some(DispatchLookup("v1", Seq(), IndexedSeq("baz", "qux")))
        )

      test("matches zero segments"):
        val trie = trieOf(Seq("foo", "*") -> "v1")
        assert(
          trie.lookup(IndexedSeq("foo")) ==
            Some(DispatchLookup("v1", Seq(), IndexedSeq()))
        )

      test("root wildcard catches everything"):
        val trie = trieOf(Seq("*") -> "v1")
        assert(trie.lookup(IndexedSeq()) == Some(DispatchLookup("v1", Seq(), IndexedSeq())))
        assert(
          trie.lookup(IndexedSeq("a", "b")) ==
            Some(DispatchLookup("v1", Seq(), IndexedSeq("a", "b")))
        )

      test("leaf wins over wildcard at the same node"):
        val trie = trieOf(Seq("foo", "*") -> "wild", Seq("foo") -> "exact")
        assert(trie.lookup(IndexedSeq("foo")).get.value == "exact")
        assert(
          trie.lookup(IndexedSeq("foo", "x")) ==
            Some(DispatchLookup("wild", Seq(), IndexedSeq("x")))
        )

      test("root leaf and root wildcard coexist"):
        val trie = trieOf(Seq() -> "root", Seq("*") -> "wild")
        assert(trie.lookup(IndexedSeq()).get.value == "root")
        assert(trie.lookup(IndexedSeq("a")).get.value == "wild")

      test("static child wins over wildcard"):
        // unlike the previous dispatch trie, a wildcard and static children
        // may live side by side
        val trie = trieOf(Seq("foo", "*") -> "wild", Seq("foo", "bar") -> "exact")
        assert(trie.lookup(IndexedSeq("foo", "bar")).get.value == "exact")
        assert(
          trie.lookup(IndexedSeq("foo", "other")) ==
            Some(DispatchLookup("wild", Seq(), IndexedSeq("other")))
        )

      test("param child wins over wildcard"):
        val trie = trieOf(Seq("foo", "*") -> "wild", Seq("foo", ":id") -> "param")
        assert(trie.lookup(IndexedSeq("foo", "x")).get.value == "param")
        assert(trie.lookup(IndexedSeq("foo", "x", "y")).get.value == "wild")

      test("backtracking to the closest wildcard"):
        val trie = trieOf(Seq("a", "*") -> "wild", Seq("a", "b", "c") -> "exact")
        assert(trie.lookup(IndexedSeq("a", "b", "c")).get.value == "exact")
        // "a/b" walks past the wildcard, then fails to find a leaf, and the
        // wildcard is used as fallback
        assert(
          trie.lookup(IndexedSeq("a", "b")) ==
            Some(DispatchLookup("wild", Seq(), IndexedSeq("b")))
        )
        // "a/b/x" matches statics for two segments before failing
        assert(
          trie.lookup(IndexedSeq("a", "b", "x")) ==
            Some(DispatchLookup("wild", Seq(), IndexedSeq("b", "x")))
        )

      test("deepest wildcard wins"):
        val trie = trieOf(
          Seq("*") -> "w0",
          Seq("a", "*") -> "w1",
          Seq("a", "b", "*") -> "w2"
        )
        assert(trie.lookup(IndexedSeq("x")) == Some(DispatchLookup("w0", Seq(), IndexedSeq("x"))))
        assert(
          trie.lookup(IndexedSeq("a", "x", "y")) ==
            Some(DispatchLookup("w1", Seq(), IndexedSeq("x", "y")))
        )
        assert(
          trie.lookup(IndexedSeq("a", "b", "x")) ==
            Some(DispatchLookup("w2", Seq(), IndexedSeq("x")))
        )
        // an exact match on "a/b" itself: no leaf, so the closest wildcard,
        // which is w2, matches zero remaining segments
        assert(
          trie.lookup(IndexedSeq("a", "b")) ==
            Some(DispatchLookup("w2", Seq(), IndexedSeq()))
        )

      test("wildcard with params"):
        val trie = trieOf(Seq("files", ":user", "*") -> "v1")
        assert(
          trie.lookup(IndexedSeq("files", "bob", "a", "b")) ==
            Some(DispatchLookup("v1", Seq("bob"), IndexedSeq("a", "b")))
        )
        assert(
          trie.lookup(IndexedSeq("files", "bob")) ==
            Some(DispatchLookup("v1", Seq("bob"), IndexedSeq()))
        )

      test("bindings collected past the wildcard are dropped"):
        // "a/1/c" binds "1" before failing on "c"; the fallback wildcard sits
        // at the root and declares no params at all
        val trie = trieOf(Seq("*") -> "wild", Seq("a", ":x", "b") -> "exact")
        assert(
          trie.lookup(IndexedSeq("a", "1", "c")) ==
            Some(DispatchLookup("wild", Seq(), IndexedSeq("a", "1", "c")))
        )
        assert(trie.lookup(IndexedSeq("a", "1", "b")).get.bindings == Seq("1"))

      test("wildcard keeps only the bindings up to its own depth"):
        val trie = trieOf(Seq(":a", "*") -> "wild", Seq(":a", ":b", "c") -> "exact")
        // "x/y/z" binds x and y, but the wildcard only declares one param
        assert(
          trie.lookup(IndexedSeq("x", "y", "z")) ==
            Some(DispatchLookup("wild", Seq("x"), IndexedSeq("y", "z")))
        )
        assert(
          trie.lookup(IndexedSeq("x", "y", "c")) ==
            Some(DispatchLookup("exact", Seq("x", "y"), IndexedSeq()))
        )

      test("a leaf always gets exactly as many bindings as it has params"):
        val trie = trieOf(Seq(":a", ":b", ":c") -> "v1", Seq(":a", "x") -> "v2")
        assert(trie.lookup(IndexedSeq("1", "2", "3")).get.bindings == Seq("1", "2", "3"))
        assert(trie.lookup(IndexedSeq("1", "x")).get.bindings == Seq("1"))

    test("odd segments"):
      test("empty segment"):
        val trie = trieOf(Seq("a", "", "b") -> "v1")
        assert(trie.lookup(IndexedSeq("a", "", "b")).get.value == "v1")
        assert(trie.lookup(IndexedSeq("a", "b")).isEmpty)

      test("empty segment is distinct from an absent one"):
        val trie = trieOf(Seq("a", "") -> "empty", Seq("a") -> "plain")
        assert(trie.lookup(IndexedSeq("a", "")).get.value == "empty")
        assert(trie.lookup(IndexedSeq("a")).get.value == "plain")

      test("segments are not interpreted"):
        // no url decoding, no dot-segment collapsing, nothing
        val trie = trieOf(Seq("a", "..", "b%2Fc", "d e") -> "v1")
        assert(trie.lookup(IndexedSeq("a", "..", "b%2Fc", "d e")).get.value == "v1")

      test("a param may be named like a wildcard"):
        val trie = DispatchTrie[String]()
        assert(trie.getOrElseUpdate(Seq("a", ":*"), "v1") == ("v1", Seq("*")))
        assert(trie.lookup(IndexedSeq("a", "x")).get.bindings == Seq("x"))

      test("long path"):
        val path = (1 to 1000).map(i => s"s$i")
        val trie = trieOf(path -> "deep")
        assert(trie.lookup(path.toIndexedSeq).get.value == "deep")
        assert(trie.lookup(path.init.toIndexedSeq).isEmpty)

    test("mixed routing table"):
      val trie = trieOf(
        Seq() -> "index",
        Seq("api", "users") -> "users",
        Seq("api", "users", ":id") -> "user",
        Seq("api", "users", ":id", "posts", ":post") -> "post",
        Seq("api", "users", "me") -> "me",
        Seq("api", "*") -> "api-fallback",
        Seq("static", "*") -> "assets",
        Seq("*") -> "notFound"
      )

      assert(trie.lookup(IndexedSeq()).get.value == "index")
      assert(trie.lookup(IndexedSeq("api", "users")).get.value == "users")
      assert(trie.lookup(IndexedSeq("api", "users", "me")).get.value == "me")
      assert(
        trie.lookup(IndexedSeq("api", "users", "42")) ==
          Some(DispatchLookup("user", Seq("42"), IndexedSeq()))
      )
      assert(
        trie.lookup(IndexedSeq("api", "users", "42", "posts", "7")) ==
          Some(DispatchLookup("post", Seq("42", "7"), IndexedSeq()))
      )
      // "me" is static, so it never reaches the param branch below it
      assert(trie.lookup(IndexedSeq("api", "users", "me", "posts", "7")).get.value == "api-fallback")
      assert(
        trie.lookup(IndexedSeq("api", "unknown", "thing")) ==
          Some(DispatchLookup("api-fallback", Seq(), IndexedSeq("unknown", "thing")))
      )
      assert(
        trie.lookup(IndexedSeq("static", "css", "main.css")) ==
          Some(DispatchLookup("assets", Seq(), IndexedSeq("css", "main.css")))
      )
      assert(
        trie.lookup(IndexedSeq("nope")) ==
          Some(DispatchLookup("notFound", Seq(), IndexedSeq("nope")))
      )

    test("shared node, different param names per registration"):
      // the scenario the value type is meant for: several endpoints share one
      // trie node, each with its own spelling of the parameters
      val trie = DispatchTrie[collection.mutable.Map[String, Seq[String]]]()

      def register(method: String, path: Seq[String]): Unit =
        val (methods, names) = trie.getOrElseUpdate(path, collection.mutable.Map.empty)
        methods(method) = names

      register("GET", Seq("users", ":id", "posts", ":post"))
      register("PUT", Seq("users", ":userId", "posts", ":postId"))

      val lookup = trie.lookup(IndexedSeq("users", "bob", "posts", "7")).get
      assert(lookup.bindings == Seq("bob", "7"))
      assert(
        lookup.value("GET").zip(lookup.bindings).toMap ==
          Map("id" -> "bob", "post" -> "7")
      )
      assert(
        lookup.value("PUT").zip(lookup.bindings).toMap ==
          Map("userId" -> "bob", "postId" -> "7")
      )

    test("non string values"):
      val trie = trieOf(Seq("a") -> 1, Seq("b", "*") -> 2)
      assert(trie.lookup(IndexedSeq("a")).get.value == 1)
      assert(trie.lookup(IndexedSeq("b", "c")).get.value == 2)
