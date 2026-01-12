package keg.main

import keg.model.{Request, Response, Endpoint, WsResponse}

/** An interceptor is invoked if a request matches an endpoint, but before the
  * endpoint's handler is called. It can modify the request, short-circuit the
  * response, or perform other actions.
  *
  * Interceptors can be used to implement functionality that affects all
  * endpoints *and* which should be opaque to the endpoint handlers themselves.
  * For example, logging or reporting metrics about requests and responses can
  * be done in an interceptor, without the endpoint handlers needing to be aware
  * of it. However, something like authenticating a request and exposing a user
  * value to the endpoint handler is not a good fit for an interceptor if the
  * endpoint does something with the user value (in that case, a better fit
  * would be to define a custom `FromRequest` which does the authentication)[1].
  *
  * [1]: you *could* do authentication in an interceptor and expose the user
  * value as a `ScopedValue` or `ThreadLocal` to the endpoint handler, but that
  * is more error prone since it hides the explicit dependency on the user value
  * from the endpoint.
  */
trait Interceptor:

  /** Handle intercepting a request to an endpoint.
    *
    * The `next` function represents the next step in the processing chain,
    * which ends up invoking the endpoint's handler. The interceptor can choose
    * to:
    * - call `next` with the (possibly modified) request
    * - return a custom response without calling `next`
    * - call `next` and then modify the response before returning it
    * - perform side effects before or after calling `next`
    */
  def intercept(
    request: Request,
    endpoint: Endpoint,
    next: Request => (Response | WsResponse)
  ): Response | WsResponse

  /** Additional endpoints defined by this interceptor. */
  def endpoints: Seq[Endpoint] = Seq.empty

  /** Hook called when the server starts up. */
  def onStartup(endpoints: Seq[Endpoint]): Unit = ()

object Interceptor:

  def compose(interceptors: Seq[Interceptor]): (Request, Endpoint) => (Response | WsResponse) =
    val invoke: (Request, Endpoint) => (Response | WsResponse) =
      (request: Request, endpoint: Endpoint) => endpoint.invoke(request)

    var fn: (Request, Endpoint) => (Response | WsResponse) = invoke

    for interceptor <- interceptors do
      val previousFn = fn
      fn = (request: Request, endpoint: Endpoint) =>
        interceptor.intercept(request, endpoint, (req: Request) => previousFn(req, endpoint))

    fn

class LoggingInterceptor extends Interceptor:
  private val log = org.slf4j.LoggerFactory.getLogger(getClass)

  override def intercept(
    request: Request,
    endpoint: Endpoint,
    next: Request => (Response | WsResponse)
  ): Response | WsResponse =
    log.info(s"Incoming request: ${request.method} ${request.uri}")
    val response = next(request)
    response match
      case resp: Response =>
        log.info(s"Response: ${resp.status} for ${request.method} ${request.uri}")
        resp
      case wsResp: WsResponse =>
        log.info(s"WebSocket response for ${request.method} ${request.uri}")
        wsResp

class TimingInterceptor extends Interceptor:
  val histogram = ustats.histogram(
    name = "http_request_duration_seconds",
    help = "Histogram of HTTP request durations in seconds",
    buckets = ustats.BucketDistribution.httpRequestDuration,
    labels = Seq("method", "path", "status")
  )

  override def onStartup(endpoints: Seq[Endpoint]): Unit =
    for ep <- endpoints do
      ep.selector match
        case keg.model.EndpointSelector.Simple(path, methods) =>
          for method <- methods do
            histogram(method, path, "200")//.reset()
        case _ =>

  def intercept(request: Request, endpoint: Endpoint, next: Request => Response | WsResponse): Response | WsResponse =
    val startTime = System.nanoTime()
    val response = next(request)
    val endTime = System.nanoTime()
    val durationSeconds = (endTime - startTime).toDouble / 1e9

    val status = response match
      case resp: Response => resp.status.toString
      case wsResp: WsResponse => "101" // Switching Protocols

    val hist = histogram(request.method, request.path, status)
    hist.observe(durationSeconds)
    response

// class TimingInterceptor extends Interceptor:
//   val unmatchedDurations = java.util.concurrent.atomic.DoubleAdder()
//   val endpointDurations = scala.collection.concurrent.TrieMap[Endpoint, java.util.concurrent.atomic.DoubleAdder]()

//   override def intercept(
//     request: Request,
//     endpoint: Option[Endpoint],
//     next: Request => InterceptedResponse
//   ): InterceptedResponse =
//     val startTime = System.currentTimeMillis()
//     val ir = next(request)
//     val endTime = System.currentTimeMillis()
//     val duration = endTime - startTime

//     ir.endpoint match
//       case Some(ep) =>
//         endpointDurations.getOrElseUpdate(ep, java.util.concurrent.atomic.DoubleAdder()).add(duration.toDouble)
//       case None =>
//         unmatchedDurations.add(duration.toDouble)
//     ir


// trait Middleware:
//   def onStartup(server: Server): Unit = ()
//   def onShutdown(server: Server): Unit = ()
//   def interceptors: Seq[Interceptor] = Seq.empty

// class TimingMiddleware extends Middleware:
//   val unmatchedDurations = java.util.concurrent.atomic.DoubleAdder()
//   val endpointDurations = scala.collection.concurrent.TrieMap[Endpoint, java.util.concurrent.atomic.DoubleAdder]()

//   override def onStartup(server: Server): Unit =
//     for ep <- server.endpoints do
//       endpointDurations.putIfAbsent(ep, java.util.concurrent.atomic.DoubleAdder())

//   object interceptor extends Interceptor:
//     override def intercept(
//       request: Request,
//       endpoint: Option[Endpoint],
//       next: Request => InterceptedResponse
//     ): InterceptedResponse =
//       val startTime = System.currentTimeMillis()
//       val ir = next(request)
//       val endTime = System.currentTimeMillis()
//       val duration = endTime - startTime

//       ir.endpoint match
//         case Some(ep) =>
//           endpointDurations.getOrElseUpdate(ep, java.util.concurrent.atomic.DoubleAdder()).add(duration.toDouble)
//         case None =>
//           unmatchedDurations.add(duration.toDouble)
//       ir

//   override def interceptors: Seq[Interceptor] = Seq(interceptor)
