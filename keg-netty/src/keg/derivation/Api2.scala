// package keg.derivation

// trait Api2:

//   type Request

//   def unwrapRequest(req: keg.model.Request): Request
//   def wrapResponse[A](res: A): keg.model.Response[A]

//   trait FromRequest[+R]:
//     type Error
//     def fromRequest(request: Request): keg.dispatch.Result[R, Error]


//   trait InputReader


// trait InputReader[Request, Input]:
//   def read(input: Request): keg.dispatch.Result[Map[String, Input], String]
//   type FI[+A] <: FromInput[Request, Input, A]

// trait FromInput[Request, Input, +A]:
//   def fromInput(input: Input): keg.dispatch.Result[A, String]

// trait WebApi2 extends Api2:

//   val secrets: Seq[String] = Seq()

//   case class Request(
//     pathParams: Map[String, String],
//     queryParams: Map[String, Seq[String]],
//     headers: Map[String, Seq[String]],
//     body: Option[Array[Byte]],

//   )

//   object Form extends InputReader[Request, FormData]:



// trait JsonApi2 extends WebApi2:
//   type Request = Map[String, Any]


// @keg.get("/hello/:id", kind = app.Data)
// @keg.get("/hello/:id", kind = app.Form)

// @keg.get("/hello/:id", kind = api.Json)
