package keg.main

import io.undertow as undertow
import keg.model.Request
import keg.model.Response
import keg.model.WebResponse
import keg.model.Data
import keg.model.Endpoint
import keg.model.Recovery
import keg.model.EndpointMeta
import keg.model.EndpointSelector
import keg.model.WsResponse
import keg.model.EndpointResult
import keg.util.log
import keg.model.Recovery
import keg.main.DispatchTrie.DispatchLookup
import scala.compiletime.ops.int

case class EndpointAbortedException(
  code: Int,
  endpoint: keg.model.Endpoint,
  pos: keg.util.Pos,
  message: String,
) extends Exception(message)

enum Listener:
  case Http(host: String, port: Int)
  case Https(host: String, port: Int, cert: String, key: String)

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
    val endpoints: Seq[Endpoint | Recovery],
    port: Int = 8080,
    host: String = "::",
    debugMode: Boolean = Server.debugFromEnv,
    secrets: Seq[Secret] = Seq.empty,
    sessionMaxAgeSeconds: Int = 3600,
    flashMaxAgeSeconds: Int =
      60, // flash cookies last for 1 minute, they're consumed on read anyway, so no need to last longer
    csrfAllowedOrigins: Set[String] = Set.empty,
    interceptors: Seq[Interceptor] = Seq.empty,
    debugInterceptors: Seq[Interceptor] = Seq(Reloader),
    listeners: Seq[Listener] = Seq()
):
  log.info(s"Debug mode: $debugMode")
  log.info(s"Initializing Keg Server with ${endpoints.size} endpoints")

  private val runtimeSecrets =
    if secrets.nonEmpty then secrets
    else if debugMode then
      log.warn("No secrets provided for server in debug mode, using random persisted secret.")
      Seq(Secret.fromTmp())
    else
      log.warn("No secrets provided for server, using random secret. This means that sessions and flash will not be valid across server restarts, and any existing cookies will be invalidated!")
      Seq(Secret.random())


  private def mkInvoke(ep: Endpoint): Request => WebResponse = (req: Request) =>
    if WebAppUtil.checkCsrf(req.method, req.headers, csrfAllowedOrigins) then
      ep.invoke(req) match
        case EndpointResult.Success(response) => response
        case failure: EndpointResult.Failure => recovery.invoke(req, ep, failure)
    else
      recovery.invoke(req, ep, EndpointResult.Failure.MethodAbort(403, "CSRF validation failed"))

  private val dispatchTrie = DispatchTrie[(Endpoint, Request => WebResponse)]()
  private val websocketDispatchTrie = DispatchTrie[(Endpoint, Request => WebResponse)]()

  private var notFoundEndpoint: Endpoint = Server.defaultNotFoundEndpoint
  private var notFoundFn: Request => WebResponse = mkInvoke(notFoundEndpoint)
  private var methodNotAllowedEndpoint: Endpoint = Server.defaultMethodNotAllowedEndpoint
  private var methodNotAllowedFn: Request => WebResponse = mkInvoke(methodNotAllowedEndpoint)

  private var recovery: Recovery =
    if debugMode then
      val fn = (req: Request, ep: Endpoint, failure: EndpointResult.Failure) =>
        failure match
          case EndpointResult.Failure.ParamAbort(param, code, message) =>
            Response[keg.Data](s"Parameter '$param' is invalid: $message", code)
          case EndpointResult.Failure.MethodAbort(code, message) =>
            Response[keg.Data](message, code)
          case EndpointResult.Failure.Exception(throwable) =>
            log.error(s"Error in endpoint ${ep.meta} for request ${req.method} ${req.path}", throwable)
            Response[keg.Data]("Internal Server Error " + throwable.getMessage, 500)
      Recovery(fn)
    else
      val fn = (req: Request, ep: Endpoint, failure: EndpointResult.Failure) =>
        failure match
          case EndpointResult.Failure.ParamAbort(_, code, _) =>
            Response[keg.Data]("Bad Request", code)
          case EndpointResult.Failure.MethodAbort(code, message) =>
            Response[keg.Data](message, code)
          case EndpointResult.Failure.Exception(throwable) =>
            log.error(s"Error in endpoint ${ep.meta} for request ${req.method} ${req.path}", throwable)
            Response[keg.Data]("Internal Server Error", 500)
      Recovery(fn)

  private val activeInterceptors =
    if debugMode then interceptors ++ debugInterceptors
    else interceptors

  private def addToTry(
    trie: DispatchTrie[(Endpoint, Request => WebResponse)],
    path: String,
    methods: Seq[String],
    endpoint: Endpoint
  ): Unit =
    val invokeFn = mkInvoke(endpoint)
    val segs = Util.splitPath(path)

    for method <- methods do
      trie.add(
        segs,
        method,
        (endpoint, invokeFn)
      ) match
        case DispatchTrie.AddResult.Ok => ()
        case DispatchTrie.AddResult.MethodConflict(path, items) =>
          throw new Exception(
            s"Method conflict when adding endpoint ${endpoint.meta} $methods $path: existing endpoints ${items.map { case (method, (ep, _)) => s"$method -> ${ep.meta}" }.mkString(", ")}"
          )
        case DispatchTrie.AddResult.SubpathConflict(path, items) =>
          throw new Exception(
            s"Subpath conflict when adding endpoint ${endpoint.meta} $methods $path: existing endpoints: ${items.values.map(_._1.meta).mkString(", ")}"
          )

  for endpoint <- activeInterceptors.flatMap(_.endpoints) ++ endpoints do
    endpoint match
      case r: Recovery =>
        if debugMode then
          log.warn(s"Ignoring recovery handler because debugMode is enabled")
        else
          recovery = r
      case ep: Endpoint =>
        ep.selector match
          case EndpointSelector.Simple(path, methods) =>
            addToTry(dispatchTrie, path, methods, ep)
          case EndpointSelector.WebSocketUpgrade(path) =>
            addToTry(websocketDispatchTrie, path, Seq("GET"), ep)
          case EndpointSelector.NotFound =>
            if notFoundEndpoint ne Server.defaultNotFoundEndpoint then
              throw new Exception(
                s"Multiple NotFound endpoints defined: ${notFoundEndpoint.meta} and ${ep.meta}"
              )
            else
              notFoundEndpoint = ep
              notFoundFn = mkInvoke(notFoundEndpoint)
          case EndpointSelector.MethodNotAllowed =>
            if methodNotAllowedEndpoint ne Server.defaultMethodNotAllowedEndpoint then
              throw new Exception(
                s"Multiple MethodNotAllowed endpoints defined: ${methodNotAllowedEndpoint.meta} and ${ep.meta}"
              )
            else
              methodNotAllowedEndpoint = ep
              methodNotAllowedFn = mkInvoke(methodNotAllowedEndpoint)
  end for

  private val interceptorFn =
    Interceptor.compose(activeInterceptors)

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


      def writeSimpleResponse(response0: keg.model.Response[keg.model.Data]): Unit =
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
        queryParams = Util.decodeQueryString(
          exchange.getQueryString()
        ),
        headers = headers,
        pathParams = lookupResult.bindings,
        remainingPathSegments = lookupResult.remainingPathSegments,
        cookies = cookies,
        session = sessionData,
        flash = flashData,
        getBody = () => exchange.getInputStream(),
        remoteIp = exchange.getSourceAddress().getAddress()
      )

      // def writeResponseOrError(ep: Endpoint, result: EndpointResult): Unit =
      //   result match
      //     case EndpointResult.Success(response: Response[keg.model.Data]) =>
      //       writeResponse(response)
      //     case EndpointResult.Success(wsResponse: WsResponse) =>
      //       val wsHandler = WsHandler(wsResponse.f)
      //       io.undertow.Handlers.websocket(wsHandler).handleRequest(exchange)
      //     case f: EndpointResult.Failure =>
      //       writeResponse(recovery.invoke(request, ep, f))

      def writeResponse(response: WebResponse): Unit = response match
        case simple: keg.model.Response[keg.model.Data] =>
          writeSimpleResponse(simple)
        case wsResponse: WsResponse =>
          val wsHandler = WsHandler(wsResponse.f)
          io.undertow.Handlers.websocket(wsHandler).handleRequest(exchange)

      if lookupResult.items.isEmpty then
        writeResponse(interceptorFn(request, notFoundEndpoint, notFoundFn))
      else
        lookupResult.items.get(exchange.getRequestMethod().toString) match
          case Some(endpoint, fn) =>
            writeResponse(interceptorFn(request, endpoint, fn))
            // WebAppUtil.checkCsrf(request.method, headers, csrfAllowedOrigins) match
            //   case false =>
            //     writeResponse(recovery.invoke(request, endpoint, EndpointResult.Failure.MethodAbort(403, "CSRF validation failed")))
            //   case true =>
            //     writeResponseOrError(
            //       endpoint,
            //       endpoint.invoke(request)
            //     )
          case None =>
            writeResponse(interceptorFn(request, notFoundEndpoint, methodNotAllowedFn))


      // try
      //   // WebAppUtil.checkCsrf(request.method, headers, csrfAllowedOrigins) match
      //   //   case false =>
      //   //     val result = recovery.invoke(request, ???, EndpointResult.Failure.MethodAbort(403, "CSRF validation failed"))
      //   //     result match
      //   //       case EndpointResult.Success(response) =>
      //   //         writeResponse(response)
      //   //       case EndpointResult.Failure(failure) =>
      //   //         val response = recovery.invoke(request, ???, failure)
      //   //         writeResponse(response)

      //   //     return
      //   //   case true => // continue

      //   if lookupResult.items.isEmpty then
      //     writeResponse(abortedEndpoint.invoke(request, 404, "Not Found"))
      //   else
      //     lookupResult.items.get(exchange.getRequestMethod().toString) match
      //       case Some(endpoint) =>
      //         try
      //           val response = interceptorFn(request, endpoint)
      //           response match
      //             case simple: keg.model.Response[keg.model.Data] =>
      //               writeResponse(simple)
      //             case wsResponse: keg.model.WsResponse =>
      //               val wsHandler = WsHandler(wsResponse.f)
      //               io.undertow.Handlers.websocket(wsHandler).handleRequest(exchange)
      //         catch
      //           case EndpointAbortedException(code, endpoint, pos, message) =>
      //             val message1 = s"Endpoint aborted with code $code at ${pos.file}:${pos.line}:${pos.column} in ${pos.function}: $message"
      //             val response = abortedEndpoint.invoke(request, code, message1)
      //             writeResponse(response)

      //       case None =>
      //         writeResponse(abortedEndpoint.invoke(request, 405, "Method Not Allowed"))
      // catch
      //   case err: Throwable =>
      //     val response = errorEndpoint.invoke(request, err)
      //     writeResponse(response)
  end handler

  private val server =
    val builder = undertow.Undertow
      .builder()
      .setHandler(undertow.server.handlers.BlockingHandler(handler))
      .addHttpListener(port, host)

    for listener <- listeners do
      listener match
        case Listener.Http(host, port) =>
          builder.addHttpListener(port, host)
        case Listener.Https(host, port, cert, key) =>
          val sslContext = Util.mkSslContext(key, cert)
          builder.addHttpsListener(port, host, sslContext)

    builder.build()

  def start(): Unit =
    if activeInterceptors.nonEmpty then
      log.info("Active interceptors: " + activeInterceptors.map(_.toString()).mkString("\n- ", "\n- ", ""))
    else
      log.info("No interceptors active")
    for interceptor <- activeInterceptors do
      interceptor.onStartup(this.endpoints.collect({
        case ep: Endpoint => ep
      }))

    log.info(s"Starting Keg Server on port $port")
    server.start()
    val listeners = server.getListenerInfo().iterator()
    while listeners.hasNext do
      val listener = listeners.next()

      log.info(
        s"Keg server listening on ${Util.prettySocketAddr(listener.getProtcol(), listener.getAddress.asInstanceOf[java.net.InetSocketAddress])}"
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

  def debugFromEnv = sys.env.isDefinedAt("KEG_DEBUG") || sys.env.isDefinedAt("DEBUG")

  val defaultNotFoundEndpoint: Endpoint =
    Endpoint(EndpointSelector.NotFound, req => EndpointResult.Failure.MethodAbort(404, "Not Found"), EndpointMeta())
  val defaultMethodNotAllowedEndpoint: Endpoint =
    Endpoint(EndpointSelector.MethodNotAllowed, req => EndpointResult.Failure.MethodAbort(405, "Method Not Allowed"), EndpointMeta())
