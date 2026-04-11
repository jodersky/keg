package app

//snippet:start
@keg.post("/hello/:n")
def hello(n: Int, req: keg.Request) =
  s"hello ${req.text()}\n" * n

val server = keg.Server(keg.routes())

@main
def main() =
  server.start()
//snippet:end

/* usage snippet
$ curl "http://localhost:8080/hello/5" -d "alice"
hello alice
hello alice
hello alice
hello alice
hello alice
*/
