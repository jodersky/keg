package keg.model

case class Response[+A](
  data: A,
  status: Int = 200,
  headers: Seq[(String, String)] = Seq.empty
):
  def map[B](f: A => B): Response[B] =
    Response(f(data), status, headers)

object Response:

  type Raw = Response[Data]

  trait Data:
    def contentLength: Option[Long]
    def write(out: java.io.OutputStream): Unit
    def headers: Seq[(String, String)]

  object Data:
    case class StringData(s: String) extends Data:
      private val bytes = s.getBytes(java.nio.charset.StandardCharsets.UTF_8)
      override def contentLength: Option[Long] = None //Some(bytes.length.toLong)
      override def write(out: java.io.OutputStream): Unit =
        out.write(bytes)
      override def headers: Seq[(String, String)] =
        Seq("Content-Type" -> "text/plain; charset=UTF-8")
