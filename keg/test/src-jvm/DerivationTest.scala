import utest.*

import keg.util.MultiDict

object DerivationTest extends TestSuite:

  val tests = Tests:
    test("empty routes"):
      object app:
        val routes = keg.routes()

      assert(app.routes.isEmpty)

    test("simple endpoint derivation"):
      object app:
        @keg.get("/hello/:name/:suffix/*")
        def hello(name: String, suffix: Int, request: keg.Request): keg.Response[String] =
          keg.Response(s"Hello, ${name}! Suffix is: ${suffix}, remaining path: ${request.remainingPathSegments.mkString("/")}" )

        val routes = keg.routes()

      assert(app.routes.length == 1)
      val selector = app.routes.head.asInstanceOf[keg.Endpoint].selector.asInstanceOf[keg.EndpointSelector.Simple]
      assert(selector.path == "/hello/:name/:suffix/*")
      assert(selector.methods == Seq("GET"))

      val response = app.routes.head.asInstanceOf[keg.Endpoint].invoke(
        keg.Request(
          method = "GET",
          target = "/hello/Alice€/42/some/extra/path",
          headers = MultiDict.empty,
          pathParams = Map("name" -> "Alice€", "suffix" -> "42"),
          remainingPathSegments = IndexedSeq("some", "extra", "path"),
          cookies = Map(),
          session = Map(),
          flash = Map(),
          getBody = () => java.io.ByteArrayInputStream(Array()),
          remoteIp = java.net.InetAddress.getLoopbackAddress(),
          clientCertificate = None
        )
      )
      val bytes = java.io.ByteArrayOutputStream()
      response.asInstanceOf[keg.EndpointResult.Success].response.asInstanceOf[keg.Response[keg.Data]].data.write(bytes)
      assert(new String(bytes.toByteArray, "UTF-8") == "Hello, Alice€! Suffix is: 42, remaining path: some/extra/path")

    test("FromPath not found"):
      val err = utest.assertCompileError("""
        object app:
          class Foo

          @keg.get("/item/:id")
          def getItem(id: Foo): keg.Response[keg.Data] =
            keg.Response(s"Item ID: $id")

          val routes = keg.routes()
      """)
      assert(err.msg.contains("No given instance of FromPath[app.Foo] found"))

    test("FromRequest not found"):
      val err = utest.assertCompileError("""
        object app:
          class Foo

          @keg.get("/item/")
          def getItem(id: Foo): keg.Response[keg.Data] =
            keg.Response(s"Item ID: $id")

          val routes = keg.routes()
      """)
      assert(err.msg.contains("No given instance of FromRequest[app.Foo] found"))

    test("ToResponse not found"):
      val err = utest.assertCompileError("""
        object app:
          class Foo

          @keg.get("/item/:id")
          def getItem(id: String): Foo =
            Foo()

          val routes = keg.routes()
      """)
      assert(err.msg.contains("No given instance of ToResponse[app.Foo] found") )

    test("websocket derivation"):
      object app:
        @keg.websocket("/ws/:user")
        def socket(user: String): keg.WsResponse =
          keg.WsResponse:
            channel =>
              keg.WsActor:
                case _ => ()

        val routes = keg.routes()

      assert(app.routes.length == 1)
      assert(app.routes.head.asInstanceOf[keg.Endpoint].selector.isInstanceOf[keg.model.EndpointSelector.WebSocketUpgrade])

    test("error annotations"):
      object app:
        @keg.notFound()
        def notFoundHandler(): keg.Response[keg.Data] =
          keg.Response("Not Found", 404)

        @keg.methodNotAllowed()
        def methodNotAllowedHandler(): keg.Response[keg.Data] =
          keg.Response("Method Not Allowed", 405)

        val routes = keg.routes()

      assert(app.routes.length == 2)
      val selectors = app.routes.map(_.asInstanceOf[keg.Endpoint].selector)
      assert(selectors.exists {
        case keg.model.EndpointSelector.NotFound => true
        case _ => false
      })
      assert(selectors.exists {
        case keg.model.EndpointSelector.MethodNotAllowed => true
        case _ => false
      })
