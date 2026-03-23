package keg.main

import keg.model.{EndpointSelector, Request, Response, Endpoint, WsResponse, WebResponse}

/** An interceptor is invoked if a request matches an endpoint, but before the
  * endpoint's invoke handler is called. It can modify the request,
  * short-circuit the response, or perform other actions.
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
    next: Request => WebResponse
  ): WebResponse

  /** Additional endpoints defined by this interceptor. */
  def endpoints: Seq[Endpoint] = Seq.empty

  /** Hook called when the server starts up. */
  def onStartup(endpoints: Seq[Endpoint]): Unit = ()

object Interceptor:

  def compose(
    interceptors: Seq[Interceptor]
  ): (Request, Endpoint, Request => WebResponse) => WebResponse =
    var fn: (Request, Endpoint, Request => WebResponse) => WebResponse = (req, _, invoke) => invoke(req)

    for interceptor <- interceptors do
      val previousFn = fn
      fn = (request: Request, endpoint: Endpoint, invoke: Request => WebResponse) =>
        interceptor.intercept(request, endpoint, (req: Request) => previousFn(req, endpoint, invoke))

    fn

class LoggingInterceptor extends Interceptor:
  private val log = org.slf4j.LoggerFactory.getLogger(getClass)

  override def intercept(
    request: Request,
    endpoint: Endpoint,
    next: Request => WebResponse
  ): WebResponse =
    log.info(s"Incoming request: ${request.method} ${request.path}")
    val response = next(request)
    response match
      case resp: Response[keg.model.Data] =>
        log.info(s"Response: ${resp.status} for ${request.method} ${request.path}")
        resp
      case wsResp: WsResponse =>
        log.info(s"WebSocket response for ${request.method} ${request.path}")
        wsResp

class TimingInterceptor extends Interceptor:
  val histogram = ustats.histogram(
    name = "http_request_duration_seconds",
    help = "Histogram of HTTP request durations in seconds",
    buckets = ustats.BucketDistribution.httpRequestDuration,
    labels = Seq("method", "path", "status")
  )

  // faster lookup
  // val byEndpoint = java.util.concurrent.ConcurrentHashMap[Endpoint, ustats.RawHistogram]()

  override def onStartup(endpoints: Seq[Endpoint]): Unit =
    for ep <- endpoints do
      ep.selector match
        case EndpointSelector.Simple(path, methods) =>
          for method <- methods do
            histogram(method, path, "200")//.reset()
        case _ =>

  def intercept(request: Request, endpoint: Endpoint, next: Request => WebResponse): WebResponse =
    val startTime = System.nanoTime()
    val response = next(request)
    val endTime = System.nanoTime()
    val durationSeconds = (endTime - startTime).toDouble / 1e9

    val status = response match
      case resp: Response[keg.model.Data] => resp.status.toString
      case wsResp: WsResponse => "101" // Switching Protocols

    val hist = histogram(request.method, request.path, status)
    hist.observe(durationSeconds)
    response

  override def endpoints: Seq[Endpoint] = Seq(
    Endpoint(
      selector = keg.model.EndpointSelector.Simple("/metrics", Seq("GET")),
      invoke = _ =>
        keg.model.EndpointResult.Success(
          Response(
            data = ustats.Registry.global
          )
        )
    )
  )

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


// a simple token-bucket rate limiter
case class RateLimiter(
  reqsPer: Long = 30,
  unitOfTime: java.time.temporal.TemporalUnit = java.time.temporal.ChronoUnit.MINUTES
) extends Interceptor:

  private class TokenBucket(var tokens: Long, var lastRefillTime: Long):
    val lock = java.util.concurrent.locks.ReentrantLock()

    def tryConsume(): Boolean =
      lock.lock()
      try
        refillIfNeeded()
        if tokens > 0 then
          tokens -= 1
          true
        else
          false
      finally
        lock.unlock()

    private def refillIfNeeded(): Unit =
      val now = System.currentTimeMillis()
      val refillIntervalMillis = unitOfTime.getDuration.toMillis / reqsPer
      if now - lastRefillTime >= refillIntervalMillis then
        val newTokens = ((now - lastRefillTime) / refillIntervalMillis).toLong
        tokens = math.min(tokens + newTokens, reqsPer)
        lastRefillTime = now

  private val cache = com.github.benmanes.caffeine.cache.Caffeine.newBuilder()
    .expireAfterAccess(java.time.Duration.of(reqsPer, unitOfTime))
    .build[String, TokenBucket]()

  override def intercept(request: Request, endpoint: Endpoint, next: Request => WebResponse): WebResponse =
    val clientId = request.remoteIp.getHostAddress()
    val bucket = cache.get(clientId, _ => new TokenBucket(reqsPer, 0L))
    if bucket.tryConsume() then
      next(request)
    else
      Response(
        data = "Too Many Requests",
        status = 429,
        headers = Seq("Content-Type" -> "text/plain; charset=UTF-8")
      )

