package keg.forms

trait FormVisitor[R]:

  /** Called after reading the name and value of a plain field. */
  def visitField(name: String, value: String): Unit

  /** Called after starting to read file part of a multipart form.
    *
    * The parser will NOT close the returned OutputStream, however it will call
    * `visitFileEnd` when the part is complete, regardless of whether an error
    * occurred while writing to the OutputStream.
    */
  def visitFileBegin(name: String, filename: String): java.io.OutputStream
  def visitFileEnd(): Unit

  def visitEnd(): R
