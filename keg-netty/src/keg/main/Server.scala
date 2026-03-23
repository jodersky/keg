package keg.main

import io.netty as netty
import io.netty.util.concurrent.DefaultEventExecutorGroup
import keg.model.Response
import io.netty.channel.ChannelHandlerContext

import keg.model.BodyReader
import keg.model.Request
import keg.model.Response
import io.netty.handler.stream.ChunkedInput
import io.netty.handler.codec.http.HttpChunkedInput
import io.netty.handler.codec.http.DefaultHttpContent
import io.netty.handler.codec.http.LastHttpContent

// type ResponseType =
//   Response |
//   WebSocketResponse |
//   File


case class Endpoint[A](
  path: String,
  method: String,
  bodyReader: () => BodyReader[A],
  handler: (Request, A) => Response.Raw,
)

object Global:
  val pool = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()

class RouterHandler(endpoints: List[Endpoint[?]]) extends netty.channel.SimpleChannelInboundHandler[netty.handler.codec.http.HttpObject]:
  private var endpoint: Endpoint[?] = null
  private var request: Request = null
  private var bp: BodyReader[?] = null

  // override def channelActive(ctx: ChannelHandlerContext): Unit =
  //   println(s"Connection established from ${ctx.channel().remoteAddress()}")
  //   ctx.channel().read()
  //   super.channelActive(ctx)

  def writeResponse(
    ctx: ChannelHandlerContext,
    response: Response.Raw
  ) =
    response.data.contentLength match

      case Some(len) if len < 8192 =>
        val buf = netty.buffer.Unpooled.buffer(len.toInt)
        response.data.write(new java.io.OutputStream {
          override def write(b: Int): Unit =
            buf.writeByte(b)
          override def write(b: Array[Byte]): Unit =
            buf.writeBytes(b)
          override def write(b: Array[Byte], off: Int, len: Int): Unit =
            buf.writeBytes(b, off, len)
        })
        val nettyResponse = new netty.handler.codec.http.DefaultFullHttpResponse(
          netty.handler.codec.http.HttpVersion.HTTP_1_1,
          netty.handler.codec.http.HttpResponseStatus.valueOf(response.status),
          buf
        )
        response.data.headers.foreach { case (key, value) =>
          nettyResponse.headers().set(key, value)
        }
        response.headers.foreach { case (key, value) =>
          nettyResponse.headers().set(key, value)
        }
        nettyResponse.headers().set(
          netty.handler.codec.http.HttpHeaderNames.CONTENT_LENGTH,
          len
        )
        ctx.writeAndFlush(nettyResponse)//.addListener(netty.channel.ChannelFutureListener.CLOSE)

      case _ =>
        val nettyResponse = new netty.handler.codec.http.DefaultHttpResponse(
          netty.handler.codec.http.HttpVersion.HTTP_1_1,
          netty.handler.codec.http.HttpResponseStatus.valueOf(response.status)
        )
        response.data.headers.foreach { case (key, value) =>
          nettyResponse.headers().set(key, value)
        }
        response.headers.foreach { case (key, value) =>
          nettyResponse.headers().set(key, value)
        }
        nettyResponse.headers().set(
          netty.handler.codec.http.HttpHeaderNames.TRANSFER_ENCODING,
          netty.handler.codec.http.HttpHeaderValues.CHUNKED
        )
        ctx.writeAndFlush(nettyResponse)//.addListener(netty.channel.ChannelFutureListener.CLOSE)

        val buffer = netty.buffer.Unpooled.buffer(8192)

        val stream = new java.io.OutputStream {
          override def write(b: Int): Unit =
            buffer.writeByte(b)
            ctx.writeAndFlush(DefaultHttpContent(buffer)).await()
            buffer.clear()

          override def write(b: Array[Byte]): Unit =
            buffer.writeBytes(b)
            ctx.writeAndFlush(DefaultHttpContent(buffer)).await()
            buffer.clear()

          override def write(b: Array[Byte], off: Int, len: Int): Unit =
            buffer.writeBytes(b, off, len)
            ctx.writeAndFlush(DefaultHttpContent(buffer)).await()
            buffer.clear()
        }
        val runnable: Runnable = () =>
          response.data.write(stream)
          ctx.writeAndFlush(LastHttpContent.EMPTY_LAST_CONTENT).await()


        Global.pool.submit(runnable)

  end writeResponse

  override def channelRead0(
      nctx: netty.channel.ChannelHandlerContext,
      msg: netty.handler.codec.http.HttpObject
  ): Unit =
    msg match
      case req: netty.handler.codec.http.HttpRequest =>
        System.err.println(s"Incoming request: ${req.method.name()} ${req.uri()}")
        endpoints.find(ep => ep.path == req.uri() && ep.method == req.method.name()) match

          case Some(ep) =>
            System.err.println(s"Matched endpoint: ${ep.method} ${ep.path}")
            endpoint = ep
            request = Request(
              method = req.method.name(),
              uri = req.uri(),
              headers = Map.empty,
              pathParams = Map.empty,
              remainingPathSegments = Seq.empty,
              cookies = Map.empty,
              session = Map.empty,
              flash = Map.empty
            )
            bp = endpoint.bodyReader()

            System.err.println(s"Initialized body reader: ${bp.getClass.getName}")

          case None =>
            // No matching endpoint found, send 404 response
            val response = new netty.handler.codec.http.DefaultFullHttpResponse(
              netty.handler.codec.http.HttpVersion.HTTP_1_1,
              netty.handler.codec.http.HttpResponseStatus.NOT_FOUND,
              netty.buffer.Unpooled.copiedBuffer(
                "404 Not Found\n",
                java.nio.charset.StandardCharsets.UTF_8
              )
            )
            nctx.writeAndFlush(response).addListener(netty.channel.ChannelFutureListener.CLOSE)

      case content: netty.handler.codec.http.HttpContent =>
        System.err.println("received content chunk")
        bp.onChunk(request, content.content()) match
          case None => // continue
            System.err.println("continuing to read chunks")

          case Some(early) =>
            System.err.println("early response from body reader")
            writeResponse(nctx, early)

        if content.isInstanceOf[netty.handler.codec.http.LastHttpContent] then
          System.err.println("received last content chunk")
          bp.onEnd(request) match
            case Left(early) =>
              System.err.println("early response from body reader on end")
              writeResponse(nctx, early)
            case Right(body) =>
              System.err.println("completed body read")
              val response = endpoint.asInstanceOf[Endpoint[Any]].handler(request, body)
              writeResponse(nctx, response)

        // nctx.channel().config().setAutoRead(false) // Disable auto-read to control flow

        // val runnable: Runnable = () =>
        //   System.err.println("running chunk decode")
        //   try
        //     bp.onChunk(request, content.content()) match
        //       case None => // continue
        //       case Some(early) => writeResponse(nctx, early)
        //   finally
        //     nctx.executor().execute(() =>
        //       System.err.println("re-enabling auto-read")
        //       nctx.channel().config().setAutoRead(true) // Re-enable auto-read
        //       nctx.channel().read() // Trigger read for the first chunk
        //     )
        // Global.pool.submit(runnable)

        // if content.isInstanceOf[netty.handler.codec.http.LastHttpContent] then
        //   System.err.println("received last content chunk")
        //   val runnable: Runnable = () =>
        //   try
        //     bp.onEnd(request) match
        //       case Left(early) => writeResponse(nctx, early)
        //       case Right(body) =>
        //         System.err.println("completed body read")
        //         val response = endpoint.asInstanceOf[Endpoint[Any]].handler(request, body)
        //         writeResponse(nctx, response)
        //   finally
        //     nctx.executor().execute(() =>
        //       nctx.channel().config().setAutoRead(true) // Re-enable auto-read
        //       nctx.channel().read() // Trigger read for the first chunk
        //     )
        //   Global.pool.submit(runnable)

class Server(
  port: Int,
  endpoints: List[Endpoint[?]]
):

  def run(): Unit =
    val bossGroup = netty.channel.nio.NioEventLoopGroup()
    val workerGroup = netty.channel.nio.NioEventLoopGroup(
      0,
      java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()
    )
    val appGroup = DefaultEventExecutorGroup(16)
    try
      val b = netty.bootstrap.ServerBootstrap()
      b.group(bossGroup, workerGroup)
        .channel(classOf[netty.channel.socket.nio.NioServerSocketChannel])
        .childHandler(
          new netty.channel.ChannelInitializer[
            netty.channel.socket.SocketChannel
          ] {
            override def initChannel(
                ch: netty.channel.socket.SocketChannel
            ): Unit =
              val p = ch.pipeline()
              p.addLast("codec", new netty.handler.codec.http.HttpServerCodec())
              p.addLast("chunkedWriter", new netty.handler.stream.ChunkedWriteHandler())
              // p.addLast("aggregator", new netty.handler.codec.http.HttpObjectAggregator(65536))
              p.addLast(appGroup, "router", new RouterHandler(endpoints))
          }
        )
        // .option(netty.channel.ChannelOption.AUTO_READ, false)
        // .option(netty.channel.ChannelOption.SO_BACKLOG, 128)
        // .childOption(netty.channel.ChannelOption.SO_KEEPALIVE, true)

      val channelFuture = b.bind(port).sync()
      println(s"Server started on port $port")
      channelFuture.channel().closeFuture().sync()
    finally
      bossGroup.shutdownGracefully()
      workerGroup.shutdownGracefully()

