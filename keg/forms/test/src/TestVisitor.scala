class TestVisitor() extends keg.forms.FormVisitor[collection.Seq[(String, String)]]:
  private val parts = scala.collection.mutable.ListBuffer.empty[(String, String)]

  override def visitField(name: String, value: String): Unit = parts += name -> value

  override def visitFileBegin(name: String, filename: String): java.io.OutputStream =
    // This visitor does not support file uploads, so this should never be called.
    throw new UnsupportedOperationException("visitFileBegin not supported in FormDataVisitor")
  override def visitFileEnd(): Unit = ()

  override def visitEnd(): collection.Seq[(String, String)] =
    parts
