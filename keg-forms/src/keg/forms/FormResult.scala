package keg.forms

/** The result of *validating* a form.
  *
  * Note that errors are only collected at validation time. Errors originating
  * from generic parsing (e.g. exceeding maximum allowed sizes, invalid
  * encoding, etc) will have already resulted in an exception being thrown by
  * the parser and a 413 Request Entity Too Large response being sent to the
  * client.
  */
enum FormResult[+A]:

  /** Success case. The form was successfully validated into an instance of type
    * A.
    *
    * @param value The successfully parsed form data
    * @param fields A map of raw field values (name -> value)
    */
  case Success(value: A, fields: collection.Map[String, String])

  /** Error case. The form had validation errors.
    *
    * @param fieldErrors A map of field-specific error messages (field name -> list of error messages)
    * @param globalErrors A list of global error messages not specific to any field
    * @param fields A map of raw field values (name -> value)
  */
  case Error(
    fieldErrors: collection.Map[String, collection.Seq[String]],
    globalErrors: collection.Seq[String],
    fields: collection.Map[String, String]
  )
