package keg.main

import keg.model.Response
import keg.model.BodyReader
import keg.model.Request

object Example:

  val ep1 = Endpoint(
    "/foo",
    "POST",
    () => BodyReader.StringBodyReader(100),
    (req: Request, body: String) =>
      println(s"Received /foo with body: $body")
      Response(
        Response.Data.StringData(s"Echo: $body"),
        status = 200,
        headers = Seq("Content-Type" -> "text/plain")
      )
  )

  // val ep2 = Endpoint(
  //   "/bar",
  //   "GET",
  //   BodyReader.emptyReader,
  //   (ctx: Context, _: Unit) =>
  //     println(s"Received /bar request")
  //     val w: geny.Writable = "hello, world"
  //     ctx.sendStream(
  //       200,
  //       Map("Content-Type" -> "text/plain"),
  //       s => w.writeBytesTo(s)
  //     )
  // )

  // val ep3 = Endpoint(
  //   "/baz",
  //   "GET",
  //   BodyReader.emptyReader,
  //   (ctx: Context, _: Unit) =>
  //     println(s"Received /baz request")
  //     ctx.sendReadable(
  //       200,
  //       Map("Content-Type" -> "text/plain"),
  //       "hello, world"
  //     )
  // )

  // val img1 = Endpoint(
  //   "/image",
  //   "GET",
  //   BodyReader.emptyReader,
  //   (ctx: Context, _: Unit) =>
  //     println(s"Received /image request")

  //     // ctx.beginChunkedResponse(200, Map("Content-Type" -> "text/plain"))

  //     val stream = java.io.FileInputStream("image.png")
  //     ctx.sendChunked(200, Map("Content-Type" -> "image/png"), stream)
  //       .addListener((future: io.netty.channel.ChannelFuture) =>
  //         if (future.isSuccess) {
  //           println("Image sent successfully")
  //         } else {
  //           println(s"Failed to send image: ${future.cause().getMessage}")
  //         }
  //         stream.close()
  //       )

  //     // stream.close()
  // )

  // def main(args: Array[String]): Unit =
  //   println("Example main")
  //   Server(8080, List(ep1)).run()


