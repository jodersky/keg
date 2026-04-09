# keg

A small web framework, merging ideas from
[flask](https://flask.palletsprojects.com/en/stable/),
[rocket](https://rocket.rs/) and especially reusing many ideas from
[cask](https://github.com/com-lihaoyi/cask).



```scala
@keg.post("/hello/:n")
def hello(n: Int, req: keg.Request) =
  s"hello ${req.text()}\n" * n

val server: keg.Server = keg.Server(keg.routes().endpoints)

@main
def main() =
  server.start()
```


```
$ curl "http://localhost:8080/hello/5" -d "alice"
hello alice
hello alice
hello alice
hello alice
hello alice
```


Keg aims to make writing HTTP applications in Scala a breeze. It is designed for
writing full classic web apps or specialized application programming interfaces
(APIs) alike. It includes most features you'd expect in a web server, yet stays
minimal and strives to make development fun. It should feel ergonomic and not
impose how you build your application.

Keg is NOT a full-featured framework, which imposes an application structure,
such as Django or Play! You can use it as a library if you like.

## Differences with cask

Given the name "Keg" is a nod to "Cask", there is no surprise that it resembles
the latter. Keg started out as a heavily customized version of cask, which I
used internally for some services, and ended up ripping out. What you see here
is essentially that, cleaned up a little.

This repository serves to showcase some ideas of keg. It may be possible to
upstream changes back into cask (although it would change many core cask APIs).

The original cask customizations were the following:

- Keg's focus is around extensible request-scoped state (e.g. adding the logged
  in user to a request, or checking authorization).

  - Defining cask decorators as member types of a configuration trait and making
    input parsing path-dependent. This gave me an easy way to define typeclasses
    for various parameter types.

- A server available under scalanative

These then led to a more. [See here](./cask-diff.md) for a description of most
changes and their rationales.

## Managing state

State management and dependency injection is often a key part of web frameworks
that is prone to scope creep.

Keg takes on a very limited role in state management: essentially, it only
focuses on mechanisms to inject request-scoped state. Application state is not
managed by keg, and users are free to use whatever they wish, be it plain
classes with parameters or anything else.

## Feature overview

- [Basics](#basics)
- [Request routing](#request-routing)
- [Reading request data](#reading-request-data)
- [Returning responses](#returning-responses)
- [Error handling](#error-handling)
- [Websockets](#websockets)
- [Cookies, session, and flash data](#cookies-session-and-flash-data)
- [TLS and mTLS](#tls-and-mtls)
- [Raw endpoints](#raw-endpoints)
- [Interceptors](#interceptors)
- [Transports](#transports)
- [Custom config traits](#custom-config-traits)
- [Managing state](#managing-state-1)
- [Forms](#forms)
- [JSON](#json)

### Basics


A keg application requires two things:
1. a set of routes, which are defined by annotating methods
2. a server, which is created from the routes and listens for incoming requests

```scala
// define routes by annotating methods
@keg.get("/hello")
def hello() =
  s"hello, world!"

@keg.post("/echo")
def echo(req: keg.Request) =
  req.text()

// use the keg.routes() macro to collect all annotated methods into a set of
// routes
val routes: keg.model.Routes = keg.routes()

// finally, create a server from the routes
val server: keg.Server = keg.Server(routes.endpoints)

@main
def main() =
  // start the server to listen for incoming requests
  server.start()

  // not necessary, but this lets you gracefully shut down the server,
  // preventing new requests from being accepted and waiting for existing
  // requests to finish before exiting the application
  sys.addShutdownHook:
    server.stop()
    server.awaitTermination()
```


```
$ curl "http://localhost:8080/hello"
hello, world!
```

```
$ curl "http://localhost:8080/echo" -d "hello"
hello
```


By default the server listens on port 8080. You can change this by setting
he `listeners` parameter when creating the server.

```scala
val serverWithConfig = keg.Server(
  routes.endpoints,
  listeners = Seq(
    keg.Listener.Http("::", 8081),
    keg.Listener.Http("::1", 8082), // you can even listen on multiple ports!
    keg.Listener.Https("::", 8443, key = keyPath, cert = certPath) // see the TLS example for more info on HTTPS
  )
)
```


### Request routing


#### Defining routes

A method annotated with `@keg.get` will be called when a GET request to the
specified path is made.

```scala
@keg.get("/hello")
def hello() = "Hello, world!"
```

```
$ curl "http://localhost:8080/hello"
Hello, world!
```

Keg has an annotation for each common HTTP method:

```scala
@keg.post("/post")
def post() = "This is a POST request"
@keg.put("/put")
def put() = "This is a PUT request"
@keg.delete("/delete")
def delete() = "This is a DELETE request"
```

```
$ curl "http://localhost:8080/post" -X POST
This is a POST request
```

```
$ curl "http://localhost:8080/put" -X PUT
This is a PUT request
```

```
$ curl "http://localhost:8080/delete" -X DELETE
This is a DELETE request
```

However, you can also use the more general `@keg.request` annotation to
specify the path and method(s) directly

```scala
@keg.request("/generic", methods = Seq("GET", "POST", "custommethod"))
def foo2() = "bar"
```

```
$ curl "http://localhost:8080/generic" -X custommethod
bar
```

#### Path parameters

Paths can contain parameters, which are specified by prefixing a path segment
with `:`. The parameter value will be passed as an argument to the method,
and automatically converted to the appropriate type (e.g. Int, String, etc.).

```scala
@keg.get("/hello/:name")
def helloName(name: String) =
  s"Hello, $name!"
```

```
$ curl "http://localhost:8080/hello/alice"
Hello, alice!
```


```
$ curl "http://localhost:8080/add/3/4"
3 + 4 = 7
```
Invalid input will result in a 400 Bad Request response without us having to
write any error handling code in the method itself!

```
$ curl "http://localhost:8080/add/3/foo" -i
HTTP/1.1 400 Bad Request
...
Parameter 'y' is invalid: Invalid integer in path
...
```

Creating custom types for path parameters is also easy. Just define an
instance of `keg.FromPath` for your type, and Keg will automatically use it
to parse path parameters of that type. For example, if we have a `UserId` type:

```scala
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
```

```
$ curl "http://localhost:8080/users/abc123"
User ID: abc123
```

```
$ curl "http://localhost:8080/users/xyz123" -i
HTTP/1.1 400 Bad Request
...
Parameter 'id' is invalid: User ID must start with 'abc'
```

#### Wildcard parameters

You can also define wildcard parameters that match the rest of the path using
`*`. For example, the following endpoint will match any path that starts with
`/files/` and pass the rest of the path as the `path` parameter:

```scala
@keg.get("/files/*")
def files(req: keg.Request) =
  // accessing wildcard parameters requires using `keg.Request` instead of typed
  // method parameters; this will be discussed in more detail in the next
  // section
  s"Requested file: ${req.remainingPathSegments.mkString("/")}"
```

```
$ curl "http://localhost:8080/files/path/to/file.txt"
Requested file: path/to/file.txt
```

#### Overlapping routes

Keg uses a trie-based router, similar to cask's. This means that all routes
must be unique and that they may not overlap with wildcards. This is checked
at server construction time.

There is one exception however: it's possible to have a static and dynamic
route overlap, in which case the static route will take precedence over the
dynamic one. This allows you to build applications with "vanity" URLs, for
example a GitHub-style web interface for user profiles:

```scala
@keg.get("/:username")
def userProfile(username: String) =
  s"User profile for ${username}"
@keg.get("/settings")
def settings() =
  "User settings"
```

```
$ curl "http://localhost:8080/alice"
User profile for alice
```

```
$ curl "http://localhost:8080/settings"
User settings
```
If you use this pattern, it is up to you to ensure that no users can register
with a username that conflicts with one of your static routes (e.g.
"settings" in the example above).




### Reading request data


You can access the HTTP request by adding a parameter of type `keg.Request`
to the method:

```scala
@keg.post("/req")
def req(req: keg.Request) =
  s"Method: ${req.method}, Path: ${req.path}, Body: ${req.text()}"
```

```
$ curl "http://localhost:8080/req" -d "hello"
Method: POST, Path: /req, Body: hello
```

The `keg.Request` class gives you access to all parts of an HTTP request,
including headers, query parameters, etc. It's technically enough to
implement an endpoint, but it's usually more convenient to work with
higher-level abstractions that do validation and parsing for you.

Keg has built-in support for parsing many common types, including:
- form data (url-encoded and multipart, including file uploads)
- JSON (using ujson)


```scala
@keg.post("/form")
def form(data: keg.FormData) =
  data.fields.toString

@keg.post("/json")
def json(data: ujson.Obj) =
  data

@keg.post("/json-any")
def jsonAny(data: ujson.Value) =
  data
```




```
$ curl "http://localhost:8080/form" -d "name=alice&age=42"
Map(name -> alice, age -> 42)
```


```
$ curl "http://localhost:8080/json" -d "{\"name\": \"alice\", \"age\": 42}"
{"name":"alice","age":42}
```

Notice how invalid input results in a 400 Bad Request response without us
having to write any error handling code in the method itself!

```
$ curl "http://localhost:8080/json" -d "[1, 2, 3] -i"
Bad Request
```


```
$ curl "http://localhost:8080/json-any" -d "[1, 2, 3]"
[1,2,3]
```


### Returning responses


Keg will automatically serialize the return value of your endpoint to the
appropriate content type based on its type. For example, a String will be
returned as text, a ujson.Obj will be returned as JSON, and an Array[Byte]
will be returned as binary.

```scala
@keg.get("/text")
def text() =
  "hello world"
```

```
$ curl "http://localhost:8080/text" -i
...
Content-Type: text/plain; charset=UTF-8
...
hello world
```



```scala
@keg.get("/json")
def json() =
  ujson.Obj("name" -> "alice", "age" -> 42)
```

```
$ curl "http://localhost:8080/json" -i
...
Content-Type: application/json
...
{"name":"alice","age":42}
```


```scala
@keg.get("/binary")
def binary() =
  Array[Byte](1, 2, 3, 4, 5)
```

```
$ curl "http://localhost:8080/binary" -i
...
Content-Type: application/octet-stream
...
```

You can also return a `keg.Response` directly if you want more control over
the response, such as setting custom headers or status codes. For example,
the following endpoint returns a JSON object with a custom status code and
header:


```scala
// override content type
@keg.get("/binaryJson")
def binaryJson() =
  keg.Response(
    ujson.Obj("name" -> "alice", "age" -> 42),
    status = 299,
    headers = Seq("X-Custom-Header" -> "Hello")
  )
```

```
$ curl "http://localhost:8080/binaryJson" -i
HTTP/1.1 299 Unknown
...
X-Custom-Header: Hello
...
{"name":"alice","age":42}
```

Keg integrates with the [geny](https://github.com/com-lihaoyi/geny)
ecosystem, so you can return any geny.Writable as a response body, and Keg
will stream it to the client.
For example, using scalatags! (note that keg itself does not depend on scalatags)

```scala
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
```

```
$ curl "http://localhost:8080/html" -i
...
Content-Type: text/html
...
<!DOCTYPE html><html><head><title>Hello</title></head><body><h1>Hello, world!</h1></body></html>
```

You can also define your own custom response types by implementing the
`keg.ToResponse` trait for your type. For example, if we have a `HashedBlob` type:

```scala
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
```

```
$ curl "http://localhost:8080/blob" -i
HTTP/1.1 200 OK
...
X-Hash: 92903040
...
{"content":"alice"}
```

#### Aborting with an error response

If you want to return an error response from your endpoint, you have two
options:

- either build a regular `keg.Response` with the desired status code and body
  and return it directly, or
- throw a `keg.AbortException` with the desired status code and message

The former puts you in control of the entire response body, while the latter
pushes error handling to a centralized error handler (which you can
customize, see the Errors section).

```scala
@keg.get("/check/:x")
def abort(x: Int) =
  if x < 0 then
    keg.abort(400, "x must be non-negative")
  else
    s"x is $x"
```

```
$ curl "http://localhost:8080/check/5"
x is 5
```

```
$ curl "http://localhost:8080/check/-1" -i
HTTP/1.1 400 Bad Request
...
x must be non-negative
```




### Error handling


Error handling in keg is also done through annotated methods. These methods
will be called when the corresponding error occurs, and can be used to return
custom error messages or pages.

Annotating a method with `@keg.notFound` will cause it to be called whenever
a request is made to a path that doesn't match any of the defined endpoints.
You can access the request and other parameters just like with regular
endpoint annotations.

```scala
@keg.notFound()
def notFound(req: keg.Request) =
  keg.Response(
    s"custom not found: ${req.path}\n",
    404
  )
```

```
$ curl "http://localhost:8080/doesnotexist"
custom not found: /doesnotexist
```

Similarly, there's a `@keg.methodNotAllowed` annotation for handling requests
that match a path but not the method of any defined endpoint.

```scala
@keg.methodNotAllowed()
def methodNotAllowed(req: keg.Request) =
  keg.Response(
    s"custom method not allowed: ${req.path}\n",
    405
  )

@keg.get("/foo")
def foo() = "bar"
```

```
$ curl "http://localhost:8080/foo" -X POST
custom method not allowed: /foo
```

Finally, there's `@keg.error()` for handling actual errors that occur while
processing a request.

This annotation is a bit special, in that it does not accept arbitrary
arguments, but may only take arguments of the following types:
- `keg.Request` (the request that caused the error)
- `keg.Endpoint` (the endpoint that generated the error)
- `keg.EndpointResult.Failure` (the type of error that occurred)

The `keg.EndpointResult.Failure` has three subtypes:
- `keg.EndpointResult.ParamAbort(param, code, message)` Something went wrong
  in the `FromRequest` instance for one of the endpoint's parameters. The
  failure includes a suggested HTTP status code and error message.
- `keg.EndpointResult.MethodAbort(code, message)` The endpoint rejected the
  request with a specific status code and message.
- `keg.EndpointResult.Exception(ex)` An exception was thrown while processing
  the request

```scala
// @keg.error()
def errorHandler(
  req: keg.Request,
  ep: keg.Endpoint,
  failure: keg.EndpointResult.Failure
) =

  keg.Response(
    s"custom error handler: ${failure}\n",
    500
  )

val routes = keg.routes()

@main
def main() =
  val server = keg.Server(routes.endpoints)
  server.start()
```


### Websockets


Websocket support is more-or-less copied directly from cask.


```scala
@keg.websocket("/hello/:x")
def hello(x: String): keg.WebResponse =
  if x != "secret" then
    // you can also return a normal HTTP response in a websocket handler if you
    // want to
    keg.Response("Unauthorized", status = 401)
  else
    keg.WsResponse: channel =>
      channel.send(keg.Ws.Text("hello world"))

      keg.WsActor:
        case keg.Ws.Text(txt) =>
          channel.send(keg.Ws.Text(s"echo: $txt"))
        case keg.Ws.Close(code, reason) =>
          println(s"Received close: $code - $reason")
        case keg.Ws.ChannelClosed() =>
          println("WebSocket channel closed")
```

One thing which is different about keg however, is that not `actorContext` is
needed to create a `WsActor`. Keg does this by injecting a context with a
context function into `keg.WsResponse(ctx ?=> channel => actor)`.



### Cookies, session, and flash data



Cookies are an essential part of web development, allowing you to store data
on the client and have it sent back to the server. Keg has built-in support
for working with cookies.

```scala
@keg.get("/get")
def hello(req: keg.Request) =
  val savedData: Option[keg.Cookie] = req.cookies.get("data")
  savedData match
    case Some(cookie) =>
      s"hello again! you previously saved: ${cookie.value}\n"
    case None =>
      s"hello, nice to meet you! you haven't saved anything yet.\n"

@keg.post("/post")
def save(data: String) =
  // set a cookie with the saved data; this will be sent back to the client in
  // the response, and the client will send it back in future requests
  keg.Response(
    s"saved: $data\n",
    cookies = Seq(keg.Cookie("data", data, path = "/"))
  )
```

```
$ curl "http://localhost:8080/get"
hello, nice to meet you! you haven't saved anything yet.
```

```
$ curl "http://localhost:8080/post" -d "my secret data" --cookie-jar /tmp/cookies.txt
saved: my secret data
```

```
$ curl "http://localhost:8080/get" --cookie /tmp/cookies.txt
hello again! you previously saved: my secret data
```

A common pattern is to use cookies to store session data on the client,
essentially long-lived data that is associated with a particular user.
Instead of wrangling raw cookies directly for this, Keg provides a convenient
`session` abstraction that allows you to store and access key-value pairs.

> [!NOTE]
>
> Session data is signed by the server, but not encrypted. This means that
> someone can't forge key-value pairs sent to the server, but they can read
> them if they have access to the cookie.

```scala
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
```

```
$ curl "http://localhost:8080/session"
your user ID is: unknown
```

```
$ curl "http://localhost:8080/login" -X POST --cookie-jar /tmp/cookies.txt
logged in as user ID: user-123
```

```
$ curl "http://localhost:8080/session" --cookie /tmp/cookies.txt
your user ID is: user-123
```

A flash is a special type of session data that is only available for the next
request; after that, it is automatically cleared. This is useful for storing
temporary messages that you want to show to the user after a redirect, as is
common in "classic" web applications. Keg has built-in support for flash
messages as well, which work similarly to sessions but are automatically
cleared after the next request.

```scala
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
```

```
$ curl "http://localhost:8080/flash"
no flash message
```

```
$ curl "http://localhost:8080/set-flash" --cookie-jar /tmp/cookies.txt --cookie /tmp/cookies.txt --location
flash message: this is a flash message
```

```
$ curl "http://localhost:8080/flash" --cookie /tmp/cookies.txt
no flash message
```




### TLS and mTLS



Support for HTTPS is built in. You just need to provide a certificate and key
as standard PEM files, and configure the server to listen for HTTPS requests.

```scala
@keg.get("/hello")
def hello() =
  "hello world\n"

val server = keg.Server(
  keg.routes().endpoints,
  listeners = Seq(
    keg.Listener.Https(
      host = "::",
      port = 8443,
      key = Path.of("creds/server.key"),
      cert = Path.of("creds/server.crt")
    )
  )
)
```

```
$ curl "https://localhost:8443/hello" --cacert creds/server-ca.crt
hello world
```

It's also possible to use a client certificate for authentication. Add the
clientCa` parameter:

```scala
val serverWithClientAuth = keg.Server(
  keg.routes().endpoints,
  listeners = Seq(
    keg.Listener.Https(
      host = "::",
      port = 8443,
      key = Path.of("creds/server.key"),
      cert = Path.of("creds/server.crt"),
      clientCa = Some(Path.of("creds/client-ca.crt"))
    )
  )
)

// You can then access the client certificate in your endpoints.
@keg.get("/whoami")
def whoami(req: keg.Request) =
  req.clientCertificate match
    case Some(cert) => s"Your client certificate subject is: ${cert.subject}\n"
    case None => "No client certificate provided\n"
```

```
$ curl "https://localhost:8443/whoami" --cacert creds/server-ca.crt --cert creds/client.crt --key creds/client.key
Your client certificate subject is: CN=client
```

> [!IMPORTANT]
>
> **It is up to your application to check for the presence of a client
> certificate**, keg will still accept requests without a client certificate.
> It only guarantees that if `request.clientCertificate` is `Some(cert)`
> that it has been signed by the specified CA and is still valid.



### Raw endpoints


Keg is structured as a layered framework. At the very top, you write your
application code by annotating methods with `@keg.get`, `@keg.post`, etc.
This is the most convenient way to write your application, but isn't what's
actually happening under the hood. Keg actually uses a different
representation for your endpoints. The annotations are just a thin layer that
generates the underlying data structures for you.

If you want more control, you can skip the annotations and write the
underlying data structures directly. This is more verbose, but can be useful
for advanced use cases, such as building endpoints programmatically.


```scala
val endpoints = Seq(
  keg.Endpoint(
    selector = keg.EndpointSelector.Simple("/", Seq("GET")),
    invoke = _ =>
      keg.model.EndpointResult.Success(
        keg.Response("Hello, World!", 200)
      )
  ),
  keg.Endpoint(
    selector = keg.EndpointSelector.Simple("/echo", Seq("POST")),
    invoke = req =>
      val body = new String(req.body.readAllBytes(), "UTF-8")
      keg.model.EndpointResult.Success(
        keg.Response(s"Echo: $body", 200)
      )
  ),
  keg.Endpoint(
    selector = keg.EndpointSelector.Simple("/fail", Seq("GET")),
    invoke = _ =>
      keg.model.EndpointResult.Failure.MethodAbort(418, "I'm a teapot")
  ),
  keg.Endpoint(
    selector = keg.EndpointSelector.NotFound,
    invoke = (req) =>
      keg.model.EndpointResult.Success(
        keg.Response("Custom 404 Not Found", 404)
      )
  ),
  keg.model.Recovery(
    invoke = (req, ep, err) =>
      keg.Response(s"Custom error page: ${err}", 500)
  )
)

val server = keg.Server(endpoints)
```



### Interceptors

derivation <-- "user" API, annotated methods Req => Response
endpoints  <-- what the annotated methods generate, this is essentially the same as the user api but without syntax sugar Req => EndpointResult
interceptors <-- opaque to application logic, Req => Response

### Transports

Keg itself does not implement the actual handling of network requests. Instead,
it factors those out to so-called *transports*. A transport is the subsystem
which handles the low-level reading and writing of network streams which make up
the HTTP protocol.

Keg is structured in such a way that the transport for a keg server can be
swapped out. Or, put differently, you can essentially think of keg as being a
large wrapper around a transport, which does that actual request handling.

Out-of-the-box, keg comes with two transports:

1. one based on the Undertow webserver. This is a very stable and
   feature-complete webserver for Java. Unfortunately however, it does not work
   with ScalaNative and also has issues when built as a GraalVM native image.

   This transport is the **default when using keg on the JVM**.

1. one based on an experimental pure-scala HTTP server written from scratch.
   This webserver is minimal, doesn't implement advanced features such as
   websockets, and is probably not spec-compliant (web clients should be
   tolerant). It is however available under scala native!

   This transport is the **default when using keg with scala native**.

Here is a more detailed comparison of both transports:

| feature  | UndertowTransport | UhttpTransport |
|----------|-------------------|----------------|
| HTTP/1.1 | yes               | yes            |
| HTTP/2.0 | yes               | no             |
| HTTPS    | yes               | only on JVM    |
| websockets | yes             | no             |
| JVM      | yes               | yes            |
| native   | no                | yes            |

You can switch out the transport simply by passing it as an argument to
`keg.Server`. For example, here we explicitly tell keg to use the Uhttp server,
regardless of platform:

```scala
keg.Server(
  ...,
  transportBuilder = keg.transport.uhttp.UhttpTransport(_, _)
)
```

You can also implement your own transport if you like. Take a look at the
[Transport](???) trait to see what you need.

### Custom config traits

### Managing state

### Forms

- formdata
- uploadformdata

### JSON

## Internals

- division of layers

  (arrow means "depends on")

  ```mermaid
  graph LR
    derivation
    model
    main
    transport
    util

    derivation --> model
    main --> model
    main --> transport
    transport --> model

  ```

- model: scala types that are used to represent things involved in handling HTTP
  requests

- derivation: syntax sugar and macros to allow a user to write "nice" programs

- main: routing logic and higher-level runtime components

- transport: low-level, pluggable implementation of an HTTP server. This is
  primarily made separate so that different backends can exist, for example
  undertow for the JVM, and a custom simplistic server for scalanative

Derivation and model layers are to be considered as library layers, no logging
or side-effecting is allowed; those are reserved for main and transport.

synchronous