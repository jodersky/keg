import utest.*

object DerivationTest extends TestSuite:

  val tests = Tests:
    test("empty routes"):
      object app:
        val routes = keg.routes()

      assert(app.routes.endpoints.isEmpty)

    test("simple endpoint derivation"):
      object app:
        @keg.get("/hello/:name")
        def hello(name: String): keg.Response =
          keg.Response("Hello, " + name + "!")

        val routes = keg.routes()

      assert(app.routes.endpoints.length == 1)
      val selector = app.routes.endpoints.head.selector.asInstanceOf[keg.EndpointSelector.Simple]
      assert(selector.path == "/hello/:name")
      assert(selector.methods == Seq("GET"))

      val response = app.routes.endpoints.head.invoke(
        keg.Request(
          method = "GET",
          uri = "/hello/Alice€",
          headers = Map(),
          pathParams = Map("name" -> "Alice€"),
          remainingPathSegments = IndexedSeq(),
          cookies = Map(),
          session = Map(),
          flash = Map(),
          body = java.io.ByteArrayInputStream(Array())
        )
      )
      val bytes = java.io.ByteArrayOutputStream()
      response.asInstanceOf[keg.model.Response].data.write(bytes)
      assert(new String(bytes.toByteArray, "UTF-8") == "Hello, Alice€!")

    test("FromPath not found"):
      val err = utest.assertCompileError("""
        object app:
          class Foo

          @keg.get("/item/:id")
          def getItem(id: Foo): keg.Response =
            keg.Response(s"Item ID: $id")

          val routes = keg.routes()
      """)
      assert(err.msg.contains("No given instance of FromPath[app.Foo] found"))

    test("FromRequest not found"):
      val err = utest.assertCompileError("""
        object app:
          class Foo

          @keg.get("/item/")
          def getItem(id: Foo): keg.Response =
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

      assert(app.routes.endpoints.length == 1)
      assert(app.routes.endpoints.head.selector.isInstanceOf[keg.model.EndpointSelector.WebSocketUpgrade])
