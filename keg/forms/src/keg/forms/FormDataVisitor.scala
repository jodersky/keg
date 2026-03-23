package keg.forms

class FormDataVisitor() extends FormVisitor[FormData]:
  private val fields = scala.collection.mutable.Map.empty[String, String]

  override def visitField(name: String, value: String): Unit =
    fields += name -> value

  override def visitFileBegin(name: String, filename: String): java.io.OutputStream =
    // This visitor does not support file uploads, so this should never be called.
    throw new UnsupportedOperationException("visitFileBegin not supported in FormDataVisitor")
  override def visitFileEnd(): Unit = ()

  override def visitEnd(): FormData =
    FormData(fields.toMap)
