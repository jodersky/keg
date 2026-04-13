import utest.*

object TodoTests extends TestSuite:

  // Set the DB path via system property before the Main singleton is first
  // accessed, so it uses a fresh in-memory database for the test run.
  System.setProperty("todohtmx.db", "file::memory:?cache=shared")

  def withServer[T](f: String => T): T =
    val server = todohtmx.Main.server.copy(listeners = List(keg.Listener.Http("::1", 0)))
    server.start()
    val port = server.serverPort()
    try f(s"http://[::1]:$port")
    finally server.stop()

  /** Extract the id of a todo by matching the hx-get attribute that sits
    * directly on the same label element as the todo title. This avoids the
    * greedy cross-item matching that `id="todo-(\d+)".*?title` suffers from.
    */
  def extractId(html: String, title: String): Int =
    val pattern = s"""hx-get="/todos/(\\d+)/edit"[^>]*>$title<""".r
    pattern.findFirstMatchIn(html).get.group(1).toInt

  val tests = Tests:

    test("GET / returns the page"):
      withServer: base =>
        val r = requests.get(s"$base/")
        assert(r.statusCode == 200)
        assert(r.text().contains("<h1>todos</h1>"))

    test("add a todo"):
      withServer: base =>
        val r = requests.post(s"$base/todos",
          data = Map("title" -> "Buy milk", "filter" -> "all"))
        assert(r.statusCode == 200)
        assert(r.text().contains("Buy milk"))
        assert(r.text().contains("item left"))
        // cleanup
        val id = extractId(r.text(), "Buy milk")
        requests.delete(s"$base/todos/$id", data = Map("filter" -> "all"))

    test("empty title is ignored"):
      withServer: base =>
        // add a baseline todo so the footer appears
        val r0 = requests.post(s"$base/todos",
          data = Map("title" -> "Baseline", "filter" -> "all"))
        val countBefore = """<strong>(\d+)</strong>""".r
          .findFirstMatchIn(r0.text()).get.group(1).toInt

        val r = requests.post(s"$base/todos",
          data = Map("title" -> "   ", "filter" -> "all"))
        assert(r.statusCode == 200)
        val countAfter = """<strong>(\d+)</strong>""".r
          .findFirstMatchIn(r.text()).get.group(1).toInt
        assert(countAfter == countBefore)
        // cleanup
        val id = extractId(r.text(), "Baseline")
        requests.delete(s"$base/todos/$id", data = Map("filter" -> "all"))

    test("toggle a todo"):
      withServer: base =>
        val addR = requests.post(s"$base/todos",
          data = Map("title" -> "Toggle me", "filter" -> "all"))
        val id = extractId(addR.text(), "Toggle me")

        val r = requests.patch(s"$base/todos/$id/toggle",
          data = Map("filter" -> "all"))
        assert(r.statusCode == 200)
        assert(r.text().contains(s"""id="todo-$id" class="completed""""))
        // cleanup
        requests.delete(s"$base/todos/$id", data = Map("filter" -> "all"))

    test("delete a todo"):
      withServer: base =>
        val addR = requests.post(s"$base/todos",
          data = Map("title" -> "Delete me", "filter" -> "all"))
        val id = extractId(addR.text(), "Delete me")

        val r = requests.delete(s"$base/todos/$id",
          data = Map("filter" -> "all"))
        assert(r.statusCode == 200)
        assert(!r.text().contains("Delete me"))

    test("edit a todo title"):
      withServer: base =>
        val addR = requests.post(s"$base/todos",
          data = Map("title" -> "Old title", "filter" -> "all"))
        val id = extractId(addR.text(), "Old title")

        val r = requests.patch(s"$base/todos/$id",
          data = Map("title" -> "New title", "filter" -> "all"))
        assert(r.statusCode == 200)
        assert(r.text().contains("New title"))
        assert(!r.text().contains("Old title"))
        // cleanup
        requests.delete(s"$base/todos/$id", data = Map("filter" -> "all"))

    test("editing to empty title deletes the todo"):
      withServer: base =>
        val addR = requests.post(s"$base/todos",
          data = Map("title" -> "Vanish me", "filter" -> "all"))
        val id = extractId(addR.text(), "Vanish me")

        val r = requests.patch(s"$base/todos/$id",
          data = Map("title" -> "", "filter" -> "all"))
        assert(r.statusCode == 200)
        assert(!r.text().contains("Vanish me"))

    test("filter: active vs completed"):
      withServer: base =>
        val r1 = requests.post(s"$base/todos",
          data = Map("title" -> "Active one", "filter" -> "all"))
        val r2 = requests.post(s"$base/todos",
          data = Map("title" -> "Done one", "filter" -> "all"))
        val activeId = extractId(r1.text(), "Active one")
        val doneId   = extractId(r2.text(), "Done one")
        requests.patch(s"$base/todos/$doneId/toggle", data = Map("filter" -> "all"))

        val active = requests.get(s"$base/todos?filter=active").text()
        assert(active.contains("Active one"))
        assert(!active.contains("Done one"))

        val completed = requests.get(s"$base/todos?filter=completed").text()
        assert(!completed.contains("Active one"))
        assert(completed.contains("Done one"))
        // cleanup
        requests.delete(s"$base/todos/$activeId", data = Map("filter" -> "all"))
        requests.delete(s"$base/todos/$doneId",   data = Map("filter" -> "all"))

    test("clear completed"):
      withServer: base =>
        val r1 = requests.post(s"$base/todos",
          data = Map("title" -> "Keep me",  "filter" -> "all"))
        val r2 = requests.post(s"$base/todos",
          data = Map("title" -> "Clear me", "filter" -> "all"))
        val keepId  = extractId(r1.text(), "Keep me")
        val clearId = extractId(r2.text(), "Clear me")
        requests.patch(s"$base/todos/$clearId/toggle", data = Map("filter" -> "all"))

        val r = requests.delete(s"$base/todos/completed",
          data = Map("filter" -> "all"))
        assert(r.statusCode == 200)
        assert(r.text().contains("Keep me"))
        assert(!r.text().contains("Clear me"))
        // cleanup
        requests.delete(s"$base/todos/$keepId", data = Map("filter" -> "all"))

    test("toggle all"):
      withServer: base =>
        val r1 = requests.post(s"$base/todos",
          data = Map("title" -> "ToggleAll-A", "filter" -> "all"))
        val r2 = requests.post(s"$base/todos",
          data = Map("title" -> "ToggleAll-B", "filter" -> "all"))
        val idA = extractId(r1.text(), "ToggleAll-A")
        val idB = extractId(r2.text(), "ToggleAll-B")

        // toggle all → both completed
        val all1 = requests.post(s"$base/todos/toggle-all",
          data = Map("filter" -> "all"))
        assert(all1.text().contains(s"""id="todo-$idA" class="completed""""))
        assert(all1.text().contains(s"""id="todo-$idB" class="completed""""))

        // toggle all again → both active
        val all2 = requests.post(s"$base/todos/toggle-all",
          data = Map("filter" -> "all"))
        assert(all2.text().contains(s"""id="todo-$idA" class="""""))
        assert(all2.text().contains(s"""id="todo-$idB" class="""""))
        // cleanup
        requests.delete(s"$base/todos/$idA", data = Map("filter" -> "all"))
        requests.delete(s"$base/todos/$idB", data = Map("filter" -> "all"))
