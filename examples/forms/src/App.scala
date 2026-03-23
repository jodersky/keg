package app

@keg.get("/")
def home() =
  val html =
    """|<doctype html>
       |<html>
       |  <head><title>Form Example</title></head>
       |  <body>
       |    <h1>Submit a Form</h1>
       |    <form action="/form" method="post">
       |      <label for="name">Name:</label>
       |      <input type="text" id="name" name="name"><br><br>
       |      <label for="value">Value:</label>
       |      <input type="text" id="value" name="value"><br><br>
       |      <input type="submit" value="Submit">
       |    </form>
       |  </body>
       |</html>""".stripMargin
  keg.Response(html, headers = Seq("Content-Type" -> "text/html"))

@keg.post("/form")
def index(data: keg.forms.FormData) =
  val name = data.fields("name")
  val value = data.fields("value")
  keg.Response(s"Hello, $name! Value is: $value")

val server: keg.Server = keg.Server(keg.routes().endpoints, debugMode = false)

@main
def main() =
  server.start()
