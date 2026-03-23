package keg.model

// class HttpMap(val underlying: collection.Map[String, collection.Seq[String]]):
//   def get(key: String): Option[String] =
//     underlying.get(key).flatMap(_.headOption)

//   def apply(key: String): String =
//     get(key).getOrElse(
//       throw new NoSuchElementException(s"Key $key not found in MultiMap")
//     )


opaque type MultiMap = collection.Map[String, collection.Seq[String]]
extension (m: MultiMap)
  def getFirst(key: String): Option[String] =
    m.get(key).flatMap(_.headOption)

  def getLast(key: String): Option[String] =
    m.get(key).flatMap(_.lastOption)

  def underlying: collection.Map[String, collection.Seq[String]] = m
  export underlying.*

object MultiMap:
  def apply(
      underlying: collection.Map[String, collection.Seq[String]]
  ): MultiMap = underlying



object Foo:

  val example: MultiMap = MultiMap(Map(
    "a" -> Seq("1", "2"),
    "b" -> Seq("3")
  ))

  example.getFirst("a") // Some("1")
  for (k, v) <- example do
    println(s"$k -> ${v.mkString(",")}")
