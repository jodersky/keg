package keg.main

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
import keg.util.Util

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
case class Server(
    val endpoints: Seq[Endpoint | Recovery],
    listeners: Seq[keg.transport.Listener] = Seq(keg.transport.Listener.Http("::", 8080)),
    debugMode: Boolean = Server.debugFromEnv,
    secrets: Seq[String] = Seq.empty,
    sessionMaxAgeSeconds: Int = 3600,
    flashMaxAgeSeconds: Int =
      60, // flash cookies last for 1 minute, they're consumed on read anyway, so no need to last longer
    csrfAllowedOrigins: Set[String] = Set.empty,
    interceptors: Seq[Interceptor] = Seq.empty,
    debugInterceptors: Seq[Interceptor] = Seq(Reloader),
    transportBuilder: keg.transport.Transport.BuilderFunc = keg.transport.Platform.mkDefaultTransport
):
  log.info(s"Debug mode: $debugMode")
  log.info(s"Initializing Keg Server with ${endpoints.size} endpoints")

  private val runtimeSecrets: Seq[Array[Byte]] =
    if secrets.nonEmpty then
      secrets.map(Secret.check)
    else if debugMode then
      log.warn("No secrets provided for server in debug mode, using random persisted secret.")
      Seq(Secret.check(Secret.persistent()))
    else
      log.warn("No secrets provided for server, using random secret. This means that sessions and flash will not be valid across server restarts, and any existing cookies will be invalidated!")
      Seq(Secret.check(Secret.random()))

  private val dispatchTrie = DispatchTrie[Endpoint]()
  private val websocketDispatchTrie = DispatchTrie[Endpoint]()

  private var notFoundEndpoint: Endpoint = Server.defaultNotFoundEndpoint
  private var methodNotAllowedEndpoint: Endpoint = Server.defaultMethodNotAllowedEndpoint

  private var recovery: Recovery = Server.defaultRecovery(debugMode)

  private val activeInterceptors =
    if debugMode then interceptors ++ debugInterceptors
    else interceptors

  private def addToTrie(
    trie: DispatchTrie[Endpoint],
    path: String,
    methods: Seq[String],
    endpoint: Endpoint
  ): Unit =
    val segs = Util.splitPath(path)

    for method <- methods do
      trie.add(segs, method, endpoint) match
        case DispatchTrie.AddResult.Ok => ()
        case DispatchTrie.AddResult.MethodConflict(path, items) =>
          throw new Exception(
            s"Method conflict when adding endpoint ${endpoint.meta} $methods $path: existing endpoints ${items.map { case (method, ep) => s"$method -> ${ep.meta}" }.mkString(", ")}"
          )
        case DispatchTrie.AddResult.SubpathConflict(path, items) =>
          throw new Exception(
            s"Subpath conflict when adding endpoint ${endpoint.meta} $methods $path: existing endpoints: ${items.values.map(_.meta).mkString(", ")}"
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
            addToTrie(dispatchTrie, path, methods, ep)
          case EndpointSelector.WebSocketUpgrade(path) =>
            addToTrie(websocketDispatchTrie, path, Seq("GET"), ep)
          case EndpointSelector.NotFound =>
            if notFoundEndpoint ne Server.defaultNotFoundEndpoint then
              throw new Exception(
                s"Multiple NotFound endpoints defined: ${notFoundEndpoint.meta} and ${ep.meta}"
              )
            else
              notFoundEndpoint = ep
          case EndpointSelector.MethodNotAllowed =>
            if methodNotAllowedEndpoint ne Server.defaultMethodNotAllowedEndpoint then
              throw new Exception(
                s"Multiple MethodNotAllowed endpoints defined: ${methodNotAllowedEndpoint.meta} and ${ep.meta}"
              )
            else
              methodNotAllowedEndpoint = ep
  end for

  private val interceptorFn =
    Interceptor.compose(activeInterceptors)

  // invokes the endpoint and recovery if needed
  private def invoke(ep: Endpoint, req: Request): WebResponse =
    if WebAppUtil.checkCsrf(req.method, req.headers, csrfAllowedOrigins) then
      ep.invoke(req) match
        case EndpointResult.Success(response) =>
          response
        case failure: EndpointResult.Failure => recovery.invoke(req, ep, failure)
    else
      recovery.invoke(req, ep, EndpointResult.Failure.MethodAbort(403, "CSRF validation failed"))

  private val handlerFunc: Request => WebResponse = (req0: Request) =>
    val sessionData = req0.cookies.get("_session") match
      case None => Map.empty[String, String]
      case Some(cookie) =>
        WebAppUtil.verifyMap(cookie.value, runtimeSecrets)

    val flashData = req0.cookies.get("_flash") match
      case None => Map.empty[String, String]
      case Some(cookie) =>
        WebAppUtil.verifyMap(cookie.value, runtimeSecrets)

    val decodedPathSegments = Util.splitPath(req0.path)

    val lookupResult = if req0.headers.get("upgrade").contains("websocket") then
      websocketDispatchTrie.lookup(decodedPathSegments)
    else
      dispatchTrie.lookup(decodedPathSegments)

    val req = req0.copy(
      pathParams = lookupResult.bindings,
      remainingPathSegments = lookupResult.remainingPathSegments,
      session = sessionData,
      flash = flashData
    )

    val response0 =
      if lookupResult.items.isEmpty then
        interceptorFn(req, notFoundEndpoint, invoke(notFoundEndpoint, _))
      else
        lookupResult.items.get(req0.method) match
          case Some(endpoint) =>
            interceptorFn(req, endpoint, invoke(endpoint, _))
          case None =>
            interceptorFn(req, notFoundEndpoint, invoke(methodNotAllowedEndpoint, _))

    val response = response0 match
      case simple: keg.model.Response[keg.model.Data] =>
        val extraCookies = collection.mutable.ListBuffer.empty[keg.model.Cookie]

        if simple.session != null then
          if simple.session.nonEmpty then
            extraCookies += keg.model.Cookie(
              name = "_session",
              value = WebAppUtil.signMap(
                simple.session,
                System.currentTimeMillis() / 1000L + sessionMaxAgeSeconds,
                runtimeSecrets.head
              ),
              path = "/",
              httpOnly = true,
              sameSite = "Lax",
              maxAge = sessionMaxAgeSeconds
            )
          else if req.cookies.contains("_session") then
            extraCookies += keg.model.Cookie(
              name = "_session",
              value = "",
              path = "/",
              maxAge = 0 // delete cookie
            )

        if simple.flash != null then
          if simple.flash.nonEmpty then
            extraCookies += keg.model.Cookie(
              name = "_flash",
              value = WebAppUtil.signMap(
                simple.flash,
                System.currentTimeMillis() / 1000L + flashMaxAgeSeconds,
                runtimeSecrets.head
              ),
              path = "/",
              httpOnly = true,
              sameSite = "Lax",
              maxAge = flashMaxAgeSeconds
            )
          else if req.cookies.contains("_flash") then
            extraCookies += keg.model.Cookie(
              name = "_flash",
              value = "",
              path = "/",
              maxAge = 0 // delete cookie
            )

        simple.copy(cookies = simple.cookies ++ extraCookies)
      case wsResponse: WsResponse =>
        wsResponse

    response

  private val transport = transportBuilder(listeners, handlerFunc, log.info(_))

  def start(): Unit =
    if activeInterceptors.nonEmpty then
      log.info("Active interceptors: " + activeInterceptors.map(_.toString()).mkString("\n- ", "\n- ", ""))
    else
      log.info("No interceptors active")
    for interceptor <- activeInterceptors do
      interceptor.onStartup(this.endpoints.collect({
        case ep: Endpoint => ep
      }))

    log.info(s"Starting Keg Server")
    transport.start()

    val listeners = transport.getListeners.iterator
    while listeners.hasNext do
      val listener = listeners.next()

      log.info(
        s"Listening on ${listener.asUrl}"
      )

  def serverPort() = transport.getListeners.headOption match
    case Some(keg.transport.Listener.Http(_, port)) => port
    case Some(keg.transport.Listener.Https(_, port, _, _)) => port
    case None => throw new Exception("Server is not listening on any ports")

  def awaitTermination(): Unit =
    transport.awaitTermination()

  def stop(): Unit =
    log.info("Stopping Keg Server")
    transport.stop()

  def kill(): Unit =
    log.info("Killing Keg Server")
    transport.kill()

object Server:

  def debugFromEnv = sys.env.isDefinedAt("KEG_DEBUG") || sys.env.isDefinedAt("DEBUG")

  val defaultNotFoundEndpoint: Endpoint =
    Endpoint(EndpointSelector.NotFound, req => EndpointResult.Failure.MethodAbort(404, "Not Found"), EndpointMeta())
  val defaultMethodNotAllowedEndpoint: Endpoint =
    Endpoint(EndpointSelector.MethodNotAllowed, req => EndpointResult.Failure.MethodAbort(405, "Method Not Allowed"), EndpointMeta())

  def defaultRecovery(debugMode: Boolean): Recovery =
    // in debug mode, we also display the error to the user, in non-debug mode
    // we just log it and return a generic message
    if debugMode then
      val fn = (req: Request, ep: Endpoint, failure: EndpointResult.Failure) =>
        failure match
          case EndpointResult.Failure.ParamAbort(param, code, message) =>
            Response[keg.model.Data](s"Parameter '$param' is invalid: $message", code)
          case EndpointResult.Failure.MethodAbort(code, message) =>
            Response[keg.model.Data](message, code)
          case EndpointResult.Failure.Exception(throwable) =>
            log.error(s"Error in endpoint ${ep.meta} for request ${req.method} ${req.path}", throwable)
            Response[keg.model.Data]("Internal Server Error " + throwable.getMessage, 500)
      Recovery(fn)
    else
      val fn = (req: Request, ep: Endpoint, failure: EndpointResult.Failure) =>
        failure match
          case EndpointResult.Failure.ParamAbort(_, code, _) =>
            Response[keg.model.Data]("Bad Request", code)
          case EndpointResult.Failure.MethodAbort(code, message) =>
            Response[keg.model.Data](message, code)
          case EndpointResult.Failure.Exception(throwable) =>
            log.error(s"Error in endpoint ${ep.meta} for request ${req.method} ${req.path}", throwable)
            Response[keg.model.Data]("Internal Server Error", 500)
      Recovery(fn)
