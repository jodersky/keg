package keg.forms

case class PwChange(
  currentPassword: String,
  newPassword: String,
  confirmNewPassword: String
):
  validate(
    newPassword.length >= 8,
    "New password must be at least 8 characters long"
  )
  validate(
    newPassword == confirmNewPassword,
    "New password and confirmation do not match"
  )

case class TempFile(
  path: java.nio.file.Path,
  filename: String
):
  private var saved = java.util.concurrent.atomic.AtomicBoolean(false)

  private val cleanable = TempFile.cleaner.register(
    this,
    () =>
      if !saved.get() then
        try java.nio.file.Files.deleteIfExists(path)
        catch case _: Throwable => ()
  )

  /** Prevent the temporary file from being deleted on GC. Note that the caller
    * is responsible for deleting the file if save() is called. */
  def save(): Unit =
    saved.set(true)
    cleanable.clean()

object TempFile:
  private val cleaner = java.lang.ref.Cleaner.create()

class form(
  keepUnknownFields: Boolean = false,
) extends annotation.StaticAnnotation

class field(
  name: String = null,
  maxSize: Long = 2000, // used for parsing, hard 413 failure if exceeded
) extends annotation.StaticAnnotation

inline def validate(inline check: => Boolean, errMsg: String): Unit = ()

sealed trait FieldReader[A]

trait SimpleFieldReader[A] extends FieldReader[A]:
  def read(value: String): Either[String, A]

object FieldReader:

  given stringFieldReader: SimpleFieldReader[String] = (value: String) => Right(value)
  given byteFieldReader: SimpleFieldReader[Byte] = (value: String) =>
    try Right(value.toByte) catch case _: NumberFormatException => Left("not a valid byte")
  given shortFieldReader: SimpleFieldReader[Short] = (value: String) =>
    try Right(value.toShort) catch case _: NumberFormatException => Left("not a valid short")
  given intFieldReader: SimpleFieldReader[Int] = (value: String) =>
    try Right(value.toInt) catch case _: NumberFormatException => Left("not a valid integer")
  given longFieldReader: SimpleFieldReader[Long] = (value: String) =>
    try Right(value.toLong) catch case _: NumberFormatException => Left("not a valid long")
  given floatFieldReader: SimpleFieldReader[Float] = (value: String) =>
    try Right(value.toFloat) catch case _: NumberFormatException => Left("not a valid float")
  given doubleFieldReader: SimpleFieldReader[Double] = (value: String) =>
    try Right(value.toDouble) catch case _: NumberFormatException => Left("not a valid double")
  given charFieldReader: SimpleFieldReader[Char] = (value: String) =>
    if value.length == 1 then Right(value.charAt(0))
    else Left("not a valid char")
  given booleanFieldReader: SimpleFieldReader[Boolean] = (value: String) =>
    value.toLowerCase match
      case "true" | "on" | "yes" | "1"  => Right(true)
      case "false" | "off" | "no" | "0" => Right(false)
      case _                            => Left("not a valid boolean")
  given optionalFieldReader[A](using reader: SimpleFieldReader[A]): SimpleFieldReader[Option[A]] =
    (value: String) =>
      if value.isEmpty then Right(None)
      else
        reader.read(value) match
          case Right(v)  => Right(Some(v))
          case Left(err) => Left(err)
  given uuidFieldReader: SimpleFieldReader[java.util.UUID] = (value: String) =>
    try Right(java.util.UUID.fromString(value))
    catch case _: IllegalArgumentException => Left("not a valid UUID")

/** Typeclass for parsing a form into an instance of type A using a FormVisitor2 */
trait FromForm2[A]:
  def visitor(): FormVisitor2[FormResult[A]]

object FromForm2:

  inline def derived[A]: FromForm2[A] = ${derivedImpl[A]}

  import scala.quoted.Expr
  import scala.quoted.Type
  import scala.quoted.Quotes

  def derivedImpl[A: Type](using q: Quotes): Expr[FromForm2[A]] =
    import collection.mutable as m
    import q.reflect.*

    val tpe = TypeRepr.of[A]

    if !tpe.typeSymbol.isClassDef || !tpe.typeSymbol.flags.is(Flags.Case) then
      report.errorAndAbort(s"Can only derive FromForm2 for case classes, got: $tpe")

    val applyMethod = tpe.typeSymbol.companionModule.methodMember("apply").headOption.getOrElse(
      report.errorAndAbort(s"Could not find apply method for case class: $tpe")
    )

    if applyMethod.paramSymss.flatten.size > 64 then
      report.errorAndAbort(s"Cannot derive FromForm2 for case class with more than 64 fields: $tpe")

    // List[List[Expr[FormFieldReader[...]]]]
    val readers: List[Term] =
      for
        params <- applyMethod.paramSymss
        param <- params
      yield
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


    def fieldSyms(cls: Symbol) =
      for
        params <- applyMethod.paramSymss
        param <- params
      yield
        Symbol.newVal(
          parent = cls,
          name = param.name + "$field",
          tpe = param.termRef.widenTermRefByName,
          flags = Flags.Mutable | Flags.Private,
          privateWithin = Symbol.noSymbol
        )

    val parents = List(TypeTree.of[Object], TypeTree.of[FormVisitor2[FormResult[A]]])

    val classSym = Symbol.newClass(
      Symbol.spliceOwner,
      "DerivedFromForm2",
      parents = parents.map(_.tpe),
      decls = cls =>
        fieldSyms(cls) :::
        List(
          Symbol.newVal(
            cls,
            "fieldIndex",
            TypeRepr.of[Int],
            Flags.Mutable | Flags.Private,
            privateWithin = Symbol.noSymbol
          ),
          Symbol.newVal(
            cls,
            "unsetFields",
            TypeRepr.of[Long],
            Flags.Mutable | Flags.Private,
            privateWithin = Symbol.noSymbol
          ),
          Symbol.newMethod(
            cls,
            "visitFieldName",
            MethodType(List("name", "filename"))(
              _ => List(TypeRepr.of[String], TypeRepr.of[String]),
              _ => TypeRepr.of[Long]
            )
          ),
          Symbol.newMethod(
            cls,
            "visitFieldValue",
            MethodType(List("value"))(
              _ => List(TypeRepr.of[String]),
              _ => TypeRepr.of[Unit]
            )
          ),
          Symbol.newMethod(
            cls,
            "visitEnd",
            MethodType(Nil)(_ => Nil, _ => TypeRepr.of[FormResult[A]]),
          )
        )
      ,
      selfType = None
    )

    val fieldDefs =
      for
        paramss <- applyMethod.paramSymss
        param <- paramss
      yield
        val fieldSym = classSym.declaredField(param.name + "$field")
        val rhs = param.termRef.widenTermRefByName match
          case t if t =:= TypeRepr.of[Byte] => Literal(ByteConstant(0))
          case t if t =:= TypeRepr.of[Short] => Literal(ShortConstant(0))
          case t if t =:= TypeRepr.of[Int] => Literal(IntConstant(0))
          case t if t =:= TypeRepr.of[Long] => Literal(LongConstant(0L))
          case t if t =:= TypeRepr.of[Char] => Literal(CharConstant('\u0000'))
          case t if t =:= TypeRepr.of[Float] => Literal(FloatConstant(0.0f))
          case t if t =:= TypeRepr.of[Double] => Literal(DoubleConstant(0.0))
          case t if t =:= TypeRepr.of[Boolean] => Literal(BooleanConstant(false))
          // case t if t =:= TypeRepr.of[String] => Literal(StringConstant(""))
          case _ => Literal(NullConstant())
        ValDef(
          fieldSym,
          Some(rhs)
        )

    val fieldIndexDef = ValDef(
      classSym.declaredField("fieldIndex"),
      Some(Literal(IntConstant(0)))
    )

    val unsetFieldsDef = ValDef(
      classSym.declaredField("unsetFields"),
      Some(Literal(LongConstant(
        (1L << applyMethod.paramSymss.flatten.size) - 1
      )))
    )

    val visitFieldNameDef = DefDef(
      classSym.declaredMethod("visitFieldName").head,
      paramss =>
        val cases = for
          (param, index) <- applyMethod.paramSymss.flatten.zipWithIndex
        yield
          CaseDef(
            Literal(StringConstant(param.name)),
            None,
            Block(
              List(
                Assign(
                  Ref(classSym.declaredField("fieldIndex")),
                  Literal(IntConstant(index))
                )
              ),
              '{1000}.asTerm // TODO: use summoned reader's maxFieldSize
            )
          )

        val defaultCase = CaseDef(
          Wildcard(),
          None,
          '{
            throw new IllegalArgumentException("Unknown form field")
          }.asTerm
        )

        Some(
          Match(
            paramss.head.head.asInstanceOf[Term],
            cases :+ defaultCase
          )
        )
    )

    val visitFieldValueDef = DefDef(
      classSym.declaredMethod("visitFieldValue").head,
      paramss =>
        val cases = for
          (param, index) <- applyMethod.paramSymss.flatten.zipWithIndex
          readerTerm = readers(index)
          fieldSym = classSym.declaredField(param.name + "$field")
        yield
          val unset = Ref(classSym.declaredField("unsetFields"))

          param.termRef.widenTermRefByName.asType match
            case '[t] =>
              CaseDef(
                Literal(IntConstant(index)),
                None,
                '{
                  val reader = ${Ref(readerTerm.symbol).asExprOf[FormFieldReader[t]]}
                  reader.read(${Ref(paramss.head.head.symbol).asExprOf[String]}) match
                    case FormResult.Success(value: t, _) =>
                      ${Assign(
                        Ref(fieldSym),
                        '{value}.asTerm
                      ).asExpr}
                      ${
                        Assign(
                          unset,
                          '{
                            ${unset.asExprOf[Long]} & ~(1L << ${Literal(LongConstant(index)).asExprOf[Long]})
                          }.asTerm
                        ).asExpr
                      }
                }.asTerm
              )

        val defaultCase = CaseDef(
          Wildcard(),
          None,
          '{
            throw new IllegalStateException("Unexpected field index")
          }.asTerm
        )
        Some(
          Match(
            Ref(classSym.declaredField("fieldIndex")),
            cases :+ defaultCase
          )
        )
    )

    val visitEndDef = DefDef(
      classSym.declaredMethod("visitEnd").head,
      paramss =>
        Some(
          '{
            if ${Ref(classSym.declaredField("unsetFields")).asExprOf[Long]} == 0L then
              FormResult.Success(
                ${
                  val argss =
                    for paramss <- applyMethod.paramSymss yield
                      for param <- paramss yield
                        val fieldSym = classSym.declaredField(param.name + "$field")
                        Ref(fieldSym): Term

                  val base = Apply(Ref(applyMethod), argss.head)
                  val application: Apply = argss.tail.foldLeft(base)((lhs, args) => Apply(lhs, args))
                  application.asExprOf[A]
                },
                Map.empty
              )
            else
              ???
          }.asTerm
        )
    )

    val classDef = ClassDef(
      classSym,
      parents,
      fieldDefs :::
        List(
          fieldIndexDef,
          unsetFieldsDef,
          visitFieldNameDef,
          visitFieldValueDef,
          visitEndDef
        )
    )

    val newCls = Typed(Apply(Select(New(TypeIdent(classSym)), classSym.primaryConstructor), Nil), TypeTree.of[FormVisitor2[FormResult[A]]])

    // val instance = Block(
    //   List(classDef),
    //   newCls
    // ).asExprOf[FormVisitor2[FormResult[A]]]

    Block(
      List(classDef),
      '{
        new FromForm2[A]:
          def visitor(): FormVisitor2[FormResult[A]] = ${newCls.asExprOf[FormVisitor2[FormResult[A]]]}
      }.asTerm
    ).asExprOf[FromForm2[A]]





    // '{
    //   new FromForm2[A]:
    //     var field0: Int = 0
    //     var field1: String = null

    //     var fieldIndex: Int = 0
    //     var unsetFields: Long = (1 << 0) | (1 << 1)

    //     def visitFieldName(name: String): Long =
    //       name match
    //         case "field0" =>
    //           fieldIndex = 0
    //           summonedReader.maxFieldSize
    //         case "field1" =>
    //           fieldIndex = 1
    //           summonedReader.maxFieldSize
    //         case other =>
    //           throw new IllegalArgumentException(s"Unknown form field: $other")

    //     def visitFieldValue(value: String): Unit =
    //       fieldIndex match
    //         case 0 =>
    //           summonedReader.read(value)
    //           unsetFields &= ~(1 << 0)
    //         case 1 =>
    //           field2 = summonedReader.read(value)
    //           unsetFields &= ~(1 << 1)
    //         case other =>
    //           throw new IllegalStateException(s"Unexpected field index: $other")

    //     def visitPart(name: String, filename: String, contentType: String): PartVisitor =
    //       throw new UnsupportedOperationException("Multipart forms are not supported")
    //
    //
    //     def visitEnd(): A =
    //       if unsetFields == 0 then
    //         A(field0, field1)
    //       else
    //         val missingFields = for i <- 0 until 2 if (unsetFields & (1 << i)) != 0 yield
    //           i match
    //             case 0 => "field0"
    //             case 1 => "field1"
    //         throw new IllegalArgumentException(s"Missing required form fields: ${missingFields.mk  String(", ")}")

    // }

    // ???
