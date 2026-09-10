# RFC 1 - Simplify routing and endpoint hierarchy

## Background

Currently, when defining endpoints manually we have too much ceremony.

Consider this example:

```scala
keg.Server(
  Seq(
    keg.Endpoint(
      keg.EndpointSelector.Simple("/metrics", Seq("GET")),
      _ => keg.EndpointResult.Success(
        keg.Response("hello, world")
      )
    )
  )
)
```

the important things are:

- the path "/metrics"
- the methods Seq("GET")
- the response (content, headers etc)

The rest is ceremony.

Without loosing anything, we could make this look like:

```scala
keg.Server(
  Seq(
    keg.Endpoint("/metrics", _ => "hello, world")
  )
)
```

While endpoints are most often written using the annotation-based API, in
practice it does happen that endpoints are written directly by a user.

### Proposal

1. Refactor `DispatchTrie` to support falling back to wildcards. This way, not
   found and method not allowed fallbacks can be modeled with regular endpoints
   rather than special handlers.

2. Drop special routing for websockets. Websockets are just a different response
   type; they can use a regular `get()` annotation.

2. Refactor endpoint hierarchy: drop `EndpointSelector` and special endpoint
   handlers. There is now only `Endpoint` which contains path and methods. All in
   all, the sealed trait `Route` is now either an `Endpoint` or `Recovery`.

3. Remove `EndpointResult`, raise exceptions instead.

   Rationale:

   In user code we already expose `keg.abort()`, which is the preferred way to
   handle errors in annotation-based routes. The `routes()` macro simply catches
   an exception and turns it into a failed endpoint result.

   We'll also always need to support user code throwing, since that's what the
   JVM does and we don't want to force users into a particular ecosystem/way of
   wiring things.

   Failed endpoints aren't recoverable except in ONE dedicated place (the
   recovery route). That means user code won't need to deal with try-catches to
   deal with failed endpoints.

   Note that we'll still keep result types for the parameter parsers, mostly out
   of conservatism.

4. Add some common conversions to convert `Response[A]` to `Response[Data]` if
   `A` can be converted to `Data`.
