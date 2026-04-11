package app

// Keg will automatically serialize the return value of your endpoint to the
// appropriate content type based on its type. For example, a String will be
// returned as text, a ujson.Obj will be returned as JSON, and an Array[Byte]
// will be returned as binary.
//snippet:start
@keg.get("/text")
def text() =
  "hello world"
//snippet:end
/* usage snippet
$ curl "http://localhost:8080/text" -i
...
Content-Type: text/plain; charset=UTF-8
...
hello world
*/


//snippet:start
@keg.get("/json")
def json() =
  ujson.Obj("name" -> "alice", "age" -> 42)
//snippet:end
/* usage snippet
$ curl "http://localhost:8080/json" -i
...
Content-Type: application/json; charset=UTF-8
...
{"name":"alice","age":42}
*/

//snippet:start
@keg.get("/binary")
def binary() =
  Array[Byte](1, 2, 3, 4, 5)
//snippet:end
/* usage snippet
$ curl "http://localhost:8080/binary" -i
...
Content-Type: application/octet-stream
...
*/

// You can also return a `keg.Response` directly if you want more control over
// the response, such as setting custom headers or status codes. For example,
// the following endpoint returns a JSON object with a custom status code and
// header:

//snippet:start
// override content type
@keg.get("/custom")
def custom() =
  keg.Response(
    ujson.Obj("name" -> "alice", "age" -> 42),
    status = 299,
    headers = Seq("X-Custom-Header" -> "Hello")
  )
//snippet:end
/* usage snippet
$ curl "http://localhost:8080/custom" -i
HTTP/1.1 299 Unknown
...
X-Custom-Header: Hello
...
{"name":"alice","age":42}
*/

// Keg integrates with the [geny](https://github.com/com-lihaoyi/geny)
// ecosystem, so you can return any geny.Writable as a response body, and Keg
// will stream it to the client.
// For example, using scalatags! (note that keg itself does not depend on scalatags)
//snippet:start
@keg.get("/html")
def index() =
  import scalatags.Text.all as h
  import h.given
  import scalatags.Text.tags2.title

  h.doctype("html")(
    h.html(
      h.head(
        title("Hello")
      ),
      h.body(
        h.h1("Hello, world!")
      )
    )
  )
//snippet:end
/* usage snippet
$ curl "http://localhost:8080/html" -i
...
Content-Type: text/html
...
<!DOCTYPE html><html><head><title>Hello</title></head><body><h1>Hello, world!</h1></body></html>
*/

// You can also define your own custom response types by implementing the
// `keg.ToResponse` trait for your type. For example, if we have a `HashedBlob` type:
//snippet:start
case class HashedBlob(content: String)
// we can define a `keg.ToResponse` instance for it like this:
object HashedBlob:
  given keg.default.ToResponse[HashedBlob] with
    def toResponse(blob: HashedBlob, ctx: keg.default.Context) =
      keg.Response(
        ujson.Obj("content" -> blob.content),
        headers = Seq("X-Hash" -> blob.content.hashCode.toString)
      )
// and then we can return `HashedBlob` directly from our endpoint:
@keg.get("/blob")
def hashedBlob() =
  HashedBlob("alice")
//snippet:end
/* usage snippet
$ curl "http://localhost:8080/blob" -i
HTTP/1.1 200 OK
...
X-Hash: 92903040
...
{"content":"alice"}
*/

// #### Aborting with an error response

// If you want to return an error response from your endpoint, you have two
// options:
//
// - either build a regular `keg.Response` with the desired status code and body
//   and return it directly, or
// - throw a `keg.AbortException` with the desired status code and message
//
// The former puts you in control of the entire response body, while the latter
// pushes error handling to a centralized error handler (which you can
// customize, see the Errors section).
//snippet:start
@keg.get("/check/:x")
def abort(x: Int) =
  if x < 0 then
    keg.abort(400, "x must be non-negative")
  else
    s"x is $x"
//snippet:end
/* usage snippet
$ curl "http://localhost:8080/check/5"
x is 5
*/
/* usage snippet
$ curl "http://localhost:8080/check/-1" -i
HTTP/1.1 400 Bad Request
...
x must be non-negative
*/

val routes = keg.routes()

@main
def main() =
  val server = keg.Server(routes)
  server.start()
