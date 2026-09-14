package keg.main

import keg.model.Request
import keg.model.Response
import keg.model.RawResponse
import keg.model.Data
import keg.model.Endpoint
import keg.model.Recovery
import keg.model.EndpointMeta
import keg.model.WsResponse
import keg.model.Recovery
import keg.main.DispatchTrie.DispatchLookup
import keg.util.Util
import java.util.concurrent.TimeoutException

case class AbortException(val code: Int, val message: String) extends Exception(message)

def abort(code: Int, message: String = ""): Nothing =
  throw new AbortException(code, message)

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
    val endpoints: Seq[keg.model.Route],
    listeners: Seq[keg.transport.Listener] = Seq(keg.transport.Listener.Http("::", 8080)),
    debugMode: Boolean = Server.debugFromEnv,
    secrets: Seq[String] = Seq.empty,
    sessionMaxAgeSeconds: Int = 3600,
    flashMaxAgeSeconds: Int =
      60, // flash cookies last for 1 minute, they're consumed on read anyway, so no need to last longer
    csrfAllowedOrigins: Set[String] = Set.empty,
    interceptors: Seq[Interceptor] = Seq.empty,
    debugInterceptors: Seq[Interceptor] = Seq(Reloader),
    transport: keg.transport.Transport = keg.transport.Platform.defaultTransport
):
  // scribe.info(s"Debug mode: $debugMode")
  // scribe.info(s"Initializing Keg Server with ${endpoints.size} endpoints")

  private val runtimeSecrets: Seq[Array[Byte]] =
    if secrets.nonEmpty then
      secrets.map(Secret.check)
    else if debugMode then
      scribe.warn("No secrets provided for server in debug mode, using random persisted secret.")
      Seq(Secret.check(Secret.persistent()))
    else
      scribe.warn("No secrets provided for server, using random secret. This means that sessions and flash will not be valid across server restarts, and any existing cookies will be invalidated!")
      Seq(Secret.check(Secret.random()))

  private val dispatch = EndpointDispatch()
  private var recovery: Recovery = Server.defaultRecovery(debugMode)
  private var recoverySet: Boolean = false

  private val activeInterceptors =
    if debugMode then interceptors ++ debugInterceptors
    else interceptors

  for endpoint <- activeInterceptors.flatMap(_.endpoints) ++ endpoints do
    endpoint match
      case r: Recovery =>
        if recoverySet then
          throw new Exception("Duplicate recovery")
        else
          recovery = r
          recoverySet = true
      case ep: Endpoint =>
        dispatch.add(ep)
  end for

  private val interceptorFn =
    Interceptor.compose(activeInterceptors)

  // invokes the endpoint and recovery if needed
  private def invoke(ep: Endpoint, req: Request): RawResponse =
    try
      if !WebAppUtil.checkCsrf(req.method, req.headers, csrfAllowedOrigins) then
        throw AbortException(403, "CSRF validation failed")
      ep.invoke(req)
    catch
      case scala.util.control.NonFatal(e) =>
        recovery.invoke(req, e, Some(ep))

  private val handlerFunc: Request => RawResponse = (req0: Request) =>
    scribe.debug(s"Received request: ${req0.method} ${req0.path}")
    val sessionData = req0.cookies.get("_session") match
      case None => Map.empty[String, String]
      case Some(cookie) =>
        WebAppUtil.verifyMap(cookie.value, runtimeSecrets)

    val flashData = req0.cookies.get("_flash") match
      case None => Map.empty[String, String]
      case Some(cookie) =>
        WebAppUtil.verifyMap(cookie.value, runtimeSecrets)

    val decodedPathSegments = Util.splitPath(req0.path)

    val lookupResult = dispatch.lookup(decodedPathSegments, req0.method)

    val response0 = lookupResult match
      case None => recovery.invoke(
        req0.copy(session = sessionData, flash = flashData),
        new AbortException(404, "endpoint not found"),
        None
      )
      case Some(result) =>
        val req = req0.copy(
          pathParams = result.bindings,
          remainingPathSegments = result.remainingPathSegments,
          session = sessionData,
          flash = flashData
        )
        interceptorFn(req, result.ep, invoke(result.ep, _))

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
          else if req0.cookies.contains("_session") then
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
          else if req0.cookies.contains("_flash") then
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

  private val backend = transport.init(listeners, handlerFunc)

  def start(): Unit =
    if activeInterceptors.nonEmpty then
      scribe.debug("Active interceptors: " + activeInterceptors.map(_.toString()).mkString("\n- ", "\n- ", ""))
    else
      scribe.debug("No interceptors active")
    for interceptor <- activeInterceptors do
      interceptor.onStartup(this.endpoints.collect({
        case ep: Endpoint => ep
      }))

    scribe.debug(s"Starting Keg server")
    backend.start()

    val listenerInfo = backend.getListeners.map(_.asUrl).mkString(", ")
    if debugMode then
      scribe.warn(s"Keg server in DEBUG mode listening on $listenerInfo")
    else
      scribe.info(s"Keg server listening on $listenerInfo")

  def serverPort() = backend.getListeners.headOption match
    case Some(keg.transport.Listener.Http(_, port)) => port
    case Some(keg.transport.Listener.Https(_, port, _, _, _)) => port
    case None => throw new Exception("Server is not listening on any ports")

  def awaitTermination(): Unit =
    backend.awaitTermination()

  def stop(): Unit =
    scribe.info("stopping keg server")
    backend.stop()

  def kill(): Unit =
    scribe.info("killing keg server")
    backend.kill()

object Server:

  def debugFromEnv = sys.env.isDefinedAt("KEG_DEBUG") || sys.env.isDefinedAt("DEBUG")

  def defaultRecovery(debugMode: Boolean): Recovery =
    // in debug mode, we also display the error to the user, in non-debug mode
    // we just log it and return a generic message
    Recovery(
      (req, err, ep) => err match
        case AbortException(code, message) =>
          if debugMode then
            Response(message, code)
          else
            Response(
              keg.transport.uhttp.UhttpTransport.StandardReasonPhrases.getOrElse(code, "unknown"),
              code
            )
        case _ =>
          if debugMode then
            Response(err.toString, 500)
          else
            Response("Internal server error", 500)
    )
