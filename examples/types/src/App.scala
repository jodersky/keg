package app

// You can access the HTTP request by adding a parameter of type `keg.Request`
// to the method:
//snippet:start
@keg.post("/req")
def req(req: keg.Request) =
  s"Method: ${req.method}, Path: ${req.path}, Body: ${req.text()}"
//snippet:end
/* usage snippet
$ curl "http://localhost:8080/req" -d "hello"
Method: POST, Path: /req, Body: hello
*/

// The `keg.Request` class gives you access to all parts of an HTTP request,
// including headers, query parameters, etc. It's technically enough to
// implement an endpoint, but it's usually more convenient to work with
// higher-level abstractions that do validation and parsing for you.
//
// Keg has built-in support for parsing many common types, including:
// - form data (url-encoded and multipart, including file uploads)
// - JSON (using ujson)

//snippet:start
@keg.post("/form")
def form(data: keg.FormData) =
  data.fields.toString

@keg.post("/json")
def json(data: ujson.Obj) =
  data

@keg.post("/json-any")
def jsonAny(data: ujson.Value) =
  data
//snippet:end

val server: keg.Server = keg.Server(keg.routes().endpoints)

@main
def main() =
  server.start()

/* usage snippet
$ curl "http://localhost:8080/form" -d "name=alice&age=42"
Map(name -> alice, age -> 42)
*/

/* usage snippet
$ curl "http://localhost:8080/json" -d "{\"name\": \"alice\", \"age\": 42}"
{"name":"alice","age":42}
*/

// Notice how invalid input results in a 400 Bad Request response without us
// having to write any error handling code in the method itself!
/* usage snippet
$ curl "http://localhost:8080/json" -d "[1, 2, 3] -i"
Bad Request
*/

/* usage snippet
$ curl "http://localhost:8080/json-any" -d "[1, 2, 3]"
[1,2,3]
*/
