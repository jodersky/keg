package keg.main

import io.fusionauth

class Server(
  port: Int
):

  val handler: fusionauth.http.server.HTTPHandler = (
    req: fusionauth.http.server.HTTPRequest,
    res: fusionauth.http.server.HTTPResponse) =>

      println(req.getFormData())

      val buffer = new Array[Byte](8192)
      var totalBytes = 0
      var bytesRead = 0
      println("Received request:")
      while ({
        bytesRead = req.getInputStream().read(buffer)
        bytesRead != -1
      }) {
        totalBytes += bytesRead
        Thread.sleep(10) // Simulate processing delay
      }
      println(s"Total bytes read: $totalBytes")

      req.getInputStream()

      res.setStatus(200)
      res.setHeader("Content-Type", "text/plain")
      res.getWriter().write("Hello from Keg Server!")
      res.getWriter().flush()

  def run() =
    val httpServer = fusionauth.http.server.HTTPServer()
      .withHandler(handler)
      .withListener(
        fusionauth.http.server.HTTPListenerConfiguration(port)
      )
    httpServer.start()
    println(s"Keg Server running on port $port")

    // httpServer.close() // This will block until the server is stopped


object example:
  def main(args: Array[String]): Unit =
    val server = Server(8080)
    server.run()

// public class Example {
//   public static void main(String... args) {
//     HTTPHandler handler = (req, res) -> {
//       // Handler code goes here
//     };

//     try (HTTPServer server = new HTTPServer().withHandler(handler)
//                                              .withListener(new HTTPListenerConfiguration(4242))) {
//       server.start();
//       // When this block exits, the server will be shutdown
//     }
//   }
// }
// ```
