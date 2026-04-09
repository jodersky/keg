package app

/* clear cookies at start of test */
/* usage
rm -f /tmp/cookies.txt
*/

// Cookies are an essential part of web development, allowing you to store data
// on the client and have it sent back to the server. Keg has built-in support
// for working with cookies.
//snippet:start
@keg.get("/get")
def hello(req: keg.Request) =
  val savedData: Option[keg.Cookie] = req.cookies.get("data")
  savedData match
    case Some(cookie) =>
      s"hello again! you previously saved: ${cookie.value}\n"
    case None =>
      s"hello, nice to meet you! you haven't saved anything yet.\n"

@keg.post("/post")
def save(req: keg.Request) =
  val data = req.text()
  // set a cookie with the saved data; this will be sent back to the client in
  // the response, and the client will send it back in future requests
  keg.Response(
    s"saved: $data\n",
    cookies = Seq(keg.Cookie("data", data, path = "/"))
  )
//snippet:end
/* usage snippet
$ curl "http://localhost:8080/get"
hello, nice to meet you! you haven't saved anything yet.
*/
/* usage snippet
$ curl "http://localhost:8080/post" -d "my secret data" --cookie-jar /tmp/cookies.txt
saved: my secret data
*/
/* usage snippet
$ curl "http://localhost:8080/get" --cookie /tmp/cookies.txt
hello again! you previously saved: my secret data
*/

// A common pattern is to use cookies to store session data on the client,
// essentially long-lived data that is associated with a particular user.
// Instead of wrangling raw cookies directly for this, Keg provides a convenient
// `session` abstraction that allows you to store and access key-value pairs.
//
// > [!NOTE]
// >
// > Session data is signed by the server, but not encrypted. This means that
// > someone can't forge key-value pairs sent to the server, but they can read
// > them if they have access to the cookie.
//snippet:start
@keg.get("/session")
def session(req: keg.Request) =
  val data: Map[String, String] = req.session
  val userId = data.getOrElse("userId", "unknown")
  s"your user ID is: $userId\n"

@keg.post("/login")
def login(req: keg.Request) =
  // in a real application, you would verify the user's credentials and generate
  // a unique user ID; for this example, we'll just use a fixed user ID
  val userId = "user-123"
  // set the user ID in the session; this will be stored server-side and associated with the client via a session cookie
  keg.Response(
    s"logged in as user ID: $userId\n",
    session = Map("userId" -> userId)
  )
//snippet:end
/* usage snippet
$ curl "http://localhost:8080/session"
your user ID is: unknown
*/
/* usage snippet
$ curl "http://localhost:8080/login" -X POST --cookie-jar /tmp/cookies.txt
logged in as user ID: user-123
*/
/* usage snippet
$ curl "http://localhost:8080/session" --cookie /tmp/cookies.txt
your user ID is: user-123
*/

// A flash is a special type of session data that is only available for the next
// request; after that, it is automatically cleared. This is useful for storing
// temporary messages that you want to show to the user after a redirect, as is
// common in "classic" web applications. Keg has built-in support for flash
// messages as well, which work similarly to sessions but are automatically
// cleared after the next request.
//snippet:start
@keg.get("/flash")
def flash(req: keg.Request) =
  val message: Option[String] = req.flash.get("message")
  message match
    case Some(msg) =>
      s"flash message: $msg\n"
    case None =>
      s"no flash message\n"

@keg.get("/set-flash")
def setFlash() =
  // set a flash message; this will be stored server-side and associated with
  // the client via a flash cookie, but it will only be available for the next
  // request; after that, it will be automatically cleared
  keg.Response(
    "flash message set\n",
    status = 302,
    flash = Map("message" -> "this is a flash message"),
    headers = Seq("location" -> "/flash") // typically you would set a flash message and then redirect the client to another page where the message will be displayed
  )
//snippet:end
/* usage snippet
$ curl "http://localhost:8080/flash"
no flash message
*/
/* usage snippet
$ curl "http://localhost:8080/set-flash" --cookie-jar /tmp/cookies.txt --cookie /tmp/cookies.txt --location
flash message: this is a flash message
*/
/* usage snippet
$ curl "http://localhost:8080/flash" --cookie /tmp/cookies.txt
no flash message
*/

val routes = keg.routes()

@main
def main() =
  val server = keg.Server(routes.endpoints)
  server.start()
