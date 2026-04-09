package app

// By default, keg's `FromRequest` and `ToResponse` type class instances are
// defined as `given`s in companion objects. This works well for types you own,
// but falls apart for external types (from third-party libraries or the
// standard library) whose companion objects you cannot modify.
//
// The *configuration traits* pattern solves this by collecting all instances
// in a single object that extends `keg.derivation.Api`. Because `FromRequest`
// is path-dependent on that object, instances defined inside it are scoped to
// it and do not require touching the types' companion objects.
//
// Endpoints are then annotated with `@app.get` / `@app.post` (instead of the
// global `@keg.get` / `@keg.post`) so the macro looks up `FromRequest`
// instances in the right scope.

// #### Example
//
// Suppose `Pagination` comes from an external library. We cannot add a
// `given FromRequest[Pagination]` to its companion object, so we define it inside
// our own `app` object instead. All other givens for external types go there
// too, giving us one central place to look.
//snippet:start
case class Pagination(page: Int, pageSize: Int) // imagine this is from a third-party library

object app extends keg.derivation.Api:

  // Defined here rather than in Pagination's companion object, which we don't control
  given FromRequest[Pagination] with
    def fromRequest(req: keg.Request, ctx: Context): keg.derivation.Result[Pagination] =
      val page = req.queryParams.get("page").flatMap(_.toIntOption).getOrElse(1)
      val pageSize = req.queryParams.get("pageSize").flatMap(_.toIntOption).getOrElse(10)
      keg.derivation.Result.Continue(Pagination(page, pageSize))

// Use @app.post, not @keg.post, so the macro resolves FromRequest inside `app`
@app.post("/profile")
def profile(pagination: Pagination) =
  s"Page: ${pagination.page}, Page Size: ${pagination.pageSize}\n"

val routes = keg.routes()

@main
def main() =
  val server = keg.Server(routes.endpoints)
  server.start()
//snippet:end
/* usage snippet
$ curl "http://localhost:8080/profile?page=2&pageSize=5" -X POST
Page: 2, Page Size: 5
*/
