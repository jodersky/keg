package keg.contrib

import keg.main.Interceptor
import keg.model.{Endpoint, Request, Response, RawResponse, WsResponse}
import java.io.OutputStream

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

object TimingInterceptor:
  private val histogram = ustats.histogram(
    name = "http_request_duration_seconds",
    help = "Histogram of HTTP request durations in seconds",
    buckets = ustats.BucketDistribution.httpRequestDuration,
    labels = Seq("method", "path", "status", "endpoint", "server")
  )

  private val KnownMethods = Set(
    "GET", "HEAD", "POST", "PUT", "PATCH", "DELETE", "OPTIONS", "CONNECT", "TRACE"
  )
  private def methodLabel(m: String) = if KnownMethods.contains(m) then m else "_OTHER"

class TimingInterceptor(server: String = "main") extends Interceptor:
  import TimingInterceptor.*

  // pre-populate the histogram with all endpoints, so that we have metrics for
  // them even if they haven't been hit yet
  override def onStartup(endpoints: Seq[Endpoint]): Unit =
    for ep <- endpoints do
      for m <- ep.methods do
        histogram(methodLabel(m), ep.path, "200", ep.meta.function, server)

  def intercept(request: Request, endpoint: Endpoint, next: Request => RawResponse): RawResponse =
    val t0 = System.nanoTime()
    val response = next(request)

    response match
      case r: Response[keg.Data] =>
        r.copy(
          data = new keg.Data:
            def write(out: OutputStream): Unit =
              try
                r.data.write(out)
              finally
                val delta = (System.nanoTime() - t0) / 1E9
                histogram(
                  methodLabel(request.method),
                  endpoint.path,
                  r.status.toString(),
                  endpoint.meta.function,
                  server
                ).observe(delta)
            def contentLength: Option[Long] = r.data.contentLength
            def headers: Seq[(String, String)] = r.data.headers
        )
      case ws: WsResponse =>
        val delta = (System.nanoTime() - t0) / 1E9
        histogram(
          methodLabel(request.method),
          endpoint.path,
          "101",
          endpoint.meta.function,
          server
        ).observe(delta)
        ws

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

