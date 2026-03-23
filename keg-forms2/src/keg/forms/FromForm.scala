package keg.forms

import scala.annotation.meta.param

trait FromForm[A]:
  def visitor(): FormVisitor[FormResult[A]]

object FromForm:

  inline def derived[A]: FromForm[A] = ${ derivedImpl[A] }

  import scala.quoted.Quotes
  import scala.quoted.Expr
  import scala.quoted.Type

  def derivedImpl[A: Type](using q: Quotes): Expr[FromForm[A]] =
    import q.reflect.*

    '{
      new FromForm[A]:
        def visitor(): FormVisitor[FormResult[A]] =
          ${ visitorImpl[A] }
    }


  /** A synthetic visitor which can be passed to a FormParser to parse form data
    * into an instance of A, without materializing any intermediate
    * representation of the form data.
    *
    * The implementation will generate a synthetic visitor which looks similar
    * to the following. You can also look at the generated code with
    * `-Xprint:typer`.
    *
    * ```scala
    * case class Foo(
    *   field1: String,
    *   field2: Int = 5
    * )
    * ```
    *
    * ```scala
    * new FormVisitor[FormResult[Foo]]:
    *   var missingFields: Long = (1L << 0) // bitmask to track which *required* fields are missing
    *   var name: String = "" // name of the current field being read
    *   var index: Int = 0 // index of the current field being read
    *   var raw = collection.mutable.ListBuffer.empty[(String, String)] // to collect raw field values for the FormResult
    *   var fieldErrors = collection.mutable.Map.empty[String, collection.mutable.ListBuffer[String]] // to collect field-specific errors
    *
    *   var _field0: String = null
    *   var _field1: Int = 0
    *
    *   var _reader0 = ???
    *   var _reader1 = ???
    *
    *   def visitFieldName(name: String, filename: String): Long =
    *     this.name = name
    *     name match
    *       case "field1" =>
    *         index = 0
    *         _reader0.maxSize
    *       case "field2" =>
    *         index = 1
    *         _reader1.maxSize
    *       case _ =>
    *         throw new Exception(s"Unknown field: $name")
    *
    *   def visitFieldValue(value: String): Unit =
    *     raw += name -> value
    *     missingFields &= ~(1L << index)
    *     index match
    *       case 0 =>
    *         _reader0.read(value) match
    *           case Right(v) => _field0 = v
    *           case Left(err) =>
    *             val errors = fieldErrors.getOrElseUpdate(name, collection.mutable.ListBuffer.empty[String])
    *             errors += err
    *       case 1 =>
    *         _reader1.read(value) match
    *           case Right(v) => _field1 = v
    *           case Left(err) =>
    *             val errors = fieldErrors.getOrElseUpdate(name, collection.mutable.ListBuffer.empty[String])
    *             errors += err
    *       case _ => throw new Exception("Invalid state: index out of bounds")
    *
    *   def visitPartBegin(): java.io.OutputStream = ??? // not used for non-multipart forms
    *   def visitPartEnd(): Unit = ()
    *
    *   def visitEnd(): FormResult[Foo] =
    *     if missingFields == 0 then
    *       val instance = Foo(_field0, _field1)
    *       FormResult.Success(
    *         value = instance,
    *         fields = raw.toSeq
    *       )
    *     else
    *       FormResult.Failure(
    *         fieldErrors = fieldErrors.toMap,
    *         raw = raw.toSeq
    *       )
    * ```
    *
    */
  def visitorImpl[A: Type](using q: Quotes): Expr[FormVisitor[FormResult[A]]] =
    import collection.mutable as m
    import q.reflect.*

    val tpe = TypeRepr.of[A]

    if !tpe.typeSymbol.isClassDef || !tpe.typeSymbol.flags.is(Flags.Case) then
      report.errorAndAbort("Can only derive FromForm for case classes")

    val applyMethod = tpe.typeSymbol.companionModule.methodMember("apply").headOption.getOrElse(
      report.errorAndAbort(s"Could not find apply method for case class: $tpe")
    )

    // TODO: lift this limitation
    if applyMethod.paramSymss.flatten.exists(_.flags.is(Flags.HasDefault)) then
      report.errorAndAbort("Implementation limitation: default parameter values are not supported in FromForm derivation")

    if applyMethod.paramSymss.flatten.size > 64 then
      report.errorAndAbort("Cannot derive FromForm for case classes with more than 64 fields")

    val (fieldNames: List[String], maxSizes: List[Long]) = (
      for
        params <- applyMethod.paramSymss
        param <- params
      yield
        val annot = param.annotations.find(_.tpe <:< TypeRepr.of[field])
        annot match
          case Some(annot) =>
            annot.asExprOf[field] match
              case '{field($n, $s)} =>
                (n.valueOrAbort, s.valueOrAbort)
              case _ =>
                report.errorAndAbort("Invalid @field annotation")
          case None =>
            (null, 0L)
    ).unzip

    val readers: List[Expr[FieldReader[?]]] = for
      params <- applyMethod.paramSymss
      param <- params
    yield
      val ptpe = param.termRef.widenTermRefByName
      val readerTpe = TypeRepr.of[FieldReader].appliedTo(ptpe)
      Implicits.search(readerTpe) match
        case success: ImplicitSearchSuccess => success.tree.asExprOf[FieldReader[?]]
        case failure: ImplicitSearchFailure =>
          report.errorAndAbort(
            s"Could not find implicit FieldReader[${ptpe.show}] for field '${param.name}' in case class ${tpe.show}"
          )




    '{???}
