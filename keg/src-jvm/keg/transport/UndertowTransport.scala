package keg.transport

import io.undertow
import keg.model.{Request, Response, WebResponse, WsResponse}

object UndertowTransport:

  // The XNIO library used by Undertow does logging in static initializers,
  // so we need to set this system property before anything else is loaded.
  System.setProperty("org.jboss.logging.provider", "slf4j")

  private def compactIpv6(addr: java.net.Inet6Address): String =
    val bytes = addr.getAddress() // 16 bytes, big-endian
    val hextets = Array.tabulate(8)(i => ((bytes(2 * i) & 0xff) << 8) | (bytes(2 * i + 1) & 0xff))

    // Find the longest run of consecutive zero hextets, leftmost on tie
    var bestStart = -1
    var bestLen = 0
    var i = 0
    while i < 8 do
      if hextets(i) == 0 then
        var j = i
        while j < 8 && hextets(j) == 0 do j += 1
        val len = j - i
        if len > bestLen then
          bestLen = len
          bestStart = i
        i = j
      else
        i += 1

    if bestLen == 0 then
      hextets.map(_.toHexString).mkString(":")
    else
      val before = hextets.slice(0, bestStart).map(_.toHexString).mkString(":")
      val after  = hextets.slice(bestStart + bestLen, 8).map(_.toHexString).mkString(":")
      s"$before::$after"

class UndertowTransport(
  listeners: Seq[Listener],
  fn: Request => WebResponse,
  log: String => Unit,
  builder: undertow.Undertow.Builder = undertow.Undertow.builder()
) extends Transport:

  private given castorContext: castor.Context = castor.Context.Simple(
    castor.Context.Simple.executionContext,
    err => log(s"Actor error: $err")
  )

  private class WsChannelActor(channel: io.undertow.websockets.core.WebSocketChannel) extends castor.SimpleActor[keg.model.Ws.Event]:
    import io.undertow.websockets.core.WebSockets
    import keg.model.Ws
    import java.nio.ByteBuffer

    def run(item: Ws.Event): Unit = item match
      case Ws.Text(value) => WebSockets.sendTextBlocking(value, channel)
      case Ws.Binary(value) => WebSockets.sendBinaryBlocking(ByteBuffer.wrap(value), channel)
      case Ws.Ping(value) => WebSockets.sendPingBlocking(ByteBuffer.wrap(value), channel)
      case Ws.Pong(value) => WebSockets.sendPongBlocking(ByteBuffer.wrap(value), channel)
      case Ws.Close(code, reason) => WebSockets.sendCloseBlocking(code, reason, channel)

  end WsChannelActor

  private class WsHandler(f: WsChannelActor => castor.Actor[keg.model.Ws.Event]) extends io.undertow.websockets.WebSocketConnectionCallback:
    import io.undertow.websockets.spi.WebSocketHttpExchange
    import io.undertow.websockets.core.WebSocketChannel
    import io.undertow.websockets.core.{AbstractReceiveListener, BufferedBinaryMessage, BufferedTextMessage, CloseMessage, WebSocketChannel, WebSockets}
    import keg.model.Ws

    def onConnect(exchange: WebSocketHttpExchange, channel: WebSocketChannel): Unit =
      channel.suspendReceives()
      val actor = f(new WsChannelActor(channel))
      // Somehow browsers closing tabs and Java processes being killed appear
      // as different events here; the former goes to AbstractReceiveListener#onClose,
      // while the latter to ChannelListener#handleEvent. Make sure we handle both cases.
      channel.addCloseTask(channel => actor.send(Ws.ChannelClosed()))
      channel.getReceiveSetter.set(
        new AbstractReceiveListener() {
          override def onFullTextMessage(channel: WebSocketChannel, message: BufferedTextMessage) = {
            actor.send(Ws.Text(message.getData))
          }

          override def onFullBinaryMessage(channel: WebSocketChannel, message: BufferedBinaryMessage): Unit = {
            actor.send(Ws.Binary(
              WebSockets.mergeBuffers(message.getData.getResource*).array()
            ))
          }

          override def onFullPingMessage(channel: WebSocketChannel, message: BufferedBinaryMessage): Unit = {
            actor.send(Ws.Ping(
              WebSockets.mergeBuffers(message.getData.getResource*).array()
            ))
          }
          override def onFullPongMessage(channel: WebSocketChannel, message: BufferedBinaryMessage): Unit = {
            actor.send(Ws.Pong(
              WebSockets.mergeBuffers(message.getData.getResource*).array()
            ))
          }

          override def onCloseMessage(cm: CloseMessage, channel: WebSocketChannel) = {
            actor.send(Ws.Close(cm.getCode, cm.getReason))
          }
        }
      )
      channel.resumeReceives()
  end WsHandler

  private object handler extends undertow.server.HttpHandler:
    override def handleRequest(exchange: undertow.server.HttpServerExchange): Unit =
      def writeSimpleResponse(response: keg.model.Response[keg.model.Data]): Unit =
        exchange.setStatusCode(response.status)

        for (key, value) <- response.data.headers do
          exchange
            .getResponseHeaders()
            .put(undertow.util.HttpString(key), value)

        for (key, value) <- response.headers do
          exchange
            .getResponseHeaders()
            .put(undertow.util.HttpString(key), value)

        if response.data.contentLength.isDefined then
          exchange
            .getResponseHeaders()
            .put(undertow.util.HttpString("content-length"), response.data.contentLength.get.toString)

        for cookie <- response.cookies do
          val ucookie = undertow.server.handlers.CookieImpl(
            cookie.name,
            cookie.value
          )
          ucookie.setComment(cookie.comment)
          ucookie.setDomain(cookie.domain)
          ucookie.setExpires(
            if (cookie.expires == null) null
            else java.util.Date.from(cookie.expires)
          )
          ucookie.setMaxAge(cookie.maxAge)
          ucookie.setPath(cookie.path)
          ucookie.setVersion(cookie.version)
          ucookie.setDiscard(cookie.discard)
          ucookie.setHttpOnly(cookie.httpOnly)
          ucookie.setSecure(cookie.secure)
          ucookie.setSameSiteMode(cookie.sameSite)
          exchange.setResponseCookie(ucookie)

        val out = exchange.getOutputStream()
        response.data.write(out)
      end writeSimpleResponse

      val headersMap = collection.mutable.Map.empty[String, collection.mutable.ListBuffer[String]]

      exchange.getRequestHeaders().forEach: headerValues =>
        val headerName = headerValues.getHeaderName.toString.toLowerCase
        val values = headersMap.getOrElseUpdate(headerName, collection.mutable.ListBuffer.empty[String])
        headerValues.forEach(value => values += value)

      val headers = keg.util.MultiDict(headersMap)

      import scala.jdk.CollectionConverters.given

      val cookies = exchange
        .requestCookies()
        .asScala
        .map { c =>
          c.getName() -> keg.model.Cookie(
            name = c.getName(),
            value = c.getValue(),
            comment = c.getComment(),
            domain = c.getDomain(),
            expires =
              if c.getExpires() == null then null
              else c.getExpires().toInstant(),
            maxAge = c.getMaxAge(),
            path = c.getPath(),
            version = c.getVersion(),
            discard = c.isDiscard(),
            httpOnly = c.isHttpOnly(),
            secure = c.isSecure(),
            sameSite = c.getSameSiteMode()
          )
        }
        .toMap

      val request = keg.model.Request(
        method = exchange.getRequestMethod().toString,
        path = exchange.getRequestPath(),
        queryParams = keg.util.MultiDict.empty,
        headers = headers,
        pathParams = Map.empty,
        remainingPathSegments = IndexedSeq.empty,
        cookies = cookies,
        session = Map.empty,
        flash = Map.empty,
        getBody = () => exchange.getInputStream(),
        remoteIp = exchange.getSourceAddress().getAddress()
      )

      fn(request) match
        case simple: keg.model.Response[keg.model.Data] =>
          writeSimpleResponse(simple)
        case wsResponse: WsResponse =>
          val wsHandler = WsHandler(wsResponse.f)
          io.undertow.Handlers.websocket(wsHandler).handleRequest(exchange)

  end handler

  var server: undertow.Undertow = null

  def start(): Unit =
    for listener <- listeners do
      listener match
        case Listener.Http(host, port) =>
          builder.addHttpListener(port, host)
        case Listener.Https(host, port, key, cert) =>
          val sslContext = Platform.mkSslContext(key, cert)
          builder.addHttpsListener(port, host, sslContext)

    server = builder.setHandler(undertow.server.handlers.BlockingHandler(handler)).build()
    server.start()

  def getListeners: Seq[Listener] =
    val ls = collection.mutable.ListBuffer.empty[Listener]

    server.getListenerInfo().forEach: info =>
      val address = info.getAddress().asInstanceOf[java.net.InetSocketAddress]
      val compact = address.getAddress match
        case ipv6: java.net.Inet6Address => UndertowTransport.compactIpv6(ipv6)
        case ipv4: java.net.Inet4Address => ipv4.getHostAddress
        case other => other.getHostAddress

      info.getProtcol() match
        case "http" =>
          ls += Listener.Http(compact, address.getPort)
        case "https" =>
          ls += Listener.Https(compact, address.getPort, "", "") // TODO: get cert/key from Undertow if possible
        case other =>
          log(s"Unknown protocol $other for listener on ${compact}:${address.getPort}, skipping")

    ls.toSeq

  def stop(): Unit =
    if server != null then server.stop()
  def kill(): Unit =
    if server != null then server.stop()
  def awaitTermination(): Unit =
    val worker = server.getWorker()
    if worker != null then
      worker.awaitTermination()
