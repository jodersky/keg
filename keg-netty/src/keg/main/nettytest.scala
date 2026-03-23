package keg.main

import io.netty as netty

class WebSocketHandler extends netty.channel.SimpleChannelInboundHandler[netty.handler.codec.http.websocketx.WebSocketFrame]:
  override def channelRead0(
      ctx: netty.channel.ChannelHandlerContext,
      frame: netty.handler.codec.http.websocketx.WebSocketFrame
  ): Unit =
    frame match
      case text: netty.handler.codec.http.websocketx.TextWebSocketFrame =>
        val message = text.text()
        println(s"WebSocket received: $message")
        ctx.writeAndFlush(
          new netty.handler.codec.http.websocketx.TextWebSocketFrame(s"Echo: $message")
        )
      case ping: netty.handler.codec.http.websocketx.PingWebSocketFrame =>
        ctx.writeAndFlush(
          new netty.handler.codec.http.websocketx.PongWebSocketFrame(ping.content().retain())
        )
      case close: netty.handler.codec.http.websocketx.CloseWebSocketFrame =>
        println("WebSocket close frame received")
        ctx.close()
      case _ =>
        println(s"Unsupported frame type: ${frame.getClass.getName}")

  override def channelInactive(
      ctx: netty.channel.ChannelHandlerContext
  ): Unit =
    println(s"WebSocket connection closed: ${ctx.channel().remoteAddress()}")
    super.channelInactive(ctx)

class HttpHandler extends netty.channel.SimpleChannelInboundHandler[netty.handler.codec.http.HttpRequest]:
  override def channelRead0(
      ctx: netty.channel.ChannelHandlerContext,
      req: netty.handler.codec.http.HttpRequest
  ): Unit =
    // Check if this is a WebSocket upgrade request
    val upgradeHeader = req.headers().get(netty.handler.codec.http.HttpHeaderNames.UPGRADE)
    if (upgradeHeader != null && upgradeHeader.equalsIgnoreCase("websocket")) {
      // Perform WebSocket handshake
      val wsFactory = new netty.handler.codec.http.websocketx.WebSocketServerHandshakerFactory(
        s"ws://${req.headers().get(netty.handler.codec.http.HttpHeaderNames.HOST)}${req.uri()}",
        null, // subprotocols
        true  // allowExtensions
      )
      val handshaker = wsFactory.newHandshaker(req)
      if (handshaker == null) {
        netty.handler.codec.http.websocketx.WebSocketServerHandshakerFactory.sendUnsupportedVersionResponse(ctx.channel())
      } else {
        handshaker.handshake(ctx.channel(), req).addListener((future: netty.channel.ChannelFuture) => {
          if (future.isSuccess) {
            // Replace HTTP handler with WebSocket handler
            ctx.pipeline().replace(this, "websocketHandler", new WebSocketHandler())
            println(s"WebSocket connection established from ${ctx.channel().remoteAddress()}")
          }
        })
      }
    } else {
      // req/

      println(">>>" + req)

      val decoder = ctx.pipeline().get(classOf[netty.handler.codec.http.HttpRequestDecoder])
      // Conditionally add aggregator if not present and request is not full
      if (!req.isInstanceOf[netty.handler.codec.http.FullHttpRequest]) {
        // Add HttpObjectAggregator to pipeline for this and future requests
        if (ctx.pipeline().get(classOf[netty.handler.codec.http.HttpObjectAggregator]) == null) {
          ctx.pipeline().addAfter(
          ctx.name(),
            "aggregator",
            new netty.handler.codec.http.HttpObjectAggregator(65536)
          )
          // Re-fire the current message through the modified pipeline
          // ctx.fireChannelRead(req.asInstanceOf[netty.handler.codec.http.HttpObject].retain())
          return
        }
      }
      if (!req.isInstanceOf[netty.handler.codec.http.FullHttpRequest]) {
        println("Warning: Request is not fully aggregated. Consider adding HttpObjectAggregator to pipeline.")
      }


      if (req.isInstanceOf[netty.handler.codec.http.FullHttpRequest]) {
        val fullReq = req.asInstanceOf[netty.handler.codec.http.FullHttpRequest]
        val body = fullReq.content().toString(java.nio.charset.StandardCharsets.UTF_8)
        println(s"Request body: $body")
      } else {
        println("Request body not fully aggregated")
      }

      // Handle multipart form data if present
      val contentType = req.headers().get(netty.handler.codec.http.HttpHeaderNames.CONTENT_TYPE)
      if (contentType != null && contentType.startsWith("multipart/form-data")) {
        if (req.isInstanceOf[netty.handler.codec.http.FullHttpRequest]) {
          val fullReq = req.asInstanceOf[netty.handler.codec.http.FullHttpRequest]
          try {
            val decoder = new netty.handler.codec.http.multipart.HttpPostRequestDecoder(
              new netty.handler.codec.http.multipart.DefaultHttpDataFactory(false),
              fullReq
            )

            val it = decoder.getBodyHttpDatas().iterator()
            while (it.hasNext()) {
              val data = it.next()
              data match {
                case attr: netty.handler.codec.http.multipart.Attribute =>
                  println(s"Form field: ${attr.getName()} = ${attr.getValue()}")
                case fileUpload: netty.handler.codec.http.multipart.FileUpload =>
                  println(s"File upload: ${fileUpload.getName()}, filename: ${fileUpload.getFilename()}, size: ${fileUpload.length()}")
                  // Access file content with: fileUpload.getByteBuf() or fileUpload.get()
                case _ =>
                  println(s"Unknown data type: ${data.getClass.getName}")
              }
            }
            decoder.destroy()
          } catch {
            case e: Exception =>
              println(s"Error decoding multipart data: ${e.getMessage}")
          }
        }
      }




      // Normal HTTP request
      val content = netty.buffer.Unpooled.copiedBuffer(
        s"Hello from Netty!\nMethod: ${req.method()}\nURI: ${req.uri()}\n",
        java.nio.charset.StandardCharsets.UTF_8
      )

      val response = netty.handler.codec.http.DefaultFullHttpResponse(
        netty.handler.codec.http.HttpVersion.HTTP_1_1,
        netty.handler.codec.http.HttpResponseStatus.OK,
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
      // sys.error("disabled")

      ctx.writeAndFlush(response)
    }

  override def exceptionCaught(
      ctx: netty.channel.ChannelHandlerContext,
      cause: Throwable
  ): Unit =
    println("Exception caught:")
    cause.printStackTrace()
    ctx.close()

object NettyTest:

  def run(): Unit =
    val bossGroup = netty.channel.nio.NioEventLoopGroup()
    val workerGroup = netty.channel.nio.NioEventLoopGroup(
      0,
      java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()
    )
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
              p.addLast(new netty.handler.codec.http.HttpRequestDecoder())
              p.addLast(new netty.handler.codec.http.HttpResponseEncoder())
              p.addLast(new CustomHttpHandler())


              // p.addLast(new netty.handler.codec.string.StringDecoder())
              // p.addLast(new netty.handler.codec.string.StringEncoder())
              // p.addLast(new netty.channel.SimpleChannelInboundHandler[String] {
              //   override def channelRead0(
              //       ctx: netty.channel.ChannelHandlerContext,
              //       msg: String
              //   ): Unit =
              //     println(s"Received: $msg")
              //     ctx.writeAndFlush(s"ECHO: $msg\n")

              //   override def channelInactive(
              //       ctx: netty.channel.ChannelHandlerContext
              //   ): Unit =
              //     println(
              //       s"Connection closed: ${ctx.channel().remoteAddress()}"
              //     )
              //     super.channelInactive(ctx)
              // })
          }
        )
        .option(netty.channel.ChannelOption.SO_BACKLOG, 128)
        .childOption(netty.channel.ChannelOption.SO_KEEPALIVE, true)

      val channelFuture = b.bind(8080).sync()
      println("Server started on port 8080")
      channelFuture.channel().closeFuture().sync()
    finally
      bossGroup.shutdownGracefully()
      workerGroup.shutdownGracefully()
