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
      val ep = app.routes.head.asInstanceOf[keg.Endpoint]
      assert(ep.path == "/hello/:name/:suffix/*")
      assert(ep.methods == Seq("GET"))

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
      response.asInstanceOf[keg.Response[keg.Data]].data.write(bytes)
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
        @keg.get("/ws/:user")
        def socket(user: String): keg.WsResponse =
          keg.WsResponse:
            channel =>
              keg.WsActor:
                case _ => ()

        val routes = keg.routes()

      assert(app.routes.length == 1)

    test("error annotations"):
      object app:
        @keg.error()
        def recovery(): keg.Response[keg.Data] =
          keg.Response("fail", 500)

        val routes = keg.routes()

      assert(app.routes.length == 1)
      assert(app.routes.head.isInstanceOf[keg.Recovery])
