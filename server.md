What underlying server should we use?

In keg, each request is run in one method call, which itself can contain
blocking calls. Hence, the underlying server must support synchronous, blocking
code (it can use virtual threads however).

Along with supporting the above design principle, we also need standard
HTTP features: chunked (i.e. streaming) uploads/downloads and websockets.

We also want our server to be minimal and efficient.

### Server comparison

features we need  | Undertow | Netty | FusionAuth Java-HTTP
------------------|----------|-------|---------------------
thread-per-request model (these can be virtual) | x | | x
chunked responses | x | x | x
chunked uploads (e.g. multipart forms) | x | x | x
websockets | x | x |

optional features  | Undertow | Netty | FusionAuth Java-HTTP
------------------|----------|-------|---------------------
form parsing | x | x | x


- FusionAuth seems to be the most modern, relying almost entirely on virtual
  threads. It's API is super simple. Unfortunately it lacks support for
  websockets.

- Netty is an industry standard. However it is also entirely async,
  documentation is lacking, and its API is not easy to use.

- Undertow seems to power some more "enterprise-y" Java programs. It does
  everything we need however, and since cask uses it it's probably a safe
  choice.

### Preliminary choice

In light of the above comparisons, it looks like Undertow is the way to go.

~~However, I have recently read that the newer version of undertow is based on
Netty. It is worthwhile to look at how Undertow reconciles its blocking features
with Netty's, and assess the work it would be to extract that. If we could use
netty directly, it remove one dependency.~~ Undertow announced it planned to use
Netty for version 3, however that seems to never have materialized.
