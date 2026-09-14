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
       |    <form action="/upload" method="post" enctype="multipart/form-data">
       |      <label for="file">File:</label>
       |      <input type="file" id="file" name="file"><br><br>
       |      <input type="submit" value="Upload">
       |    </form>
       |  </body>
       |</html>""".stripMargin
  keg.Response(html, headers = Seq("Content-Type" -> "text/html"))

@keg.post("/form")
def index(data: keg.FormData) =
  val name = data.fields("name")
  val value = data.fields("value")
  keg.Response(s"Hello, $name! Value is: $value")

@keg.post("/upload")
def upload(data: keg.UploadFormData) =
  val fileContent = java.nio.file.Files.readString(data.files("file").path)
  keg.Response(s"File content is: $fileContent")

val routes = keg.routes()
val server: keg.Server = keg.Server(routes, debugMode = false)

@main
def main() =
  server.start()
