package keg.main

import io.netty as netty
import io.netty.handler.stream.ChunkedStream
import io.netty.handler.codec.http.HttpChunkedInput

class Context(val underlying: netty.channel.ChannelHandlerContext):

  def beginChunkedResponse(
    code: Int,
    headers: Map[String, String]
  ): Unit =
    val response = new netty.handler.codec.http.DefaultHttpResponse(
      netty.handler.codec.http.HttpVersion.HTTP_1_1,
      netty.handler.codec.http.HttpResponseStatus.valueOf(code)
    )
    for (k, v) <- headers do
      response.headers().set(k, v)

    response.headers().set(
      netty.handler.codec.http.HttpHeaderNames.TRANSFER_ENCODING,
      "chunked"
    )
    underlying.write(response)

  def writeChunk(chunk: netty.buffer.ByteBuf): Unit =
    val httpChunk = new netty.handler.codec.http.DefaultHttpContent(chunk)
    underlying.write(httpChunk)

  def endChunkedResponse(): Unit =
    val lastChunk = netty.handler.codec.http.LastHttpContent.EMPTY_LAST_CONTENT
    underlying.writeAndFlush(lastChunk)

  def sendChunked(
    code: Int,
    headers: Map[String, String],
    stream: java.io.InputStream
  ) =
    beginChunkedResponse(code, headers)
    val cs = new ChunkedStream(stream)
    underlying.writeAndFlush(HttpChunkedInput(cs))

  def sendFull(
    code: Int,
    headers: Map[String, String],
    content: netty.buffer.ByteBuf
  ): Unit =
    val response = new netty.handler.codec.http.DefaultFullHttpResponse(
      netty.handler.codec.http.HttpVersion.HTTP_1_1,
      netty.handler.codec.http.HttpResponseStatus.valueOf(code),
      content
    )
    for (k, v) <- headers do
      response.headers().set(k, v)

    response.headers().set(
      netty.handler.codec.http.HttpHeaderNames.CONTENT_LENGTH,
      content.readableBytes()
    )

    underlying.writeAndFlush(response)

  def sendFull(
    code: Int,
    headers: Map[String, String],
    content: String
  ): Unit =
    val byteBuf = netty.buffer.Unpooled.copiedBuffer(
      content,
      java.nio.charset.StandardCharsets.UTF_8
    )
    sendFull(code, headers, byteBuf)

  def sendFull(
    code: Int,
    headers: Map[String, String],
    content: Array[Byte]
  ): Unit =
    val byteBuf = netty.buffer.Unpooled.copiedBuffer(content)
    sendFull(code, headers, byteBuf)

  def sendStream(
    code: Int,
    headers: Map[String, String],
    w: java.io.OutputStream => Unit
  ): Unit =
    val response = new netty.handler.codec.http.DefaultHttpResponse(
      netty.handler.codec.http.HttpVersion.HTTP_1_1,
      netty.handler.codec.http.HttpResponseStatus.valueOf(code)
    )
    for (k, v) <- headers do
      response.headers().set(k, v)

    underlying.write(response)


    // val buffer = netty.buffer.Unpooled.buffer(8192)
    val buffer = underlying.alloc().buffer(8192)

    val stream = new java.io.OutputStream {
      override def write(b: Int): Unit =
        println("writing byte " + b)
        buffer.writeByte(b)
        underlying.writeAndFlush(buffer)

      override def write(b: Array[Byte]): Unit =
        println("writing byte array of size " + b.length)
        buffer.writeBytes(b)
        println("flushing buffer")
        underlying.writeAndFlush(buffer)
        println("flushed buffer")

      override def write(b: Array[Byte], off: Int, len: Int): Unit =
        println("writing chunk of size " + len)
        buffer.writeBytes(b, off, len)
        underlying.writeAndFlush(buffer)
    }
    w(stream)
    underlying.close()


  def sendReadable(
    code: Int,
    headers: Map[String, String],
    readable: geny.Readable
  ): Unit =
    val response = new netty.handler.codec.http.DefaultHttpResponse(
      netty.handler.codec.http.HttpVersion.HTTP_1_1,
      netty.handler.codec.http.HttpResponseStatus.valueOf(code)
    )
    for (k, v) <- headers do
      response.headers().set(k, v)

    underlying.write(response)

    readable.readBytesThrough(
      stream =>
        println("sending readable stream")
        val cs = ChunkedStream(stream)
        underlying.writeAndFlush(HttpChunkedInput(cs)).await()
    )
    underlying.close()

    // underlying.write(response)
