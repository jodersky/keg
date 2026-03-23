package keg.derivation.macros

import scala.quoted.Expr
import scala.quoted.Type
import scala.quoted.Quotes
import scala.annotation.meta.param

inline def routes(): Seq[keg.dispatch.Endpoint] = ${routesImpl()}

def routesImpl()(using q: Quotes) =
  import q.reflect.*

  val container = This(Symbol.spliceOwner.owner.owner)

  val endpoints = for
    m <- container.symbol.declaredMethods
    annot <- m.annotations
    if annot.tpe <:< TypeRepr.of[keg.derivation.EndpointApi#EndpointAnnotation[?]]
  yield
    val prefix = annot.tpe match
      case TypeRef(prefix: TermRef, _) =>
        Ref.term(prefix)
      case _ => report.errorAndAbort("Unexpected annotation type: " + annot.tpe.show)

    annot match
      case Apply(_, path :: remainder) =>
        path match
          case Literal(StringConstant(path)) =>
            makeEndpoint(m, prefix, annot, path)
          case _ =>
            report.errorAndAbort("Path is not a string literal: " + path.show, path.pos)
      case _ =>
        report.errorAndAbort(s"Unexpected annotation structure: ${annot.show}")

  Expr.ofSeq(endpoints)

def makeEndpoint(using q: Quotes)(
  method: q.reflect.Symbol,
  prefix: q.reflect.Term,
  annot: q.reflect.Term,
  path: String
) =
  import q.reflect.*

  val dynamicSegments =
    path.split("/").toSeq.filter(_.startsWith(":")).map(_.drop(1))

  val valSymss = for paramss <- method.paramSymss yield
    for param <- paramss yield
      Symbol.newVal(
        parent = Symbol.spliceOwner,
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

  val paramCheckss = for (paramss, valss) <- method.paramSymss.zip(valSymss) yield
    for (param, v) <- paramss.zip(valss) yield
      val paramType = param.termRef.widenTermRefByName
      paramType.asType match
        case '[t] =>
          if dynamicSegments.contains(param.name) then
            val fromPathType = TypeSelect(
              prefix,
              "FromPath"
            ).tpe.appliedTo(paramType)
            Implicits.search(fromPathType) match
              case success: ImplicitSearchSuccess =>
                (req: Expr[keg.model.Request], errs: Expr[collection.mutable.ListBuffer[String]]) =>
                  '{
                    val str = $req.pathParams(${Expr(param.name)})
                    val fp = ${success.tree.asExprOf[keg.derivation.EndpointApi#FromPath[t]]}
                    fp.fromSegment(str) match
                      case keg.dispatch.Result.Success(value) =>
                        ${Assign(Ref(v), 'value.asTerm).asExpr}
                      case keg.dispatch.Result.Failure(_, msg) =>
                        $errs += s"Failed to parse path parameter ${${Expr(param.name)}}: $msg"
                  }.asTerm

              case failure: ImplicitSearchFailure =>
                report.errorAndAbort(
                  s"No implicit FromPath found for parameter ${param.name} of type ${paramType.show}",
                  param.pos.get
                )
          else
            val fromRequestType = TypeSelect(
              prefix,
              "FromRequest"
            ).tpe.appliedTo(paramType)

            Implicits.search(fromRequestType) match
              case success: ImplicitSearchSuccess =>
                (req: Expr[keg.model.Request], errs: Expr[collection.mutable.ListBuffer[String]]) =>
                '{
                  val fr = ${success.tree.asExprOf[keg.derivation.EndpointApi#FromRequest[t]]}
                  fr.fromRequest($req) match
                    case keg.dispatch.Result.Success(value) =>
                      ${Assign(Ref(v), 'value.asTerm).asExpr}
                    case keg.dispatch.Result.Failure(code, msg) =>
                      $errs += s"Failed to parse request parameter"
                }.asTerm
              case failure: ImplicitSearchFailure =>
                report.errorAndAbort(
                  s"No implicit FromRequest found for parameter ${param.name} of type ${paramType.show}",
                  param.pos.get
                )

  val checkAndCall = (errs: Expr[collection.mutable.ListBuffer[String]]) => '{
    if $errs.isEmpty then
      try
        val result = ${
          val base: Term = Ref(method)
          val argss = for vals <- valSymss yield
            for v <- vals yield Ref(v)
          val application = argss.foldLeft(base)((lhs, args) => Apply(lhs, args))
          application.asExpr
        }
        keg.dispatch.Result.Success(result)
      catch
        case ex: Throwable =>
          keg.dispatch.Result.Exception(ex)
    else
      ${prefix.asExprOf[keg.derivation.EndpointApi]}.handleFailure(
        400, $errs.mkString(", ")
      )
      // keg.dispatch.Result.Failure(400, $errs.mkString(", "))
  }

  '{
    keg.dispatch.Endpoint(
      fqn = ${Expr(method.fullName)},
      path = ${Expr(path)},
      methods = ${annot.asExprOf[keg.derivation.EndpointApi#EndpointAnnotation[?]]}.methods,
      invoke = (req: keg.model.Request) =>
        val errs = collection.mutable.ListBuffer.empty[String]
        ${
          Block(
            valDefss.flatten ++
            paramCheckss.flatten.map(f => f('req, 'errs)),
            checkAndCall('errs).asTerm
          ).asExprOf[keg.dispatch.Result[Any, Nothing]]
        }
    )
  }
