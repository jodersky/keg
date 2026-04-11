
@keg.get("/")
def index() = "Hello, World!"

@keg.post("/echo/:x")
def echo(x: Int, req: keg.Request) =
  val y = req.queryParams.get("q").getOrElse("")
  val z = new String(req.body().readAllBytes(), "utf8")
  s"Echo (param = $x, q = $y, body = $z)"

val endpoints: Seq[keg.Endpoint | keg.Recovery] = keg.routes().endpoints

@main
def run() =
  println("Starting server on http://localhost:8080")
  val server = keg.Server(endpoints)

  sys.addShutdownHook:
    server.stop()
    server.awaitTermination()

  server.start()
