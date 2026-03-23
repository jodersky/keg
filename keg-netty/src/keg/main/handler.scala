package keg.main

import io.netty as netty
import java.nio.file.{Files, Paths, StandardOpenOption}
import java.nio.channels.FileChannel
import io.netty.handler.codec.http.multipart.HttpPostRequestDecoder

class CustomHttpHandler extends netty.channel.SimpleChannelInboundHandler[netty.handler.codec.http.HttpObject]:
  private var currentRequest: netty.handler.codec.http.HttpRequest = null
  private var bodyAccumulator: StringBuilder = null
  private var fileChannel: FileChannel = null

  private var decoder: HttpPostRequestDecoder = null



  override def channelRead0(
      ctx: netty.channel.ChannelHandlerContext,
      msg: netty.handler.codec.http.HttpObject
  ): Unit =
    msg match
      case req: netty.handler.codec.http.HttpRequest =>
        currentRequest = req


        req.uri() match
          case "/foo" =>
            // Initialize body accumulator for /foo
            bodyAccumulator = new StringBuilder()

          case "/bar" =>
            // Initialize file streaming for /bar
            val path = Paths.get("output.txt")
            fileChannel = FileChannel.open(
              path,
              StandardOpenOption.CREATE,
              StandardOpenOption.WRITE,
              StandardOpenOption.TRUNCATE_EXISTING
            )

          case _ =>
            // Return 404 immediately for other paths
            send404(ctx)
            return

      case content: netty.handler.codec.http.HttpContent =>
        if (currentRequest != null) {


          currentRequest.uri() match
            case "/foo" =>
              // Accumulate body content as string
              if (bodyAccumulator != null) {
                val data = content.content().toString(java.nio.charset.StandardCharsets.UTF_8)
                bodyAccumulator.append(data)
              }

            case "/bar" =>
              // Stream content directly to file
              if (fileChannel != null && fileChannel.isOpen()) {
                val buffer = content.content().nioBuffer()
                fileChannel.write(buffer)
              }

            case _ =>
              // Already handled with 404
        }

        // Check if this is the last content
        if (content.isInstanceOf[netty.handler.codec.http.LastHttpContent]) {

          currentRequest.uri() match
            case "/foo" =>
              // Echo the accumulated body
              val body = if (bodyAccumulator != null) bodyAccumulator.toString() else ""
              val responseContent = netty.buffer.Unpooled.copiedBuffer(
                body,
                java.nio.charset.StandardCharsets.UTF_8
              )
              sendResponse(ctx, netty.handler.codec.http.HttpResponseStatus.OK, responseContent)
              bodyAccumulator = null

            case "/bar" =>
              // Close file and send success response
              if (fileChannel != null) {
                fileChannel.close()
                fileChannel = null
              }
              val responseContent = netty.buffer.Unpooled.copiedBuffer(
                "File saved successfully\n",
                java.nio.charset.StandardCharsets.UTF_8
              )
              sendResponse(ctx, netty.handler.codec.http.HttpResponseStatus.OK, responseContent)

            case _ =>
              // Already handled with 404

          currentRequest = null
        }

      case _ =>
        // Ignore other message types

  private def sendResponse(
      ctx: netty.channel.ChannelHandlerContext,
      status: netty.handler.codec.http.HttpResponseStatus,
      content: netty.buffer.ByteBuf
  ): Unit =
    val response = new netty.handler.codec.http.DefaultFullHttpResponse(
      netty.handler.codec.http.HttpVersion.HTTP_1_1,
      status,
      content
    )
    response.headers().set(
      netty.handler.codec.http.HttpHeaderNames.CONTENT_TYPE,
      "text/plain; charset=UTF-8"
    )
    response.headers().set(
      netty.handler.codec.http.HttpHeaderNames.CONTENT_LENGTH,
      content.readableBytes()
    )
    ctx.writeAndFlush(response)

  private def send404(ctx: netty.channel.ChannelHandlerContext): Unit =
    val content = netty.buffer.Unpooled.copiedBuffer(
      "404 Not Found\n",
      java.nio.charset.StandardCharsets.UTF_8
    )
    sendResponse(ctx, netty.handler.codec.http.HttpResponseStatus.NOT_FOUND, content)

  override def channelInactive(ctx: netty.channel.ChannelHandlerContext): Unit =
    // Cleanup resources if connection closes
    if (fileChannel != null && fileChannel.isOpen()) {
      fileChannel.close()
      fileChannel = null
    }
    bodyAccumulator = null
    currentRequest = null
    super.channelInactive(ctx)

  override def exceptionCaught(
      ctx: netty.channel.ChannelHandlerContext,
      cause: Throwable
  ): Unit =
    println("Exception caught:")
    cause.printStackTrace()
    // Cleanup on error
    if (fileChannel != null && fileChannel.isOpen()) {
      fileChannel.close()
      fileChannel = null
    }
    ctx.close()
