package keg.forms

trait FromForm[A]:
  def parse(data: FormData): FormResult[A]

object FromForm:

  inline def derived[A]: FromForm[A] = ${derivedImpl[A]}

  import scala.quoted.Expr
  import scala.quoted.Type
  import scala.quoted.Quotes

  def derivedImpl[A: Type](using q: Quotes): Expr[FromForm[A]] =
    import collection.mutable as m
    import q.reflect.*

    val tpe = TypeRepr.of[A]

    if !tpe.typeSymbol.isClassDef || !tpe.typeSymbol.flags.is(Flags.Case) then
      report.errorAndAbort(s"Can only derive FromForm for case classes, got: $tpe")

    val applyMethod = tpe.typeSymbol.companionModule.methodMember("apply").headOption.getOrElse(
      report.errorAndAbort(s"Could not find apply method for case class: $tpe")
    )

    // List[List[Expr[FormFieldReader[...]]]]
    val readers = for params <- applyMethod.paramSymss yield
      for param <- params yield
        val ptpe = param.termRef.widenTermRefByName
        val pname = param.name
        val readerTpe = TypeRepr.of[FormFieldReader].appliedTo(ptpe)

        val readerImplicit = Implicits.search(readerTpe) match
          case success: ImplicitSearchSuccess => success.tree
          case failure: ImplicitSearchFailure =>
            report.errorAndAbort(
              s"Could not find implicit FormFieldReader[${ptpe.show}] for field '$pname' in case class ${tpe.show}"
            )
        readerImplicit

    val parseFunc = Lambda(
      Symbol.spliceOwner,
      MethodType(
        List("data")
      )(
        _ => List(TypeRepr.of[FormData]),
        _ => TypeRepr.of[FormResult[A]]
      ), {
        case (sym, List(data)) =>
          given Quotes = sym.asQuotes

          val fieldErrorSym = Symbol.newVal(
            parent = sym,
            name = "fieldErrors",
            tpe = TypeRepr.of[m.Map[String, m.ListBuffer[String]]],
            flags = Flags.Mutable,
            privateWithin = Symbol.noSymbol
          )
          val fieldErrorDef = ValDef(
            fieldErrorSym,
            Some(
              '{ m.Map[String, m.ListBuffer[String]]() }.asTerm
            )
          )

          val valSymss = for paramss <- applyMethod.paramSymss yield
            for param <- paramss yield
              Symbol.newVal(
                parent = sym,
                name = param.name + "$value",
                tpe = param.termRef.widenTermRefByName,
                flags = Flags.Mutable,
                privateWithin = Symbol.noSymbol
              )
          val valDefss = for (paramss, valss) <- applyMethod.paramSymss.zip(valSymss) yield
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

          val paramsValidations: List[List[Statement]] = for (params, valss, readersRow) <- applyMethod.paramSymss.lazyZip(valSymss).lazyZip(readers) yield
            for (param, v, reader) <- params.lazyZip(valss).lazyZip(readersRow) yield
              val pname = Expr(param.name)

              val ptpe = param.termRef.widenTermRefByName.asType
              ptpe match
                case '[t] =>
                  '{
                    val fieldValueOpt = ${data.asExprOf[FormData]}.parts.find(_.name == $pname).flatMap:
                      case FormPart.Field(_, value) => Some(value)
                      case FormPart.File(_, _) => None // ignore files for now

                    fieldValueOpt match
                      case Some(fieldValue) =>
                        ${reader.asExprOf[FormFieldReader[t]]}.read(fieldValue) match
                          case Right(value) =>
                            ${Assign(Ref(v), 'value.asTerm).asExpr}
                          case Left(errorMsg) =>
                            val fe = ${Ref(fieldErrorSym).asExprOf[m.Map[String, m.ListBuffer[String]]]}
                            val errors = fe.getOrElseUpdate($pname, m.ListBuffer[String]())
                            errors += errorMsg
                      case None =>
                        val fe = ${Ref(fieldErrorSym).asExprOf[m.Map[String, m.ListBuffer[String]]]}
                        val errors = fe.getOrElseUpdate($pname, m.ListBuffer[String]())
                        errors += "Missing form field: " + ${pname}
                  }.asTerm


          val checkAndCall = '{
            val fe = ${Ref(fieldErrorSym).asExprOf[m.Map[String, m.ListBuffer[String]]]}
            if fe.isEmpty then
              val instance = ${
                val base: Term = Ref(applyMethod)
                val argss = for vals <- valSymss yield
                  for v <- vals yield Ref(v)
                val application = argss.foldLeft(base)((lhs, args) => Apply(lhs, args))
                application.asExprOf[A]
              }
              FormResult.Success(
                value = instance,
                fields = Map.empty, // TODO: fill in raw field values
              )
            else
              FormResult.Error(
                fieldErrors = fe,
                globalErrors = Seq.empty,
                fields = Map.empty // TODO: fill in raw field values
              )
          }.asTerm

          Block(
            List(fieldErrorDef) ++ valDefss.flatten ++ paramsValidations.flatten,
            checkAndCall
          )
      }
    ).asExprOf[FormData => FormResult[A]]

    '{
      new FromForm[A]:
        def parse(data: FormData): FormResult[A] = $parseFunc(data)
    }

trait FormFieldReader[A]:
  def read(value: String): Either[String, A]

object FormFieldReader:
  given stringFieldReader: FormFieldReader[String] = (value: String) => Right(value)
  given byteFieldReader: FormFieldReader[Byte] = (value: String) =>
    try Right(value.toByte) catch case _: NumberFormatException => Left("not a valid byte")
  given shortFieldReader: FormFieldReader[Short] = (value: String) =>
    try Right(value.toShort) catch case _: NumberFormatException => Left("not a valid short")
  given intFieldReader: FormFieldReader[Int] = (value: String) =>
    try Right(value.toInt) catch case _: NumberFormatException => Left("not a valid integer")
  given longFieldReader: FormFieldReader[Long] = (value: String) =>
    try Right(value.toLong) catch case _: NumberFormatException => Left("not a valid long")
  given floatFieldReader: FormFieldReader[Float] = (value: String) =>
    try Right(value.toFloat) catch case _: NumberFormatException => Left("not a valid float")
  given doubleFieldReader: FormFieldReader[Double] = (value: String) =>
    try Right(value.toDouble) catch case _: NumberFormatException => Left("not a valid double")
  given charFieldReader: FormFieldReader[Char] = (value: String) =>
    if value.length == 1 then Right(value.charAt(0))
    else Left("not a valid char")
  given booleanFieldReader: FormFieldReader[Boolean] = (value: String) =>
    value.toLowerCase match
      case "true" | "on" | "yes" | "1"  => Right(true)
      case "false" | "off" | "no" | "0" => Right(false)
      case _                            => Left("not a valid boolean")
  given optionalFieldReader[A](using reader: FormFieldReader[A]): FormFieldReader[Option[A]] =
    (value: String) =>
      if value.isEmpty then Right(None)
      else
        reader.read(value) match
          case Right(v)  => Right(Some(v))
          case Left(err) => Left(err)
  given uuidFieldReader: FormFieldReader[java.util.UUID] = (value: String) =>
    try Right(java.util.UUID.fromString(value))
    catch case _: IllegalArgumentException => Left("not a valid UUID")


// @forms.form(render: ???)
// case class LoginForm(
//   username: String,
//   password: String,
// ) derives FromForm

// @app.post("/login")
// def login(form: Form[LoginForm]) =
//   form match
//     case Form.Ok(data) =>
//       // process login
//       ???
//     case Form.Errors(errors) =>
//       // render login page with errors

// def render(value: A, errors: Seq[FormError]): keg.model.Response =
//   ???
