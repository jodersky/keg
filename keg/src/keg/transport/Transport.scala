package keg.transport

import keg.model.{Request, Response, WebResponse}
import keg.main.Listener

trait TransportBuilder:
  type TransportImpl <: Transport

  /** Initializes the transport with the given listeners and request handler.
    *
    * The transport should listen on the specified listeners, but should not
    * start accepting requests until the `start()` method is called.
    *
    * The handler will be called for each incoming request, and should return a
    * response to be sent back to the client. The transport is responsible for
    * converting the request and response to the appropriate format for the
    * underlying transport.
    *
    * However it must not use the following fields:
    * - when creating a Request: remainingPathSegments, session or flash (they
    *   will be handled by the application layer)
    * - when serializing a Response: session, flash should not be used (they
    *   will have been converted to cookies by the application layer)
    *
    */
  def build(
    listeners: Seq[Listener],
    handler: Request => keg.model.WebResponse,
    log: String => Unit
  ): TransportImpl

trait Transport:

  /** Starts the transport, making it ready to accept requests. */
  def start(): Unit

  /** Actual listeners that the service is listening on, useful for testing when using port 0 */
  def getListeners: Seq[Listener]

  /** Stops the transport, making it no longer accept requests.
    *
    * Requests will no longer be accepted, but existing requests will still be
    * allowed to complete.
    *
    * @see kill() for a more forceful shutdown that does not allow existing
    * requests to complete.
    */
  def stop(): Unit

  /** Stops the transport immediately, not allowing existing requests to
    * complete.
    *
    * @see stop() for a graceful shutdown that allows existing requests to complete.
    */
  def kill(): Unit

  /** Blocks until the transport has fully stopped. */
  def awaitTermination(): Unit


object Transport:

  type BuilderFunc = (Seq[Listener], Request => WebResponse, String => Unit) => Transport
