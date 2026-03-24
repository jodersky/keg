package keg.model

import scala.Conversion.into

// Design note: why make Response[A] generic but require Response[Data] in
// endpoints, instead of making the data field of a response always be of type
// Data?
//
// The reason is a mix of ergonomics and avoiding code duplication. At the end
// of the day we always need a Response[Data] to actually write to the wire as
// an HTTP response body, however we'd also like users to be able to pass in
// common Scala types which can be converted to Data. Furthermore, in our
// derivation "API" layer, we'd like to be able to customize what types can be
// converted and how.
//
// We could of course create two separate Response classes, one in this package
// requiring a body of type Data, and one in the derivation package allowing
// arbitrary data, however not much is gained, hence we decide to make it
// generic and add a type alias for concrete returnable responses.

/** A simple HTTP response.
  * @param data
  *   The response body data.
  * @param status
  *   The HTTP status code.
  * @param headers
  *   The response headers.
  * @param cookies
  *   The response cookies.
  * @param session
  *   The session data to set in the response. If `null`, the session is not
  *   modified. If non-null, the session cookie will be updated accordingly.
  *   Hence, to *add* session data, be sure to include the existing session data
  *   as well, e.g. `request.session ++ Map("key" -> "value")`.
  * @param flash
  *   The flash data to set in the response. Flash data is accessible only in
  *   the next request, and will automatically be cleared afterwards. It is
  *   typically used for one-time messages (e.g. success or error messages after
  *   a redirect). Note that you *can* keep existing flash data for the next
  *   request by setting flash to null, but this is not common practice.
  */
case class Response[+A](
    data: into[A],
    status: Int = 200,
    headers: Seq[(String, String)] = Seq.empty,
    cookies: Seq[Cookie] = Seq.empty,
    session: Map[String, String] = null, // null means no change
    flash: Map[String, String] = Map.empty
)
// def map[B](f: A => B): Response[B] =
//   Response(f(Writer), status, headers)

type WebResponse = Response[Data] | WsResponse

trait Data:

  /** Write the data to the given HTTP response's output stream. */
  def write(out: java.io.OutputStream): Unit

  /** Headers related to the body. Typically this includes Content-Type.
   *
    * Note however that transfer-encoding related headers (e.g. Content-Length,
    * or chunked encoding) should not be included here, as they are handled by
    * the server infrastructure.
    */
  def headers: Seq[(String, String)]

  def contentLength: Option[Long]

object Data:

  given stringAsData: Conversion[String, Data] with
    def apply(a: String): Data = new Data:
      val bytes = a.getBytes(java.nio.charset.StandardCharsets.UTF_8)
      override def write(out: java.io.OutputStream): Unit =
        out.write(bytes)

      override def headers: Seq[(String, String)] =
        Seq(
          "Content-Type" -> "text/plain; charset=UTF-8"
        )

      def contentLength: Option[Long] = Some(bytes.length.toLong)

  given numberAsData[N <: Number]: Conversion[N, Data] with
    def apply(a: N): Data = stringAsData(a.toString)

  given booleanAsData: Conversion[Boolean, Data] with
    def apply(a: Boolean): Data = stringAsData(a.toString)

  given unitAsData: Conversion[Unit, Data] with
    def apply(a: Unit): Data = new Data:
      override def write(out: java.io.OutputStream): Unit = ()
      override def headers: Seq[(String, String)] = Seq()
      def contentLength: Option[Long] = Some(0L)

  given genyAsData: Conversion[geny.Writable, Data] with
    def apply(a: geny.Writable): Data = new Data:
      override def write(out: java.io.OutputStream): Unit =
        a.writeBytesTo(out)

      override def headers: Seq[(String, String)] =
        a.httpContentType.map(ct => "Content-Type" -> ct).toSeq

      def contentLength: Option[Long] = a.contentLength

  // Geny uses Scala 2, so we provide an implicit class for conversions
  implicit class GenyAsDataCompat[A](a: A)(implicit f: A => geny.Writable)
      extends Data:
    val writable = f(a)
    override def write(out: java.io.OutputStream): Unit =
      writable.writeBytesTo(out)

    override def headers: Seq[(String, String)] =
      writable.httpContentType.map(ct => "Content-Type" -> ct).toSeq

    def contentLength: Option[Long] = writable.contentLength

// object Response:

//   given dataAsResponse[A](using f: Conversion[A, Data]): Conversion[A, Response[Data]] with
//     def apply(a: A): Response[Data] =
//       Response(
//         data = f(a),
//         status = 200,
//         headers = Seq.empty
//       )

//   import scala.language.implicitConversions

//   implicit def compat[A](a: A)(implicit f: A => Data): Response[Data] =
//     Response(
//       data = f(a),
//       status = 200,
//       headers = Seq.empty
//     )
