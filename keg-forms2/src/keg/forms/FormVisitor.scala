package keg.forms

trait FormVisitor[R]:

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
