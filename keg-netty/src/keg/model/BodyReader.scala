package keg.model

import io.netty.buffer.ByteBuf

trait BodyReader[A]:

  /** Called when a chunk of the body is received.
   * @return An optional early Response to send back immediately, bypassing further body reading.
   */
  def onChunk(req: Request, chunk: ByteBuf): Option[Response.Raw]

  /** Called when the body reading ends.
   * @return The fully read body, or an early Response to send back immediately.
   */
  def onEnd(req: Request): Either[Response.Raw, A]

object BodyReader:
  class StringBodyReader(maxSize: Long) extends BodyReader[String]:
    private var totalSize: Long = 0
    private val charset = java.nio.charset.StandardCharsets.UTF_8
    private var builder: StringBuilder = new StringBuilder()

    override def onChunk(req: Request, chunk: ByteBuf): Option[Response.Raw] =
      totalSize += chunk.readableBytes()
      if totalSize > maxSize then
        Some(
          Response(
            Response.Data.StringData("Request body too large"),
            status = 413
          )
        )
      else
        println("chunk is: " + chunk)
        io.netty.util.CharsetUtil.UTF_8.decode(chunk.nioBuffer()).toString() match
          case str =>
            builder.append(str)

        None

    override def onEnd(req: Request): Either[Response.Raw, String] =
      Right(builder.toString())
