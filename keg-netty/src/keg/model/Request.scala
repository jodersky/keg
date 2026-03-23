package keg.model

// import io.undertow.server.HttpServerExchange
import scala.reflect.ClassTag
import scala.annotation.meta.param

case class Request(
  // exchange: HttpServerExchange,
  method: String,
  uri: String,
  headers: Map[String, Seq[String]],
  pathParams: Map[String, String],
  remainingPathSegments: Seq[String],
  cookies: Map[String, Cookie],
  session: Map[String, String],
  flash: Map[String, String]
):
  lazy val path: String =
    uri.indexOf('?') match
      case -1 => uri
      case idx => uri.substring(0, idx)

  lazy val queryParams: Map[String, Seq[String]] =
    Util.decodeQueryString(
      uri,
      uri.indexOf('?') match
        case -1 => uri.length
        case idx => idx + 1
    )

object Util:
  def decodeQueryString(uri: String, idx: Int): Map[String, Seq[String]] =
    if idx > uri.length then Map()
    else
      var limit = 1024 // params limit
      var done = false
      var i = idx
      val params = scala.collection.mutable.Map[String, scala.collection.mutable.ListBuffer[String]]()

      var nameStart = idx
      var valueStart = -1

      def addParam(): Boolean =
        if nameStart >= i then
          false
        else
          if valueStart <= nameStart then
            valueStart = i + 1
          val name = java.net.URLDecoder.decode(uri.substring(nameStart, valueStart - 1), "UTF-8")
          val value = java.net.URLDecoder.decode(uri.substring(valueStart - 1, i), "UTF-8")
          val values = params.getOrElseUpdate(name, scala.collection.mutable.ListBuffer[String]())
          values += value
          true

      while i < uri.length && !done do
        uri.charAt(i) match
          case '=' =>
            if nameStart == i then
              nameStart = i + 1
            else if valueStart < nameStart then
              valueStart = i + 1
          case '&' =>
            if addParam() then
              limit -= 1
              if limit <= 0 then done = true
            nameStart = i + 1
          case '#' => done = true
          case _ => // continue
        i += 1
      addParam()

      params.view.mapValues(_.toSeq).toMap

case class Cookie(
  name: String,
  value: String,
  domain: Option[String] = None,
)

trait BodyDecoder[A]:
  def init(): Unit
  def decodeChunk(chunk: io.netty.buffer.ByteBuf): Unit
  def result(): A

object NoBodyDecoder extends BodyDecoder[Unit]:
  def init(): Unit = ()
  def decodeChunk(chunk: io.netty.buffer.ByteBuf): Unit = sys.error("NoBodyDecoder cannot decode chunks")
  def result(): Unit = ()
