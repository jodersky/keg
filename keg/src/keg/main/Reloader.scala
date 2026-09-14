package keg.main

import keg.model.Endpoint

import keg.model.Request

import keg.model.RawResponse

object Reloader extends Interceptor:

  val instance = java.util.UUID.randomUUID().toString

  private val clients = java.util.concurrent.ConcurrentHashMap.newKeySet[castor.Actor[keg.model.Ws.Event]]()

  def reload(): Unit =
    val message = keg.model.Ws.Text("""{"type": "reload"}""")
    val it = clients.iterator()
    while it.hasNext do
      val client = it.next()
      client.send(message)

  override val endpoints = Seq(
    keg.model.Endpoint(
      "/__dev_ws",
      Seq("GET"),
      invoke = (request: keg.model.Request) =>
        val r = keg.model.WsResponse(
          channel =>
            channel.send(
              keg.model.Ws.Text(
                s"""{
                  |  "type": "hello",
                  |  "instance": "$instance"
                  |}""".stripMargin
              )
            )
            clients.add(channel)
            keg.model.WsActor:
              case keg.model.Ws.ChannelClosed() =>
                clients.remove(channel)
              case _ => ()
        )
        r
    ),
    keg.model.Endpoint(
      "/__dev_ws.js",
      Seq("GET"),
      invoke = (request: keg.model.Request) =>
        keg.model.Static.single("/reload.js", classpath = true)
    )
  )

  // val script = new String(this.getClass().getResourceAsStream("/reload.js").readAllBytes(), "UTF-8")

  def intercept(request: Request, endpoint: Endpoint, next: Request => RawResponse): RawResponse =
    val response = next(request)
    response match
      case r: keg.model.Response[keg.model.Data] => inject(r)
      case other => other

  def inject(response: keg.model.Response[keg.model.Data]): keg.model.Response[keg.model.Data] =
    val allHeaders = (response.data.headers ++ response.headers).map(elem => elem._1.toLowerCase -> elem._2).toMap
    allHeaders.get("content-type") match
      case Some(html) if html.startsWith("text/html") =>
        // TODO: stream and modify on the fly instead of buffering everything in memory
        val buffer = new java.io.ByteArrayOutputStream()
        response.data.write(buffer)
        val content = buffer.toString("UTF-8")

        // only inject reloader script on full pages (not HTML fragments)
        if content.startsWith("<!DOCTYPE html>") then
          val injectedContent = content.replace(
            "</body>",
            s"""<script src="/__dev_ws.js"></script></body>"""
          )
          response.copy(data = injectedContent, headers = response.headers ++ Seq("content-type" -> html))
        else
          response
      case _ => response
