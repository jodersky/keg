package keg.util

def makeUrl(
  scheme: String | Null = null,
  host: String | Null = null,
  port: Int | Null = null,
  paths: Iterable[String] = Seq.empty,
  queryParams: Iterable[(String, String)] = Seq.empty
): String =
  val sb = new StringBuilder()
  if scheme != null then
    sb.append(scheme).append("://")
  if host != null then
    sb.append(host)
  if port != null then
    sb.append(':').append(port)
  for path <- paths do
    sb.append('/').append(java.net.URLEncoder.encode(path, "UTF-8").replace("+", "%20"))

  val it = queryParams.iterator
  if queryParams.nonEmpty then
    sb.append('?')
    val (k, v) = it.next()
    sb.append(java.net.URLEncoder.encode(k, "UTF-8")).append('=').append(java.net.URLEncoder.encode(v, "UTF-8"))
    while it.hasNext do
      val (k, v) = it.next()
      sb.append('&').append(java.net.URLEncoder.encode(k, "UTF-8")).append('=').append(java.net.URLEncoder.encode(v, "UTF-8"))

  sb.toString()

def addQueryParams(sb: StringBuilder, queryParams: Iterable[(String, String)]): Unit =
  def encode(s: String) = java.net.URLEncoder.encode(s, "UTF-8")

  val it = queryParams.iterator
  if queryParams.nonEmpty then
    sb.append('?')
    val (k, v) = it.next()
    sb.append(encode(k)).append('=').append(encode(v))
    while it.hasNext do
      val (k, v) = it.next()
      sb.append('&').append(encode(k)).append('=').append(encode(v))

extension (inline sc: StringContext)
  inline def url(inline args: Any*): String = ${UrlInterpolator.impl('sc, 'args)}

object UrlInterpolator:
  import scala.quoted.Quotes
  import scala.quoted.Expr
  import scala.quoted.Varargs

  def impl(sc: Expr[StringContext], args: Expr[Seq[Any]])(using Quotes): Expr[String] =
    import quoted.quotes.reflect.*

    val partExprs = sc.value match
      case Some(s) => s.parts.iterator
      case None => report.errorAndAbort("String context must be known at compile time")

    val argExprs = args match
      case Varargs(argExprs) =>
        argExprs.iterator
      case _ => report.errorAndAbort("Expected varargs for arguments")

    val bufferSym = Symbol.newVal(
      parent = Symbol.spliceOwner,
      name = "buffer",
      tpe = TypeRepr.of[StringBuilder],
      flags = Flags.EmptyFlags,
      privateWithin = Symbol.noSymbol
    )

    val statements = scala.collection.mutable.ListBuffer[Statement]()
    statements += ValDef(bufferSym, Some('{ new StringBuilder() }.asTerm))

    var isQuery = false

    while partExprs.hasNext do
      val part = partExprs.next()
      if part.contains('?') then
        if isQuery then
          report.errorAndAbort("multiple '?' characters are not allowed in a url interpolator")
        isQuery = true

      //   statements += '{
      //     ${Ref(bufferSym).asExprOf[StringBuilder]}.append(${Expr(part.stripSuffix("?"))})
      //   }.asTerm
      // else
      statements += '{
        ${Ref(bufferSym).asExprOf[StringBuilder]}.append(${Expr(part)})
      }.asTerm

      if argExprs.hasNext then
        val arg = argExprs.next()

        if isQuery then
          statements += '{
            ${Ref(bufferSym).asExprOf[StringBuilder]}.append(
              java.net.URLEncoder.encode(${arg}.toString, "UTF-8")
            )
          }.asTerm

          // if arg.asTerm.tpe <:< TypeRepr.of[Iterable[(String, String)]] then
          //   statements += '{
          //     addQueryParams(
          //       ${Ref(bufferSym).asExprOf[StringBuilder]},
          //       ${arg.asExprOf[Iterable[(String, String)]]}
          //     )
          //   }.asTerm
          // else
          //   report.errorAndAbort("Only Iterable[(String, String)] can be interpolated in the query part of a url")
        else
          statements += '{
            ${Ref(bufferSym).asExprOf[StringBuilder]}.append(
              java.net.URLEncoder.encode(${arg}.toString, "UTF-8").replace("+", "%20")
            )
          }.asTerm

    val block = Block(
      statements.toList,
      '{${Ref(bufferSym).asExprOf[StringBuilder]}.toString()}.asTerm
    )
    block.asExprOf[String]
