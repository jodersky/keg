package keg.util

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
        file = ${ Expr(Position.ofMacroExpansion.sourceFile.path) },
        line = ${ Expr(Position.ofMacroExpansion.startLine + 1) },
        column = ${ Expr(Position.ofMacroExpansion.startColumn + 1) },
        function = ${ Expr(Symbol.spliceOwner.owner.fullName) }
      )
    }
