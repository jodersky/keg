class TestVisitor() extends keg.forms.FormVisitor[collection.Seq[(String, String)]]:
  private val parts = scala.collection.mutable.ListBuffer.empty[(String, String)]
  private val _files = scala.collection.mutable.ListBuffer.empty[(String, String, String)]
  private var currentName: String = null
  private var currentFilename: String = null
  private var currentOut: java.io.ByteArrayOutputStream = null

  /** All file parts as (fieldName, filename, content). */
  def files: collection.Seq[(String, String, String)] = _files

  override def visitField(name: String, value: String): Unit = parts += name -> value

  override def visitFileBegin(name: String, filename: String): java.io.OutputStream =
    currentName = name
    currentFilename = filename
    currentOut = new java.io.ByteArrayOutputStream()
    currentOut

  override def visitFileEnd(): Unit =
    _files += ((currentName, currentFilename, currentOut.toString("UTF-8")))
    parts += currentName -> currentOut.toString("UTF-8")
    currentName = null
    currentFilename = null
    currentOut = null

  override def visitEnd(): collection.Seq[(String, String)] = parts
