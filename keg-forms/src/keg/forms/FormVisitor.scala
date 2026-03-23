package keg.forms

import java.io.ByteArrayOutputStream

trait FormVisitor2[R]:

  /** Called after reading the name of a field (and other metadata in case of a
    * multipart form), but before reading the field value.
    *
    * This method must return the maximum allowed size for the field value. The
    * parsing infrastructure will enforce that the field value does not exceed
    * this size, whether url-encoded or multipart.
    *
    * If the returned size is positive, then subsequent field value data will be
    * buffered in memory up to that size, and then passed to `visitFieldValue`
    * as a single String. If the returned size is negative, and the form is
    * multipart encoded, then the field value will be provided in chunks via the
    * `OutputStream` returned by `visitPartBegin`.
    */
  def visitFieldName(name: String, filename: String): Long

  /** Called after reading a field value.
    *
    * This method is called after all bytes of the field value have been read,
    * always after `visitFieldName`.
    */
  def visitFieldValue(value: String): Unit

  /** Called after starting to read a multipart form part.
    *
    * The parser will NOT close the returned OutputStream, however it will call
    * `visitPartEnd` when the part is complete, regardless of whether an error
    * occurred while writing to the OutputStream.
    */
  def visitPartBegin(): java.io.OutputStream
  def visitPartEnd(): Unit

  def visitEnd(): R

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
) extends FormVisitor2[collection.Seq[(String, String)]]:
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
