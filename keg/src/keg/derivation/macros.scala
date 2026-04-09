package keg.derivation

import scala.quoted.Expr
import scala.quoted.Type
import scala.quoted.Quotes
import keg.model.WebResponse
import keg.model.EndpointResult

class AbortException(val code: Int, val message: String) extends Exception(message)

def abort(code: Int, message: String = ""): Nothing =
  throw new AbortException(code, message)

def routesImpl()(using q: Quotes) =
  import q.reflect.*

  val container = This(Symbol.spliceOwner.owner.owner)

  val eps = for
    m <- container.symbol.declaredMethods
    annot <- m.annotations
    if annot.tpe <:< TypeRepr.of[keg.derivation.BaseApi#EndpointAnnotation]
  yield
    endpointImpl(m, annot)

  val recs = for
    m <- container.symbol.declaredMethods
    annot <- m.annotations
    if annot.tpe <:< TypeRepr.of[keg.derivation.BaseApi#error]
  yield
    recoveryImpl(m, annot)

  '{
    new keg.model.Routes:
      def endpoints: Seq[keg.model.Endpoint | keg.model.Recovery] = ${Expr.ofSeq(eps)} ++ ${Expr.ofSeq(recs)}
      def paths: keg.model.Paths = new keg.model.Paths {}
  }

/** Generate an endpoint for the given method.
  *
  * Assuming the method is defined as:
  *
  * ```scala
  * @app.get("/info/:org/:id")
  * def getUser(org: String, id: Int, user: User): keg.model.Response = ???
  * ```
  *
  * then the generated invoke function will be equivalent to:
  * ```scala
  * (request: keg.model.Request) =>
  *   var org: String = ""
  *   var id: Int = 0
  *   var user: User = null
  *
  *   var continue = true
  *
  *   summon[app.FromRequest[String]].fromPath(request.pathSegment("org")) match
  *     case Result.Continue(value) =>
  *       org = value
  *     case Result.Respond(response) =>
  *       return response
  *   summon[app.FromRequest[Int]].fromPath(request.pathSegment("id")) match
  *     case Result.Continue(value) =>
  *       id = value
  *     case Result.Respond(response) =>
  *       return response
  *   summon[app.FromRequest[User]].fromRequest(request) match
  *     case Result.Continue(value) =>
  *       user = value
  *     case Result.Respond(response) =>
  *       return response
  *
  *   getUser(org, id, user)
  * ```
  */
def endpointImpl(using qctx: Quotes)(
  method: qctx.reflect.Symbol,
  annot: qctx.reflect.Term,
): Expr[keg.model.Endpoint] =
  import qctx.reflect.*

  val prefix: Ref = annot.tpe match
    case TypeRef(prefix: TermRef, _) =>
      Ref.term(prefix)
    case _ => report.errorAndAbort("Unexpected annotation type: " + annot.tpe.show)

  val path: String = annot match
    case Apply(_, Nil) => "" // no path argument (this is sort of a hack to support NotFound, etc)
    case Apply(_, pathArg :: _) =>
      pathArg match
        case Literal(StringConstant(path)) => path
        case _ =>
          report.errorAndAbort("Path is not a string literal: " + pathArg.show, pathArg.pos)
    case _ =>
      report.errorAndAbort(s"Unexpected annotation structure: ${annot.show}")

  val dynamicSegments =
    path.split("/").toSeq.filter(_.startsWith(":")).map(_.drop(1))

  val mt = method.termRef.widenTermRefByName.asInstanceOf[MethodType].resType.widenTermRefByName

  val toResponseTpe = TypeSelect(
    prefix,
    "ToResponse"
  ).tpe.appliedTo(mt)

  val toResponse = Implicits.search(toResponseTpe) match
    case success: ImplicitSearchSuccess =>
      success.tree//.asExprOf[keg.derivation.BaseApi#ToResponse[?]]
    case failure: ImplicitSearchFailure =>
      report.errorAndAbort(
        s"No given instance of ToResponse[${mt.show}] found for method '${method.name}'",
        method.pos.get
      )

  def paramChecks(
    owner: Symbol,
    prefix: Ref,
    ctx: Ref,
    valss: List[List[Symbol]],
    req: Expr[keg.model.Request]
  ) =
    given Quotes = owner.asQuotes

    for (params, vals) <- method.paramSymss.zip(valss) yield
      for (param, v) <- params.zip(vals) yield
        val paramType = v.termRef.widenTermRefByName
        paramType.asType match
          case '[t] =>
            if dynamicSegments.contains(param.name) then
              val fromPathType = TypeSelect(
                prefix,
                "FromPath"
              ).tpe.appliedTo(paramType)
              Implicits.search(fromPathType) match
                case success: ImplicitSearchSuccess =>
                  '{
                    val str = $req.pathParams(${Expr(param.name)})
                    // val fp = ${success.tree.asExprOf[keg.derivation.BaseApi#FromPath[t]]}

                    ${
                      // this is equivalent to `fp.fromPath(str, ctx)`, but we have to jump through some hoops to convince the compiler that the types line up here
                      Apply(
                        Select.unique(success.tree, "fromPath"),
                        List(
                          'str.asTerm,
                          ctx
                        )
                      ).asExprOf[Result[t]]
                    } match
                      case Result.Continue(value) =>
                        ${Assign(Ref(v), 'value.asTerm).asExpr}
                      case Result.Abort(code, message) =>
                        ${Return('{EndpointResult.Failure.ParamAbort(${Expr(param.name)}, code, message)}.asTerm, owner).asExpr}
                      case Result.Respond(response) =>
                        ${Return('{EndpointResult.Success(response)}.asTerm, owner).asExpr}
                  }.asTerm

                case failure: ImplicitSearchFailure =>
                  report.errorAndAbort(
                    s"No given instance of FromPath[${paramType.show}] found for parameter '${param.name}'",
                    param.pos.get
                  )
            else
              val fromRequestType = TypeSelect(
                prefix,
                "FromRequest"
              ).tpe.appliedTo(paramType)

              Implicits.search(fromRequestType) match
                case success: ImplicitSearchSuccess =>
                  '{
                    // val fr = ${success.tree.asExprOf[keg.derivation.BaseApi#FromRequest[t]]}
                    ${
                      // this is equivalent to `fr.fromRequest(req, ctx)`, but we have to jump through some hoops to convince the compiler that the types line up here
                      Apply(
                        Select.unique(success.tree, "fromRequest"),
                        List(
                          req.asTerm,
                          ctx
                        )
                      ).asExprOf[Result[t]]
                    } match
                      case Result.Continue(value) =>
                        ${Assign(Ref(v), 'value.asTerm).asExpr}
                      case Result.Abort(code, message) =>
                        ${Return('{EndpointResult.Failure.ParamAbort(${Expr(param.name)}, code, message)}.asTerm, owner).asExpr}
                      case Result.Respond(response) =>
                        ${Return('{EndpointResult.Success(response)}.asTerm, owner).asExpr}
                  }.asTerm
                case failure: ImplicitSearchFailure =>
                  report.errorAndAbort(
                    s"No given instance of FromRequest[${paramType.show}] found for parameter '${param.name}'",
                    param.pos.get
                  )

  val invokeFunc = Lambda(
    Symbol.spliceOwner,
    MethodType(
      List("request")
    )(
      _ => List(TypeRepr.of[keg.model.Request]),
      _ => TypeRepr.of[keg.model.EndpointResult]
    ), {
      case (sym, List(req)) =>

        val ctxSym = Symbol.newVal(
          parent = sym,
          name = "ctx",
          tpe = TypeSelect(
            prefix,
            "Context"
          ).tpe,
          flags = Flags.EmptyFlags,
          privateWithin = Symbol.noSymbol
        )

        val ctxDef = ValDef(ctxSym, Some('{
          ${prefix.asExprOf[keg.derivation.BaseApi]}.parseContext(${req.asExprOf[keg.model.Request]})
        }.asTerm))

        val valSymss = for paramss <- method.paramSymss yield
          for param <- paramss yield
            Symbol.newVal(
              parent = sym,
              name = param.name + "$value",
              tpe = param.termRef.widenTermRefByName,
              flags = Flags.Mutable,
              privateWithin = Symbol.noSymbol
            )

        val valDefss = for (paramss, valss) <- method.paramSymss.zip(valSymss) yield
          for (param, v) <- paramss.zip(valss) yield
            val rhs = param.termRef.widenTermRefByName match
              case t if t =:= TypeRepr.of[Byte] => Literal(ByteConstant(0))
              case t if t =:= TypeRepr.of[Short] => Literal(ShortConstant(0))
              case t if t =:= TypeRepr.of[Int] => Literal(IntConstant(0))
              case t if t =:= TypeRepr.of[Long] => Literal(LongConstant(0L))
              case t if t =:= TypeRepr.of[Char] => Literal(CharConstant('\u0000'))
              case t if t =:= TypeRepr.of[Boolean] => Literal(BooleanConstant(false))
              case t if t =:= TypeRepr.of[Float] => Literal(FloatConstant(0.0f))
              case t if t =:= TypeRepr.of[Double] => Literal(DoubleConstant(0.0))
              case t => Literal(NullConstant())
            ValDef(v, Some(rhs))

        val base: Term = Ref(method)
        val argss = for vals <- valSymss yield
          for v <- vals yield Ref(v)
        val application = argss.foldLeft(base)((lhs, args) => Apply(lhs, args))

        val conversion = Apply(
          Select.unique(toResponse, "toResponse"),
          List(
            application,
            Ref(ctxSym)
          )
        )

        // val conversion = '{
        //   // TODO: this cast is a bit sad, but I don't see a way to convince the compiler that the types line up here
        //   EndpointResult.Success(
        //     ${toResponse}.asInstanceOf[BaseApi#ToResponse[Any]].toResponse(${application.asExpr}, ${Ref(ctxSym).asExpr}.asInstanceOf[Nothing])
        //   )
        // }.asTerm

        val block = Block(
          List(ctxDef) ++ valDefss.flatten ++ paramChecks(sym, prefix, Ref(ctxSym), valSymss, req.asExprOf[keg.model.Request]).flatten,
          '{
            EndpointResult.Success(${conversion.asExprOf[keg.model.WebResponse]})
          }.asTerm
        )

        val bindSym = Symbol.newBind(sym, "e", Flags.EmptyFlags, TypeRepr.of[Exception])

        val abortExceptionSym = Symbol.newBind(sym, "e", Flags.EmptyFlags, TypeRepr.of[AbortException])

        Try(
          block,
          List(
            CaseDef(
              Bind(
                abortExceptionSym,
                Typed(Wildcard(), TypeTree.of[AbortException])
              ),
              None,
              '{
                EndpointResult.Failure.MethodAbort(${Ref(abortExceptionSym).asExprOf[AbortException]}.code, ${Ref(abortExceptionSym).asExprOf[AbortException]}.message)
              }.asTerm
            ),
            CaseDef(
              Bind(
                bindSym,
                Wildcard()
              ),
              None,
              '{ EndpointResult.Failure.Exception(${Ref(bindSym).asExprOf[Exception]}) }.asTerm
            )
          ),
          None
        )

        // '{
        //   try
        //     ${block.asExprOf[keg.model.EndpointResult]}
        //   catch
        //     case e: Exception =>
        //       EndpointResult.Failure.Exception(e)
        // }.asTerm
      case _ => ???
    }
  ).asExprOf[keg.model.Request => keg.model.EndpointResult]

  val selector = if annot.tpe <:< TypeRepr.of[keg.derivation.BaseApi#websocket] then
    '{
      keg.model.EndpointSelector.WebSocketUpgrade(${Expr(path)})
    }
  else if annot.tpe <:< TypeRepr.of[keg.derivation.BaseApi#request] then
    '{
      keg.model.EndpointSelector.Simple(
        path = ${Expr(path)},
        methods = ${annot.asExprOf[keg.derivation.BaseApi#request]}.methods
      )
    }
  else if annot.tpe <:< TypeRepr.of[keg.derivation.BaseApi#notFound] then
    '{
      keg.model.EndpointSelector.NotFound
    }
  else if annot.tpe <:< TypeRepr.of[keg.derivation.BaseApi#methodNotAllowed] then
    '{
      keg.model.EndpointSelector.MethodNotAllowed
    }
  else
    report.errorAndAbort(s"Unsupported endpoint annotation: ${annot.show}", annot.pos)

  '{
    keg.model.Endpoint(
      selector = $selector,
      invoke = ${invokeFunc},
      meta = keg.model.EndpointMeta(
        file = ${Expr(method.pos.get.sourceFile.getJPath.map(_.getFileName().toString).getOrElse("<unknown>"))},
        line = ${Expr(method.pos.get.startLine + 1)},
        column = ${Expr(method.pos.get.startColumn + 1)},
        function = ${Expr(method.fullName)}
      )
    )
  }

def recoveryImpl(using qctx: Quotes)(
  method: qctx.reflect.Symbol,
  annot: qctx.reflect.Term,
): Expr[keg.model.Recovery] =
  import qctx.reflect.*

  val rt = method.termRef.widenTermRefByName.asInstanceOf[MethodType].resType.widenTermRefByName

  val invokeFunc = Lambda(
    Symbol.spliceOwner,
    MethodType(
      List("req", "ep", "f")
    )(
      _ => List(
        TypeRepr.of[keg.model.Request],
        TypeRepr.of[keg.model.Endpoint],
        TypeRepr.of[keg.model.EndpointResult.Failure]
      ),
      _ => TypeRepr.of[keg.model.Response[keg.model.Data]]
    ), {
      case (sym, List(req, ep, f)) =>
        val argss = for params <- method.paramSymss yield
          for param <- params yield
            val tpe = param.termRef.widenTermRefByName
            if tpe =:= TypeRepr.of[keg.Request] then
              Ref(req.symbol)
            else if tpe =:= TypeRepr.of[keg.model.Endpoint] then
              Ref(ep.symbol)
            else if tpe =:= TypeRepr.of[keg.model.EndpointResult.Failure] then
              Ref(f.symbol)
            else
              report.errorAndAbort(
                s"Invalid parameter type for recovery method: ${tpe.show}. Expected Request, Endpoint, or EndpointResult.Failure.",
                param.pos.get
              )

        val base: Term = Ref(method)
        val application = argss.foldLeft(base)((lhs, args) => Apply(lhs, args))

        Implicits.search(TypeRepr.of[Conversion].appliedTo(List(rt, TypeRepr.of[keg.model.Response[keg.model.Data]]))) match
          case success: ImplicitSearchSuccess =>
            Apply(
              Select.unique(success.tree, "apply"),
              List(application)
            ).asExprOf[keg.model.Response[keg.model.Data]]
          case failure: ImplicitSearchFailure =>
             report.errorAndAbort(
              s"No given instance of Conversion[${rt.show}, Response[Data]] found for recovery method '${method.name}'",
              method.pos.get
            )

    //     if !(rt <:< TypeRepr.of[keg.model.Response[keg.model.Data]]) then
    // report.errorAndAbort(
    //   s"Recovery method must return Response[Data], but found ${rt.show}",
    //   method.pos.get
    // )

        application
      case _ => ???
    }
  )


  '{
      keg.model.Recovery(
        invoke = ${invokeFunc.asExprOf[(keg.model.Request, keg.model.Endpoint, keg.model.EndpointResult.Failure) => keg.model.Response[keg.model.Data]]}
      )
  }
