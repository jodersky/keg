package keg.extras

import keg.main.Interceptor
import keg.model.{Endpoint, Request, Response, RawResponse, WsResponse}

class LoggingInterceptor extends Interceptor:

  override def intercept(
    request: Request,
    endpoint: Endpoint,
    next: Request => RawResponse
  ): RawResponse =
    scribe.info(s"Incoming request: ${request.method} ${request.path}")
    val response = next(request)
    response match
      case resp: Response[keg.model.Data] =>
        scribe.info(s"Response: ${resp.status} for ${request.method} ${request.path}")
        resp
      case wsResp: WsResponse =>
        scribe.info(s"WebSocket response for ${request.method} ${request.path}")
        wsResp

class TimingInterceptor extends Interceptor:
  val histogram = ustats.histogram(
    name = "http_request_duration_seconds",
    help = "Histogram of HTTP request durations in seconds",
    buckets = ustats.BucketDistribution.httpRequestDuration,
    labels = Seq("method", "path", "status")
  )

  // pre-populate the histogram with all endpoints and common status codes, so
  // that we have metrics for them even if they haven't been hit yet
  override def onStartup(endpoints: Seq[Endpoint]): Unit =
    for ep <- endpoints do
      for method <- ep.methods do
        histogram(method, ep.path, "200")

  def intercept(request: Request, endpoint: Endpoint, next: Request => RawResponse): RawResponse =
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
      "/metrics", Seq("GET"),
      invoke = _ =>
        Response(
          data = ustats.Registry.global
        )
    )
  )

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

  override def intercept(request: Request, endpoint: Endpoint, next: Request => RawResponse): RawResponse =
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

