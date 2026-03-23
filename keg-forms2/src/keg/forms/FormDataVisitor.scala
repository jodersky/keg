package keg.forms

/** A simple form visitor, which collects form fields as a sequence of
  * name-value pairs.
  *
  * This visitor puts all field values in memory, including file uploads, so it
  * is not suitable for large file uploads.
  *
  * @param maxFieldSize Maximum size of each individual form field (in bytes)
  * @param maxFields Maximum number of form fields allowed
  *
  * If any of the limits are exceeded an exception is thrown, which will result
  * in a 413 Request Entity Too Large response.
  */
class FormDataVisitor(
  maxFieldSize: Long = 2000,
  maxFields: Int = 50,
) extends FormVisitor[collection.Seq[(String, String)]]:
  require(maxFieldSize > 0, "maxFieldSize must be positive")

  private val parts = scala.collection.mutable.ListBuffer.empty[(String, String)]
  private var fieldCount = 0

  private var name = ""

  def visitFieldName(name: String, filename: String): Long =
    require(fieldCount < maxFields, "Maximum number of form fields exceeded")
    this.name = name
    maxFieldSize

  def visitFieldValue(value: String): Unit =
    fieldCount += 1
    parts += name -> value

  def visitPartBegin(): java.io.OutputStream =
    // maxFieldSize is positive, so this should never be called
    throw new UnsupportedOperationException("visitPartBegin not supported in FormDataVisitor")
  def visitPartEnd(): Unit = ()

  def visitEnd(): collection.Seq[(String, String)] =
    parts
