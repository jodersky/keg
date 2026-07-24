package app

// #### Defining routes
//
// A method annotated with `@keg.get` will be called when a GET request to the
// specified path is made.
//snippet:start
@keg.get("/hello")
def hello() = "Hello, world!"
//snippet:end
/* usage snippet
$ curl "http://localhost:8080/hello"
Hello, world!
*/

// Keg has an annotation for each common HTTP method:
//snippet:start
@keg.post("/post")
def post() = "This is a POST request"
@keg.put("/put")
def put() = "This is a PUT request"
@keg.delete("/delete")
def delete() = "This is a DELETE request"
//snippet:end
/* usage snippet
$ curl "http://localhost:8080/post" -X POST
This is a POST request
*/
/* usage snippet
$ curl "http://localhost:8080/put" -X PUT
This is a PUT request
*/
/* usage snippet
$ curl "http://localhost:8080/delete" -X DELETE
This is a DELETE request
*/

// However, you can also use the more general `@keg.request` annotation to
// specify the path and method(s) directly
//snippet:start
@keg.request("/generic", methods = Seq("GET", "POST", "custommethod"))
def foo2() = "bar"
//snippet:end
/* usage snippet
$ curl "http://localhost:8080/generic" -X custommethod
bar
*/

// #### Path parameters
//
// Paths can contain parameters, which are specified by prefixing a path segment
// with `:`. The parameter value will be passed as an argument to the method,
// and automatically converted to the appropriate type (e.g. Int, String, etc.).
//snippet:start
@keg.get("/hello/:name")
def helloName(name: String) =
  s"Hello, $name!"
//snippet:end
/* usage snippet
$ curl "http://localhost:8080/hello/alice"
Hello, alice!
*/

//snippet:start
@keg.get("/add/:x/:y")
def add(x: Int, y: Int) =
  s"$x + $y = ${x + y}"
//snippet:end
/* usage snippet
$ curl "http://localhost:8080/add/3/4"
3 + 4 = 7
*/
// Invalid input will result in a 400 Bad Request response without us having to
// write any error handling code in the method itself!
/* usage snippet
$ curl "http://localhost:8080/add/3/foo" -i
HTTP/1.1 400 Bad Request
...
Parameter 'y' is invalid: Invalid integer in path
...
*/

// Creating custom types for path parameters is also easy. Just define an
// instance of `keg.FromPath` for your type, and Keg will automatically use it
// to parse path parameters of that type. For example, if we have a `UserId` type:
//snippet:start
case class UserId(value: String)
// we can define a `keg.FromPath` instance for it like this:
object UserId:
  given keg.default.FromPath[UserId] with
    def fromPath(segment: String, ctx: keg.default.Context) =
      if segment.startsWith("abc") then
        keg.derivation.Result.Continue(UserId(segment))
      else
        keg.derivation.Result.Abort(400, "User ID must start with 'abc'")
// and then we can use `UserId` as a parameter type in our endpoint:
@keg.get("/users/:id")
def getUser(id: UserId) =
  s"User ID: ${id.value}"
//snippet:end
/* usage snippet
$ curl "http://localhost:8080/users/abc123"
User ID: abc123
*/
/* usage snippet
$ curl "http://localhost:8080/users/xyz123" -i
HTTP/1.1 400 Bad Request
...
Parameter 'id' is invalid: User ID must start with 'abc'
*/

// #### Wildcard parameters
//
// You can also define wildcard parameters that match the rest of the path using
// `*`. For example, the following endpoint will match any path that starts with
// `/files/` and pass the rest of the path as the `path` parameter:
//snippet:start
@keg.get("/files/*")
def files(req: keg.Request) =
  // accessing wildcard parameters requires using `keg.Request` instead of typed
  // method parameters; this will be discussed in more detail in the next
  // section
  s"Requested file: ${req.remainingPathSegments.mkString("/")}"
//snippet:end
/* usage snippet
$ curl "http://localhost:8080/files/path/to/file.txt"
Requested file: path/to/file.txt
*/

// #### Overlapping routes
//
// Keg uses a trie-based router, similar to cask's. This means that all routes
// must be unique and that they may not overlap with wildcards. This is checked
// at server construction time.
//
// There is one exception however: it's possible to have a static and dynamic
// route overlap, in which case the static route will take precedence over the
// dynamic one. This allows you to build applications with "vanity" URLs, for
// example a GitHub-style web interface for user profiles:
//snippet:start
@keg.get("/:username")
def userProfile(username: String) =
  s"User profile for ${username}"
@keg.get("/settings")
def settings() =
  "User settings"
//snippet:end
/* usage snippet
$ curl "http://localhost:8080/alice"
User profile for alice
*/
/* usage snippet
$ curl "http://localhost:8080/settings"
User settings
*/
// If you use this pattern, it is up to you to ensure that no users can register
// with a username that conflicts with one of your static routes (e.g.
// "settings" in the example above).

val routes = keg.routes()
val server: keg.Server = keg.Server(routes, debugMode = true)

@main def main() = server.start()
