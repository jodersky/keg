package keg.forms

class field(
  final val name: String | Null = null, // if empty, use the parameter name
  final val maxSize: Long = 0, // in bytes, 0 means to use the type's default
) extends scala.annotation.StaticAnnotation
