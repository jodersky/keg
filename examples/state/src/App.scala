package app

// State management and dependency injection is often a key part of web
// frameworks that is prone to scope creep.
//
// Keg takes on a very limited role in state management: essentially, it only
// focuses on mechanisms to inject request-scoped state. Application state is
// not managed by keg, and users are free to use whatever they wish, be it plain
// classes with parameters or anything else.
//
// The recommended way to inject request-scoped state is by using the
// configuration-trait pattern described earlier, but where the configuration
// trait contains state.
//
// For example, a typical use case is requiring a logged-in user: the
// `FromRequest[User]` instance looks up the session and either yields the user
// or short-circuits the request with a `401 Unauthorized` — similar to [request
// guards in Rocket](https://rocket.rs/guide/v0.5/requests/#request-guards).
//
//snippet:start
case class User(name: String)

object app extends keg.derivation.Api:
  // in a real application, this would likely be a database or an external service
  val userDb = collection.mutable.Map[String, User]("alice" -> User("Alice"))

  given FromRequest[User] with
    def fromRequest(req: keg.Request, ctx: Context): keg.derivation.Result[User] =
      userDb.get(req.queryParams.get("username").getOrElse("")) match
        case Some(user) => keg.derivation.Result.Continue(user)
        case None       => keg.derivation.Result.Respond(keg.Response("Unauthorized\n", 401))

@app.post("/profile")
def profile(user: User) =
  s"Hello, ${user.name}!\n"

val routes = keg.routes()

@main
def main() =
  val server = keg.Server(routes.endpoints)
  server.start()
//snippet:end
/* usage snippet
$ curl "http://localhost:8080/profile?username=alice" -X POST
Hello, Alice!
*/
/* usage snippet
$ curl "http://localhost:8080/profile?username=nobody" -X POST -i
HTTP/1.1 401 Unauthorized
...
Unauthorized
*/

// Note that you are not limited to static objects! You can make your whole
// application as modular as you like, using plain classes for dependency
// injection.
//snippet:start
class UserService:
  val userDb = collection.mutable.Map[String, User]("alice" -> User("Alice"))

class App(userService: UserService) extends keg.derivation.Api:
  given FromRequest[User] with
    def fromRequest(req: keg.Request, ctx: Context): keg.derivation.Result[User] =
      userService.userDb.get(req.queryParams.get("username").getOrElse("")) match
        case Some(user) => keg.derivation.Result.Continue(user)
        case None       => keg.derivation.Result.Respond(keg.Response("Unauthorized\n", 401))

class Routes(app: App):

  @app.post("/profile")
  def profile(user: User) =
    s"Hello, ${user.name}!\n"

  val routes = keg.routes()

def main2() =
  val userService = UserService()
  val app = App(userService)
  val routes = Routes(app).routes
  val server = keg.Server(routes.endpoints)
  server.start()
//snippet:end
