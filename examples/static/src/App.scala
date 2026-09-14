package app

// Static assets such as stylesheets, scripts, and images can be served with
// the helpers in `keg.Static`. Each helper comes in two variants: one looking
// up assets on the classpath (e.g. files in a module's `resources` directory,
// which get bundled into the application's jar), and one with a `File` suffix
// looking up assets on the filesystem. Classpath resources are not available
// on Scala Native, so use the `File` variants there.
//
// #### Single assets
//
// `Static.single` serves one fixed asset. Its `Content-Type` is derived from
// the file extension.
//snippet:start
@keg.get("/")
def index() =
  keg.Static.single("/assets/index.html")
//snippet:end
/* usage snippet
$ curl http://localhost:8080/
<!DOCTYPE html>
<html>
<head><link rel="stylesheet" href="/static/main.css"></head>
<body><h1>Hello, static world!</h1></body>
</html>
*/

// #### Directories
//
// `Static.fromRequest` serves assets from a directory, using the part of the
// request path matched by a wildcard endpoint. Here, a request to
// `/static/main.css` serves the classpath resource `/assets/main.css`:
//snippet:start
@keg.get("/static/*")
def assets(req: keg.Request) =
  keg.Static.fromRequest(req, "/assets")
//snippet:end
/* usage snippet
$ curl http://localhost:8080/static/main.css
h1 { color: teal; }
*/

// To serve files from the filesystem instead, use `fromRequestFile` (or
// `singleFile` for a single file). Relative paths are resolved against the
// server's working directory:
//snippet:start
@keg.get("/files/*")
def files(req: keg.Request) =
  keg.Static.fromRequestFile(req, "public")
//snippet:end
/* usage snippet
$ curl http://localhost:8080/files/hello.txt
hello from the filesystem
*/

// Request paths containing `.` or `..` segments are rejected, so it's safe to
// serve user-provided paths this way:
/* usage snippet
$ curl -i --path-as-is http://localhost:8080/files/../src/App.scala
HTTP/1.1 404 Not Found
...
*/

// #### Precompressed assets
//
// If the client accepts a compressed encoding and a precompressed variant of an
// asset exists next to it (e.g. `main.css.gz`), `fromRequest` serves that
// variant instead, with the appropriate `Content-Encoding` header. By default,
// `br` and `gzip` are negotiated, in that order.
/* usage snippet
$ curl -s -o /dev/null -D - -H 'Accept-Encoding: gzip' http://localhost:8080/static/main.css | grep -i content-encoding
content-encoding: gzip
*/

val routes = keg.routes()

@main
def main() =
  keg.Server(routes).start()
