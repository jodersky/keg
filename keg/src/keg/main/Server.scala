package keg.main

import io.undertow as undertow
import keg.model.Request
import keg.model.Response
import keg.util.log

/** A simple HTTP server using Undertow.
  *
  * @param endpoints
  *   The list of endpoints to serve.
  * @param handleNotFound
  *   A function to handle requests that do not match any endpoint.
  * @param handleMethodNotAllowed
  *   A function to handle requests that match an endpoint path but not the
  *   method.
  * @param port
  *   The port to listen on.
  * @param host
  *   The host to bind to.
  * @param secrets
  *   A list of secrets for signing cookies or sessions (not yet implemented).
  *   The first secret is used for signing and verification, others for
  *   verification.
  * @param sessionMaxAgeSeconds
  *   The maximum age of session cookies in seconds.
  * @param flashMaxAgeSeconds
  *   The maximum age of flash cookies in seconds.
  * @param csrfAllowedOrigins
  *   A set of allowed origins for CSRF protection.
  */
class Server(
    val endpoints: Seq[keg.model.Endpoint],
    port: Int = 8080,
    host: String = "::",
    debugMode: Boolean = true,
    secrets: Seq[Secret] = Seq.empty,
    sessionMaxAgeSeconds: Int = 3600,
    flashMaxAgeSeconds: Int =
      60, // flash cookies last for 1 minute, they're consumed on read anyway, so no need to last longer
    csrfAllowedOrigins: Set[String] = Set.empty,
    handleError: (Request, Throwable) => Response = (req, err) =>
      log.error("Internal Server Error: " + req.path, err)
      Response("Internal Server Error: " + err.getMessage(), 500),
    interceptors: Seq[Interceptor] = Seq.empty
):
  private val runtimeSecrets =
    if secrets.nonEmpty then secrets
    else if debugMode then
      log.warn("No secrets provided for Server, using random secret in debug mode")
      Seq(Secret.random())
    else throw new Exception("No secrets provided for Server in non-debug mode")

  private val dispatchTrie = DispatchTrie[keg.model.Endpoint]()
  private var websocketDispatchTrie = DispatchTrie[keg.model.Endpoint]()

  private var handleNotFound: Request => Response = req =>
    log.info("Not Found: " + req.path)
    Response("Not Found", 404)
  private var handleMethodNotAllowed: Request => Response = req =>
    log.info("Method Not Allowed: " + req.path + " " + req.method)
    Response("Method Not Allowed " + req.method, 405)
  private var handleCsrfFailure: Request => Response = req =>
    log.info("CSRF check failed: " + req.path)
    Response("Forbidden - CSRF check failed", 403)

  private val endpoints1 = if debugMode then
    endpoints ++ Reloader.endpoints
  else
    endpoints

  for endpoint <- endpoints1 do
    endpoint.selector match
      case sel: keg.model.EndpointSelector.Simple =>
        for method <- sel.methods do
          dispatchTrie.add(
            Util.splitPath(sel.path),
            method,
            endpoint
          ) match
            case DispatchTrie.AddResult.Ok => ()
            case DispatchTrie.AddResult.MethodConflict(path, items) =>
              throw new Exception(
                s"Method conflict when adding endpoint ${endpoint.meta} $method ${sel.path}: existing endpoint ${items(method).meta}"
              )
            case DispatchTrie.AddResult.SubpathConflict(path, items) =>
              throw new Exception(
                s"Subpath conflict when adding endpoint ${endpoint.meta} $method ${sel.path}: existing endpoints: ${items.values.map(_.meta).mkString(", ")}"
              )
      case sel: keg.model.EndpointSelector.WebSocketUpgrade =>
        websocketDispatchTrie.add(
          Util.splitPath(sel.path),
          "GET",
          endpoint
        ) match
          case DispatchTrie.AddResult.Ok => ()
          case DispatchTrie.AddResult.MethodConflict(path, items) =>
            throw new Exception(
              s"Method conflict when adding endpoint ${endpoint.meta} ${sel.path}: existing endpoint ${items("GET").meta}"
            )
          case DispatchTrie.AddResult.SubpathConflict(path, items) =>
            throw new Exception(
              s"Subpath conflict when adding endpoint ${endpoint.meta} ${sel.path}: existing endpoints: ${items.values.map(_.meta).mkString(", ")}"
            )
      case keg.model.EndpointSelector.NotFound =>
        handleNotFound = endpoint.invoke.asInstanceOf[Request => Response]
      case keg.model.EndpointSelector.MethodNotAllowed =>
        handleMethodNotAllowed = endpoint.invoke.asInstanceOf[Request => Response]
      case keg.model.EndpointSelector.CsrfError =>
        handleCsrfFailure = endpoint.invoke.asInstanceOf[Request => Response]
  end for

  private val interceptorFn = Interceptor.compose(interceptors)

  private given castorContext: castor.Context = castor.Context.Simple(
    castor.Context.Simple.executionContext,
    err => log.error("Actor error", err)
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
    override def handleRequest(
        exchange: undertow.server.HttpServerExchange
    ): Unit =


      def writeResponse(response0: keg.model.Response): Unit =
        val response = if debugMode then Reloader.inject(response0) else response0

        exchange.setStatusCode(response.status)

        for (key, value) <- response.data.headers do
          exchange
            .getResponseHeaders()
            .put(
              undertow.util.HttpString(key),
              value
            )

        for (key, value) <- response.headers do
          exchange
            .getResponseHeaders()
            .put(
              undertow.util.HttpString(key),
              value
            )

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

        if response.session != null then
          if response.session.nonEmpty then
            val sessionCookie = undertow.server.handlers.CookieImpl(
              "_session",
              WebAppUtil.signMap(
                response.session,
                System.currentTimeMillis() / 1000L + sessionMaxAgeSeconds,
                runtimeSecrets.head
              )
            )
            sessionCookie.setMaxAge(sessionMaxAgeSeconds)
            sessionCookie.setPath("/")
            sessionCookie.setHttpOnly(true)
            sessionCookie.setSameSiteMode("Lax")
            exchange.setResponseCookie(sessionCookie)
          else if exchange.getRequestCookie("_session") != null then
            val sessionCookie = undertow.server.handlers.CookieImpl(
              "_session",
              ""
            )
            sessionCookie.setMaxAge(0) // delete cookie
            sessionCookie.setPath("/")
            exchange.setResponseCookie(sessionCookie)

        if response.flash != null then
          if response.flash.nonEmpty then
            val flashCookie = undertow.server.handlers.CookieImpl(
              "_flash",
              WebAppUtil.signMap(
                response.flash,
                System.currentTimeMillis() / 1000L + flashMaxAgeSeconds,
                runtimeSecrets.head
              )
            )
            flashCookie.setMaxAge(flashMaxAgeSeconds)
            flashCookie.setPath("/")
            flashCookie.setHttpOnly(true)
            flashCookie.setSameSiteMode("Lax")
            exchange.setResponseCookie(flashCookie)
          else if exchange.getRequestCookie("_flash") != null then
            val flashCookie = undertow.server.handlers.CookieImpl(
              "_flash",
              ""
            )
            flashCookie.setMaxAge(0) // delete cookie
            flashCookie.setPath("/")
            exchange.setResponseCookie(flashCookie)

        val out = exchange.getOutputStream()
        response.data.write(out)
      end writeResponse

      import scala.jdk.CollectionConverters.given
      val headers = exchange
        .getRequestHeaders()
        .asScala
        .map { header =>
          header.getHeaderName.toString().toLowerCase -> header.asScala.toSeq
        }
        .toMap

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

      val sessionData: Map[String, String] =
        exchange.getRequestCookie("_session") match
          case null =>
            Map()
          case cookie =>
            WebAppUtil.verifyMap(cookie.getValue(), runtimeSecrets)
      val flashData: Map[String, String] =
        exchange.getRequestCookie("_flash") match
          case null   => Map()
          case cookie =>
            WebAppUtil.verifyMap(cookie.getValue(), runtimeSecrets)

      val decodedPathSegments = Util.splitPath(java.net.URLDecoder.decode(exchange.getRequestPath(), "UTF-8"))

      val lookupResult = if exchange.getRequestHeaders().getFirst("upgrade") == "websocket" then
        websocketDispatchTrie.lookup(decodedPathSegments)
      else
        dispatchTrie.lookup(decodedPathSegments)

      // val path: String =
      //   uri.indexOf('?') match
      //     case -1  => uri
      //     case idx => uri.substring(0, idx)

      val request = keg.model.Request(
        method = exchange.getRequestMethod().toString,
        path = exchange.getRequestPath(),
        queryParams = Server.decodeQueryString(
          exchange.getQueryString()
        ),
        headers = headers,
        pathParams = lookupResult.bindings,
        remainingPathSegments = lookupResult.remainingPathSegments,
        cookies = cookies,
        session = sessionData,
        flash = flashData,
        getBody = () => exchange.getInputStream()
      )

      try
        WebAppUtil.checkCsrf(request.method, headers, csrfAllowedOrigins) match
          case false =>
            val response = Response("Forbidden - CSRF check failed", 403)
            writeResponse(response)
            return
          case true => // continue

        if lookupResult.items.isEmpty then
          writeResponse(handleNotFound(request))
        else
          lookupResult.items.get(exchange.getRequestMethod().toString) match
            case Some(endpoint) =>
              val response = interceptorFn(request, endpoint)
              response match
                case simple: keg.model.Response =>
                  writeResponse(simple)
                case wsResponse: keg.model.WsResponse =>
                  val wsHandler = WsHandler(wsResponse.f)
                  io.undertow.Handlers.websocket(wsHandler).handleRequest(exchange)

            case None =>
              writeResponse(handleMethodNotAllowed(request))
      catch
        case err: Throwable =>
          val response = handleError(request, err)
          writeResponse(response)
  end handler

  private val server = undertow.Undertow
    .builder()
    .addHttpListener(port, host)
    .setHandler(undertow.server.handlers.BlockingHandler(handler))
    .build()

  def start(): Unit =
    if interceptors.nonEmpty then
      log.info("Active interceptors: " + interceptors.map(_.toString()).mkString("\n- ", "\n- ", ""))
    else
      log.info("No interceptors active")

    log.info(s"Starting Keg Server on port $port")
    server.start()
    val listeners = server.getListenerInfo().iterator()
    while listeners.hasNext do
      val listener = listeners.next()
      log.info(
        s"Keg server listening on ${listener.getAddress().asInstanceOf[java.net.InetSocketAddress]}"
      )

  def serverPort() =
    server.getListenerInfo().get(0).getAddress().asInstanceOf[java.net.InetSocketAddress].getPort()

  def awaitTermination(): Unit =
    server.getWorker().awaitTermination()

  def stop(): Unit =
    log.info("Stopping Keg Server")
    server.stop()

object Server:

  // The XNIO library used by Undertow does logging in static initializers,
  // so we need to set this system property before anything else is loaded.
  System.setProperty("org.jboss.logging.provider", "slf4j")

  private def decodeQueryString(qstring: String): Map[String, Seq[String]] =

    var limit = 1024 // params limit
    var done = false
    var i = 0
    val params = scala.collection.mutable
      .Map[String, scala.collection.mutable.ListBuffer[String]]()

    var nameStart = 0
    var valueStart = -1

    def addParam(): Boolean =
      if nameStart >= i then false
      else
        if valueStart <= nameStart then valueStart = i + 1
        val name = java.net.URLDecoder
          .decode(qstring.substring(nameStart, valueStart - 1), "UTF-8")
        val value = java.net.URLDecoder
          .decode(qstring.substring(valueStart - 1, i), "UTF-8")
        val values = params.getOrElseUpdate(
          name,
          scala.collection.mutable.ListBuffer[String]()
        )
        values += value
        true

    while i < qstring.length && !done do
      qstring.charAt(i) match
        case '=' =>
          if nameStart == i then nameStart = i + 1
          else if valueStart < nameStart then valueStart = i + 1
        case '&' =>
          if addParam() then
            limit -= 1
            if limit <= 0 then done = true
          nameStart = i + 1
        case '#' => done = true
        case _   => // continue
      i += 1
    addParam()

    params.view.mapValues(_.toSeq).toMap
