package keg.model

import scala.Conversion.into

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
case class Response(
    data: into[Data],
    status: Int = 200,
    headers: Seq[(String, String)] = Seq.empty,
    cookies: Seq[Cookie] = Seq.empty,
    session: Map[String, String] = null, // null means no change
    flash: Map[String, String] = Map.empty
)
// def map[B](f: A => B): Response[B] =
//   Response(f(Writer), status, headers)

trait Data:

  /** Write the data to the given HTTP response's output stream. */
  def write(out: java.io.OutputStream): Unit

  /** Headers related to the body. Typically this includes Content-Type and
    * Content-Length.
    * Note however that transfer-encoding related headers (e.g. for chunked
    * encoding) should not be included here, as they are handled by the server
    * infrastructure.
    */
  def headers: Seq[(String, String)]

object Data:
  given stringAsData: Conversion[String, Data] with
    def apply(a: String): Data = new Data:
      val bytes = a.getBytes(java.nio.charset.StandardCharsets.UTF_8)
      override def write(out: java.io.OutputStream): Unit =
        out.write(bytes)

      override def headers: Seq[(String, String)] =
        Seq(
          "Content-Length" -> bytes.length.toString,
          "Content-Type" -> "text/plain; charset=UTF-8"
        )

  given numberAsData[N <: Number]: Conversion[N, Data] with
    def apply(a: N): Data = stringAsData(a.toString)

  given booleanAsData: Conversion[Boolean, Data] with
    def apply(a: Boolean): Data = stringAsData(a.toString)

  given unitAsData: Conversion[Unit, Data] with
    def apply(a: Unit): Data = new Data:
      override def write(out: java.io.OutputStream): Unit = ()
      override def headers: Seq[(String, String)] = Seq()

  given genyAsData: Conversion[geny.Writable, Data] with
    def apply(a: geny.Writable): Data = new Data:
      override def write(out: java.io.OutputStream): Unit =
        a.writeBytesTo(out)

      override def headers: Seq[(String, String)] =
        a.httpContentType.map(ct => "Content-Type" -> ct).toSeq ++
          a.contentLength
            .map(len => "Content-Length" -> len.toString)
            .toSeq

  // Geny uses Scala 2, so we provide an implicit class for conversions
  implicit class GenyAsDataCompat[A](a: A)(implicit f: A => geny.Writable)
      extends Data:
    val writable = f(a)
    override def write(out: java.io.OutputStream): Unit =
      writable.writeBytesTo(out)

    override def headers: Seq[(String, String)] =
      writable.httpContentType.map(ct => "Content-Type" -> ct).toSeq ++
        writable.contentLength
          .map(len => "Content-Length" -> len.toString)
          .toSeq

object Response:

  given dataAsResponse[A](using f: Conversion[A, Data]): Conversion[A, Response]
  with
    def apply(a: A): Response =
      Response(
        data = f(a),
        status = 200,
        headers = Seq.empty
      )

  import scala.language.implicitConversions

  implicit def compat[A](a: A)(implicit f: A => Data): Response =
    Response(
      data = f(a),
      status = 200,
      headers = Seq.empty
    )
