package keg.forms

trait FieldReader[A]:
  def maxSize: Long
  def read(value: String): Option[A]

object FieldReader:
  given stringFieldReader: FieldReader[String] with
    def maxSize: Long = 2048
    def read(value: String): Option[String] = Some(value)

  given byteFieldReader: FieldReader[Byte] with
    def maxSize: Long = 1
    def read(value: String): Option[Byte] =
      try Some(value.toByte) catch case _: NumberFormatException => None

  given shortFieldReader: FieldReader[Short] with
    def maxSize: Long = 2
    def read(value: String): Option[Short] =
      try Some(value.toShort) catch case _: NumberFormatException => None

  given intFieldReader: FieldReader[Int] with
    def maxSize: Long = 4
    def read(value: String): Option[Int] =
      try Some(value.toInt) catch case _: NumberFormatException => None

  given longFieldReader: FieldReader[Long] with
    def maxSize: Long = 8
    def read(value: String): Option[Long] =
      try Some(value.toLong) catch case _: NumberFormatException => None

  given floatFieldReader: FieldReader[Float] with
    def maxSize: Long = 4
    def read(value: String): Option[Float] =
      try Some(value.toFloat) catch case _: NumberFormatException => None

  given doubleFieldReader: FieldReader[Double] with
    def maxSize: Long = 8
    def read(value: String): Option[Double] =
      try Some(value.toDouble) catch case _: NumberFormatException => None

  given charFieldReader: FieldReader[Char] with
    def maxSize: Long = 1
    def read(value: String): Option[Char] =
      if value.length == 1 then Some(value.charAt(0))
      else None

  given booleanFieldReader: FieldReader[Boolean] with
    def maxSize: Long = 5 // "false".length
    def read(value: String): Option[Boolean] =
      value.toLowerCase match
        case "true" | "on" | "yes" | "1"  => Some(true)
        case "false" | "off" | "no" | "0" => Some(false)
        case _                            => None

  given optionalFieldReader[A](using reader: FieldReader[A]): FieldReader[Option[A]] with
    def maxSize: Long = reader.maxSize
    def read(value: String): Option[Option[A]] =
      if value.isEmpty then Some(None)
      else reader.read(value).map(Some(_))

  given uuidFieldReader: FieldReader[java.util.UUID] with
    def maxSize: Long = 36 // UUID string representation length
    def read(value: String): Option[java.util.UUID] =
      try Some(java.util.UUID.fromString(value))
      catch case _: IllegalArgumentException => None
