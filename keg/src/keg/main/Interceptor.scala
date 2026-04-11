package keg.main

import keg.model.{EndpointSelector, Request, Response, Endpoint, WsResponse, FinalResponse}

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
    next: Request => FinalResponse
  ): FinalResponse

  /** Additional endpoints defined by this interceptor. */
  def endpoints: Seq[Endpoint] = Seq.empty

  /** Hook called when the server starts up. */
  def onStartup(endpoints: Seq[Endpoint]): Unit = ()

object Interceptor:

  def compose(
    interceptors: Seq[Interceptor]
  ): (Request, Endpoint, Request => FinalResponse) => FinalResponse =
    var fn: (Request, Endpoint, Request => FinalResponse) => FinalResponse = (req, _, invoke) => invoke(req)

    for interceptor <- interceptors do
      val previousFn = fn
      fn = (request: Request, endpoint: Endpoint, invoke: Request => FinalResponse) =>
        interceptor.intercept(request, endpoint, (req: Request) => previousFn(req, endpoint, invoke))

    fn
