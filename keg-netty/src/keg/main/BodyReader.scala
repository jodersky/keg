package keg.main

trait BodyReader[A]:
  def onStart(ctx: Context): Unit
  def onChunk(ctx: Context, chunk: io.netty.buffer.ByteBuf): Unit
  def onEnd(ctx: Context): A

object BodyReader:
  def stringReader: BodyReader[String] = new BodyReader[String]:
    private val sb = new StringBuilder()
    def onStart(ctx: Context): Unit = ()
    def onChunk(ctx: Context, chunk: io.netty.buffer.ByteBuf): Unit =
      val str = chunk.toString(java.nio.charset.StandardCharsets.UTF_8)
      sb.append(str)
    def onEnd(ctx: Context): String = sb.toString()

  def emptyReader: BodyReader[Unit] = new BodyReader[Unit]:
    def onStart(ctx: Context): Unit = ()
    def onChunk(ctx: Context, chunk: io.netty.buffer.ByteBuf): Unit = ()
    def onEnd(ctx: Context): Unit = ()
