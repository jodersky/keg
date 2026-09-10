// // import keg.forms.{Form, FormData}

// // case class ChangePasswordRequest(
// //     oldPassword: String,
// //     newPassword: Int,
// //     confirmNewPassword: String
// // ) derives keg.forms.FromForm

// // @keg.post("/change-password")
// // def changePassword(form: keg.forms.Form[ChangePasswordRequest]): keg.Response =
// //   println(form)
// //   keg.Response(
// //     data = s"Received form: $form",
// //     status = 200
// //   )

// // @keg.get("/")
// // def index() =
// //   val html =
// //     s"""|<!DOCTYPE html>
// //         |<html>
// //         |<head>
// //         |  <title>Keg Example</title>
// //         |</head>
// //         |<body>
// //         |  <h1>Welcome to the Keg Example Server!</h1>
// //         |  <p>Try accessing <a href="/hello?name=World">/hello?name=World</a></p>
// //         |  <form action="/submit" method="post" enctype="multipart/form-data">
// //         |    <label for="name">Name:</label>
// //         |    <input type="text" id="name" name="name"><br><br>
// //         |    <label for="value">Value:</label>
// //         |    <input type="text" id="value" name="email"><br><br>
// //         |    <label for="file">File:</label>
// //         |    <input type="file" id="file" name="file"><br><br>
// //         |    <input type="checkbox" id="subscribe" name="subscribe">
// //         |    <input type="submit" value="Submit">
// //         |
// //         |  </form>
// //         |
// //         |  <form action="/change-password" method="post">
// //         |    <label for="username">Username:</label>
// //         |    <input type="text" id="username" name="username"><br><br>
// //         |    <label for="oldPassword">Old Password:</label>
// //         |    <input type="password" id="oldPassword" name="oldPassword"><br><br>
// //         |    <label for="newPassword">New Password:</label>
// //         |    <input type="password" id="newPassword" name="newPassword"><br><br>
// //         |    <label for="confirmNewPassword">Confirm New Password:</label>
// //         |    <input type="password" id="confirmNewPassword" name="confirmNewPassword"><br><br>
// //         |    <input type="submit" value="Change Password">
// //         |    <input type="submit" value="submit">
// //         |  </form>
// //         |
// //         |</body>
// //         |</html>
// //         |""".stripMargin

// //   keg.Response(
// //     data = html,
// //     headers = Seq("content-type" -> "text/html; charset=UTF-8")
// //   )

// // @keg.post("/submit")
// // def submitForm(req: keg.Request, data: keg.forms.FormData): keg.Response =
// //   // val allData = req.body.readAllBytes()
// //   // val bodyStr = new String(allData, "UTF-8")
// //   // println(bodyStr)
// //   keg.Response(
// //     data = data.toString,
// //     status = 200
// //   )

// //   //

// // @keg.get("/index2")
// // def index2() =
// //   import scalatags.Text.all.*

// //   val result = html(
// //     head(
// //     ),
// //     body(
// //       h1("Welcome to the Keg Example Server!"),
// //       button("Click Me"),
// //       p("Try accessing ", a(href := "/hello?name=World")("/hello?name=World"))
// //     )
// //   )

// //   keg.Response(
// //     data = doctype("html")(result),
// //     // headers = Seq("content-type" -> "text/html; charset=UTF-8")
// //   )

// // @keg.notFound()
// // def notFoundHandler(request: keg.model.Request) =
// //   keg.Response(
// //     status = 404,
// //     data = s"404 Not Found: ${request.path}",
// //     headers = Seq("content-type" -> "text/plain; charset=UTF-8")
// //   )

// // val icept = new keg.main.TimingInterceptor()

// // @keg.get("/metrics")
// // def metrics() =
// //   keg.Response(
// //     ustats.Registry.global,
// //     200
// //   )

// // val routes = keg.routes()


// // object CustomInterceptor extends keg.main.Interceptor:

// //   override def intercept(
// //     request: keg.model.Request,
// //     endpoint: keg.model.Endpoint,
// //     next: keg.model.Request => (keg.model.Response | keg.model.WsResponse)
// //   ): keg.model.Response | keg.model.WsResponse =
// //     println(s"Intercepting request to ${request.path}")
// //     val response = next(request)
// //     response match
// //       case resp: keg.model.Response =>
// //         println(s"Response status: ${resp.status}")
// //         resp
// //       case wsResp: keg.model.WsResponse =>
// //         println(s"WebSocket response")
// //         wsResp


// // @main def runExampleServer(): Unit =
// //   println("Starting example server on http://localhost:8080")
// //   val server = keg.main.Server(routes.endpoints, debugMode = true, interceptors = Seq(icept))
// //   server.start()

// //   // while true do
// //   //   Thread.sleep(2000)
// //   //   keg.main.Reloader.reload()


// //   // server.start()


// // case class Foo(
// //     namea: String,
// //     age: Int
// // ) derives keg.forms.FromForm2

// // @main()
// // def testFoo(): Unit =
// //   summon[keg.forms.FromForm2[Foo]]




// // @keg.get("/echo/:n")
// // def foo(n: Int, x: String) =
// //   // println(s"Received n: $n, x: $x")
// //   s"Echo: n=$n, x=$x"

// // @keg.get("/")
// // def index() = "hello, world"

// // val plain = keg.Endpoint(
// //   selector = keg.EndpointSelector.Simple("/foo", Seq("GET")),
// //   invoke = (req: keg.model.Request) => keg.EndpointResult.Success(
// //     keg.Response(
// //       "ok"
// //     )
// //   )
// // )

// // val routes: keg.model.Routes = keg.routes()

// // @main()
// // def main() =
// //   // val key = java.nio.file.Files.readString(java.nio.file.Paths.get("key.pem"))
// //   // val cert = java.nio.file.Files.readString(java.nio.file.Paths.get("cert.pem"))

// //   val server = keg.Server(
// //     routes.endpoints ++ Seq(plain),
// //     // debugMode = true,
// //     interceptors = Seq(
// //       // keg.main.RateLimiter(),
// //       keg.extras.TimingInterceptor()
// //     ),
// //     listeners = Seq(
// //       keg.Listener.Http("::1", 8000),
// //       keg.Listener.Http("::1", 8081),
// //       // keg.Listener.Https("::1", 8443, key, cert)
// //     ),
// //     // transportBuilder = new keg.transport.uhttp.UhttpTransportBuilder
// //   )


// //   server.start()

// object foo:
//   case class Res(data: Conversion.into[Data])

//   case class Data()
//   object Data:
//     given stringAsData: Conversion[String, Data] with
//       def apply(a: String): Data = Data()

//     given asGenyAsData[A](using c: A => geny.Writable): Conversion[A, Data] with
//       def apply(a: A): Data = stringAsData(c(a).toString)


//   def direct: Res = Res(Data()) // obvious
//   def converted: Res = Res("hello") // uses stringAsData
//   def viaGenyDirect: Res = Res(ujson.Obj("hello" -> "world"): geny.Writable) // uses asGenyAsData
//   def viaGeny: Res = Res(ujson.Obj("hello" -> "world"))

//   case class CustomObject(value: String)

//   // here is the asymmetry: if we have a ToResponse[CustomObject], we can return
//   // CustomObject directly, but *can't* return a Res(CustomObject), since it is not Data

//   def custom: CustomObject = CustomObject("test")
//   // def fail: Res = Res(CustomObject("test")) // error: no Conversion[CustomObject, Data]

//   // def foo: Res =
//   // def foo: Res | String =
//   // def foo: String =

// object foo2:
//   case class Res[A](data: Conversion.into[A])
//   object Res:
//     type Raw = Res[Data]

//   case class Data()
//   object Data:
//     given stringAsData: Conversion[String, Data] with
//       def apply(a: String): Data = Data()

//     given asGenyAsData[A](using c: A => geny.Writable): Conversion[A, Data] with
//       def apply(a: A): Data = stringAsData(c(a).toString)


//   def direct: Res.Raw = Res(Data()) // obvious
//   def converted: Res.Raw = Res("hello") // uses stringAsData
//   def viaGenyDirect: Res.Raw = Res(ujson.Obj("hello" -> "world"): geny.Writable) // uses asGenyAsData
//   def viaGeny: Res.Raw = Res(ujson.Obj("hello" -> "world"))

//   case class CustomObject(value: String)

//   // here is the asymmetry: if we have a ToResponse[CustomObject], we can return
//   // CustomObject directly, but *can't* return a Res(CustomObject), since it is not Data

//   def custom: CustomObject = CustomObject("test")
//   def fail: Res[CustomObject] = Res(CustomObject("test")) // error: no Conversion[CustomObject, Data]

//   // to final essentially acts as a conversion to Res.Raw, but can live outside of
//   // the Res hierarchy
//   trait ToFinal[A]:
//     def convert(a: A): Res.Raw

//   object ToFinal:
//     given [A](using f: Conversion[A, Data]): ToFinal[A] with
//       def convert(a: A): Res.Raw = Res(f(a))

//     given [A](using f: ToFinal[A]): ToFinal[Res[A]] with
//       def convert(a: Res[A]): Res.Raw =
//         Res(f.convert(a.data).data)

//     given [A](using f: ToFinal[Res[A]], g: ToFinal[A]): ToFinal[Res[A] | A] with
//       def convert(a: Res[A] | A): Res.Raw =
//         a match
//           case r: Res[A] =>
//             println(s"Converting Res[A]: $r")
//             f.convert(r)
//           case v: A =>
//             println(s"Converting A: $v")
//             g.convert(v)

//     given customObjectToFinal: ToFinal[CustomObject] with
//       // def apply(a: CustomObject): Final = Final(a.value)
//       def convert(a: CustomObject): Res[Data] = Res(a.value)

//   summon[ToFinal[CustomObject]]
//   summon[ToFinal[String]]
//   summon[ToFinal[geny.Writable]]
//   summon[ToFinal[ujson.Value]]

//   summon[ToFinal[Res[CustomObject]]]
//   summon[ToFinal[Res[String]]]
//   summon[ToFinal[Res[geny.Writable]]]
//   summon[ToFinal[Res[ujson.Value]]]

//   def yeah: String | Res[String] = if false then "" else Res("")

//   // this still fails, but is probably acceptable
//   // def meh: Res[String] = if false then "" else Res("")

//   // def yeah2: geny.Writable| String | Res[String] = if false then "" else Res("")

//   summon[ToFinal[Res[String] | String]]
//   summon[ToFinal[String | Res[String]]]

//   @main
//   def main() =
//     val r1 = summon[ToFinal[Res[String] | String]].convert("hello")
//     val r2 = summon[ToFinal[Res[String] | String]].convert(Res("hello"))
//     println(r1)
//     println(r2)


@main
def main() =
  val res = keg.Response("hello, world")
  summon[Conversion[String, keg.Data]] // ok

  import keg.Data.stringAsData
  summon[Conversion[keg.Response[String], keg.Response[keg.Data]]] // ok

