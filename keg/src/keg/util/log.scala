package keg.util

object log:
  // val logger = org.slf4j.LoggerFactory.getLogger("keg")

  def info(msg: String)(using pos: Pos): Unit =
    // logger.info(s"[${pos.function}(${pos.file}:${pos.line}:${pos.column})] $msg")
    println(s"[${pos.function}(${pos.file}:${pos.line}:${pos.column})] $msg")

  def warn(msg: String)(using pos: Pos): Unit =
    // logger.warn(s"[${pos.function}(${pos.file}:${pos.line}:${pos.column})] $msg")
    println(s"[${pos.function}(${pos.file}:${pos.line}:${pos.column})] $msg")

  def error(msg: String, err: Throwable = null)(using pos: Pos): Unit =
    // logger.error(s"[${pos.function}(${pos.file}:${pos.line}:${pos.column})] $msg", err)
    println(s"[${pos.function}(${pos.file}:${pos.line}:${pos.column})] $msg")
    if (err != null) err.printStackTrace()

import scala.quoted.Expr
import scala.quoted.Quotes
case class Pos(
  file: String,
  line: Int,
  column: Int,
  function: String
)
object Pos:
  inline given Pos = here()

  inline def here(): Pos = ${ hereImpl() }

  private def hereImpl()(using q: Quotes): Expr[Pos] =
    import q.reflect.*

    '{
      Pos(
        file = ${ Expr(Position.ofMacroExpansion.sourceFile.getJPath.map(_.getFileName.toString()).getOrElse("<unknown>")) },
        line = ${ Expr(Position.ofMacroExpansion.startLine + 1) },
        column = ${ Expr(Position.ofMacroExpansion.startColumn + 1) },
        function = ${ Expr(Symbol.spliceOwner.owner.fullName) }
      )
    }
