package todohtmx

import scalatags.Text.all as h
import scalatags.Text.all.{attr, given}
import scalatags.Text.tags2.{section, title as titleTag}
import htmx as hx

case class Todo(id: Int, title: String, completed: Boolean)

object Main:

  val db = simplesql.DataSource.pooled(
    "jdbc:sqlite:" + sys.props.getOrElse("todohtmx.db", sys.env("DB"))
  )
  db.run:
    val conn = summon[simplesql.Connection]
    val stat = conn.underlying.createStatement()
    stat.execute("PRAGMA journal_mode=WAL")
    stat.execute("PRAGMA busy_timeout=5000")

  simplesql.migrations.MigrationTool.fromClasspath(db).applyUp()

  // Config that allows returning raw scalatags `Frag`s as HTML responses
  object app extends keg.derivation.Api:
    given ToResponse[h.Frag] with
      def toResponse(frag: h.Frag, ctx: Context) =
        keg.Response(
          frag.render,
          headers = Seq("Content-Type" -> "text/html; charset=utf-8")
        )

  @app.get("/static/*")
  def assets(req: keg.Request) =
    keg.model.Static.fromRequest(req, "/static", classpath = true)

  // ---------------------------------------------------------------------------
  // DB helpers
  // ---------------------------------------------------------------------------

  def fetchTodos(filter: String): Seq[Todo] =
    db.run:
      val rows = filter match
        case "active" =>
          sql"SELECT id, title, completed FROM todos WHERE completed = 0 ORDER BY id"
            .read[(Int, String, Int)]
        case "completed" =>
          sql"SELECT id, title, completed FROM todos WHERE completed = 1 ORDER BY id"
            .read[(Int, String, Int)]
        case _ =>
          sql"SELECT id, title, completed FROM todos ORDER BY id"
            .read[(Int, String, Int)]
      rows.map((id, title, c) => Todo(id, title, c != 0)).toSeq

  def countAll(): Int =
    db.run:
      sql"SELECT count(*) FROM todos".read[Int].head

  def countActive(): Int =
    db.run:
      sql"SELECT count(*) FROM todos WHERE completed = 0".read[Int].head

  def countCompleted(): Int =
    db.run:
      sql"SELECT count(*) FROM todos WHERE completed = 1".read[Int].head

  // ---------------------------------------------------------------------------
  // HTML fragments
  // ---------------------------------------------------------------------------

  def todoItemFrag(todo: Todo, filter: String): h.Frag =
    val toggleMods: Seq[h.Modifier] = Seq(
      h.cls := "toggle",
      h.`type` := "checkbox",
      hx.patch := s"/todos/${todo.id}/toggle",
      hx.target := "#app-body",
      hx.swap := "outerHTML",
      hx.vals := s"""{"filter":"$filter"}"""
    ) ++ (if todo.completed then Seq(h.checked := "") else Nil)

    h.li(
      h.id := s"todo-${todo.id}",
      h.cls := (if todo.completed then "completed" else "")
    )(
      h.div(h.cls := "view")(
        h.input(toggleMods*),
        h.label(
          hx.trigger := "dblclick",
          hx.get := s"/todos/${todo.id}/edit",
          hx.target := s"#todo-${todo.id}",
          hx.swap := "outerHTML",
          hx.vals := s"""{"filter":"$filter"}"""
        )(todo.title),
        h.button(
          h.cls := "destroy",
          hx.delete := s"/todos/${todo.id}",
          hx.target := "#app-body",
          hx.swap := "outerHTML",
          hx.vals := s"""{"filter":"$filter"}"""
        )
      )
    )

  def appBodyFrag(filter: String): h.Frag =
    val todos        = fetchTodos(filter)
    val activeCount  = countActive()
    val totalCount   = countAll()
    val allCompleted = totalCount > 0 && activeCount == 0

    val toggleAllMods: Seq[h.Modifier] = Seq(
      h.id := "toggle-all",
      h.cls := "toggle-all",
      h.`type` := "checkbox",
      hx.post := "/todos/toggle-all",
      hx.target := "#app-body",
      hx.swap := "outerHTML",
      hx.vals := s"""{"filter":"$filter"}"""
    ) ++ (if allCompleted then Seq(h.checked := "") else Nil)

    h.div(h.id := "app-body")(
      if totalCount > 0 then
        section(h.cls := "main")(
          h.input(toggleAllMods*),
          h.label(h.`for` := "toggle-all")("Mark all as complete"),
          h.ul(h.cls := "todo-list")(
            todos.map(todoItemFrag(_, filter))
          )
        )
      else "",
      if totalCount > 0 then
        h.footer(h.cls := "footer")(
          h.span(h.cls := "todo-count")(
            h.strong(activeCount.toString),
            if activeCount == 1 then " item left" else " items left"
          ),
          h.ul(h.cls := "filters")(
            Seq("all", "active", "completed").map: f =>
              h.li(
                h.a(
                  h.cls := (if f == filter then "selected" else ""),
                  h.href := "#",
                  hx.get := s"/todos?filter=$f",
                  hx.target := "#app-body",
                  hx.swap := "outerHTML",
                  hx.pushUrl := s"/?filter=$f"
                )(f.capitalize)
              )
          ),
          if countCompleted() > 0 then
            h.button(
              h.cls := "clear-completed",
              hx.delete := "/todos/completed",
              hx.target := "#app-body",
              hx.swap := "outerHTML",
              hx.vals := s"""{"filter":"$filter"}"""
            )("Clear completed")
          else ""
        )
      else ""
    )

  // ---------------------------------------------------------------------------
  // Routes
  // ---------------------------------------------------------------------------

  @app.get("/")
  def index(req: keg.Request) =
    val filter = req.params.getOrElse("filter", "all")
    h.doctype("html")(
      h.html(h.lang := "en")(
        h.head(
          h.meta(h.charset := "utf-8"),
          h.meta(h.name := "viewport", h.content := "width=device-width, initial-scale=1"),
          titleTag("TodoMVC"),
          h.link(h.rel := "stylesheet", h.href := "/static/main.css")
        ),
        h.body(
          section(h.cls := "todoapp")(
            h.header(h.cls := "header")(
              h.h1("todos"),
              h.form(
                h.cls := "new-todo-form",
                hx.post := "/todos",
                hx.target := "#app-body",
                hx.swap := "outerHTML",
                hx.vals := s"""{"filter":"$filter"}""",
                attr("hx-on::after-request") := "this.reset()"
              )(
                h.input(
                  h.cls := "new-todo",
                  h.placeholder := "What needs to be done?",
                  h.name := "title",
                  h.autofocus := true
                ),
                h.button(h.`type` := "submit", h.cls := "add-btn")("+")
              )
            ),
            appBodyFrag(filter)
          ),
          h.footer(h.cls := "info")(
            h.p("Double-click to edit a todo")
          ),
          h.script(h.src := "/static/htmx.min.js")
        )
      )
    )

  @app.get("/todos")
  def getTodosFragment(req: keg.Request) =
    val filter = req.params.getOrElse("filter", "all")
    appBodyFrag(filter)

  @app.post("/todos")
  def addTodo(form: keg.FormData) =
    val title  = form.fields.getOrElse("title", "").trim
    val filter = form.fields.getOrElse("filter", "all")
    if title.nonEmpty then
      db.run:
        sql"INSERT INTO todos (title, completed) VALUES ($title, 0)".write()
    appBodyFrag(filter)

  // NOTE: defined before /todos/:id to avoid "toggle-all" being parsed as Int
  @app.post("/todos/toggle-all")
  def toggleAll(form: keg.FormData) =
    val filter = form.fields.getOrElse("filter", "all")
    db.run:
      val active = sql"SELECT count(*) FROM todos WHERE completed = 0".read[Int].head
      if active > 0 then
        sql"UPDATE todos SET completed = 1".write()
      else
        sql"UPDATE todos SET completed = 0".write()
    appBodyFrag(filter)

  @app.delete("/todos/completed")
  def clearCompleted(form: keg.FormData) =
    val filter = form.fields.getOrElse("filter", "all")
    db.run:
      sql"DELETE FROM todos WHERE completed = 1".write()
    appBodyFrag(filter)

  @app.patch("/todos/:id/toggle")
  def toggleTodo(id: Int, form: keg.FormData) =
    val filter = form.fields.getOrElse("filter", "all")
    db.run:
      sql"UPDATE todos SET completed = 1 - completed WHERE id = $id".write()
    appBodyFrag(filter)

  @app.delete("/todos/:id")
  def deleteTodo(id: Int, form: keg.FormData) =
    val filter = form.fields.getOrElse("filter", "all")
    db.run:
      sql"DELETE FROM todos WHERE id = $id".write()
    appBodyFrag(filter)

  // Returns just the <li> with an inline edit input
  @app.get("/todos/:id/edit")
  def editTodo(req: keg.Request, id: Int) =
    val filter = req.params.getOrElse("filter", "all")
    val todoOpt = db.run:
      sql"SELECT id, title, completed FROM todos WHERE id = $id"
        .readOpt[(Int, String, Int)]
        .map((i, t, c) => Todo(i, t, c != 0))
    todoOpt match
      case None => keg.abort(404)
      case Some(todo) =>
        h.li(
          h.id := s"todo-${todo.id}",
          h.cls := "editing"
        )(
          h.input(h.`type` := "hidden", h.name := "filter", h.value := filter,
            h.id := s"filter-${todo.id}"),
          h.input(
            h.cls := "edit",
            h.name := "title",
            h.value := todo.title,
            h.autofocus := true,
            hx.patch := s"/todos/${todo.id}",
            hx.target := "#app-body",
            hx.swap := "outerHTML",
            hx.include := s"#filter-${todo.id}",
            hx.trigger := "keydown[key=='Enter'], blur"
          )
        )

  @app.patch("/todos/:id")
  def updateTodo(id: Int, form: keg.FormData) =
    val title  = form.fields.getOrElse("title", "").trim
    val filter = form.fields.getOrElse("filter", "all")
    if title.isEmpty then
      db.run:
        sql"DELETE FROM todos WHERE id = $id".write()
    else
      db.run:
        sql"UPDATE todos SET title = $title WHERE id = $id".write()
    appBodyFrag(filter)

  val routes = keg.routes()

  val server = keg.Server(routes)

  def main(args: Array[String]): Unit =
    server.start()
    sys.addShutdownHook:
      server.stop()
      server.awaitTermination()
