package app

// Keg integrates with [upickle](https://com-lihaoyi.github.io/upickle/) for
// JSON support, covering both raw and typed access.
//
// #### Raw JSON
//
// Parameters of type `ujson.Value`, `ujson.Obj`, or `ujson.Arr` are parsed
// from the request body automatically. You can return them directly as
// responses — keg serializes them back to JSON and sets `Content-Type:
// application/json`.
//snippet:start
@keg.post("/echo")
def echo(body: ujson.Value) =
  body
//snippet:end
/* usage snippet
$ curl http://localhost:8080/echo -d '{"name":"Alice","scores":[1,2,3]}'
{"name":"Alice","scores":[1,2,3]}
*/

// #### Typed JSON with `derives ReadWriter`
//
// For typed access, derive `upickle.default.ReadWriter` on a case class. Keg
// picks up the generated instances automatically — the case class can be used
// as an endpoint parameter (request parsing) and as a return type (response
// serialization) with no additional boilerplate:
//snippet:start
case class Item(name: String, count: Int) derives upickle.default.ReadWriter

@keg.post("/double")
def double(item: Item) =
  item.copy(count = item.count * 2)
//snippet:end
/* usage snippet
$ curl http://localhost:8080/double -d '{"name":"apples","count":5}'
{"name":"apples","count":10}
*/

// Malformed or missing fields produce a `400 Bad Request` automatically:
/* usage snippet
$ curl http://localhost:8080/double -d 'not json' -i
HTTP/1.1 400 Bad Request
...
*/

val routes = keg.routes()

@main
def main() =
  keg.Server(routes).start()
