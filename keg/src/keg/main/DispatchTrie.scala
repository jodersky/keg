package keg.main

import collection.mutable as m
import collection.IndexedSeq

/** A trie for routing path segments to values.
  *
  * A path is registered as a sequence of segments, where a segment starting
  * with ':' declares a parameter matching any single segment, and a trailing
  * '*' declares a wildcard matching any number of remaining segments
  * (including none). Every other segment matches literally; segments are never
  * decoded or normalized in any way.
  *
  * Matching walks the path segment by segment and, at every step, tries
  * statics first, then the parameter child, then falls back to the closest
  * wildcard seen so far. The lookup is O(number of segments): it commits to
  * the first branch that matches and never revisits that choice. In
  * particular, once a static segment matched, the parameter child of the same
  * node is out of the picture for the rest of the lookup, even if it would
  * have led to a match. Given "/a/b/c" and "/a/:x/d", the path "/a/b/d" does
  * not match anything, since "b" is claimed by the static branch. This is
  * intentional: routes are expected to be distinguished by their prefix, and
  * the alternative, backtracking over parameters, would make lookups
  * super-linear in adversarial cases.
  *
  * Wildcards are the exception, in that they are remembered as a fallback
  * while descending and used when the walk gets stuck or ends on a node
  * without a leaf. The deepest wildcard passed wins.
  */
class DispatchTrie[A]:
  import DispatchTrie.*

  // Value to return if no more path segments are left
  private var leaf: Option[A] = None

  // Highest precedence: children for static path segments (i.e., not starting with ":")
  private val statics = m.Map.empty[String, DispatchTrie[A]]

  // This trie also matches a segment corresponding to a parameter
  private var param: Option[DispatchTrie[A]] = None

  // This trie also matches a wildcard of any number of segments. This is of
  // lower precedence than any other situations. It however also includes
  // backtracking, so that on a mismatch the closest wildcard is always
  // selected
  private var wildcard: Option[A] = None

  /** Look up `path`, inserting `value` if no entry exists for it yet.
    *
    * Returns the entry now stored at `path`, which is the existing one if
    * there was any, and the parameter names as spelled by *this* call, in path
    * order.
    *
    * Throws IllegalArgumentException if '*' appears anywhere but at the end.
    */
  def getOrElseUpdate(path: collection.Seq[String], value: => A): (A, Seq[String]) =
    if path.nonEmpty && path.init.contains("*") then
      throw IllegalArgumentException("wildcard '*' can only appear at the end of a path")

    val paramNames = Seq.newBuilder[String]
    var node = this
    var isWildcard = false

    for segment <- path do
      if segment == "*" then
        isWildcard = true // can only happen on last segment as per requirement
      else if segment.startsWith(":") then
        paramNames += segment.drop(1)
        if node.param.isEmpty then node.param = Some(DispatchTrie[A]())
        node = node.param.get
      else
        node = node.statics.getOrElseUpdate(segment, DispatchTrie[A]())

    if isWildcard then
      if node.wildcard.isDefined then
        (node.wildcard.get, paramNames.result())
      else
        val v = value
        val p = paramNames.result()
        node.wildcard = Some(v)
        (v, p)
    else
      if node.leaf.isDefined then
        (node.leaf.get, paramNames.result())
      else
        val v = value
        val p = paramNames.result()
        node.leaf = Some(v)
        (v, p)

  /** Fast O(length-of-path) lookup.
    *
    * The bindings of the result are the segments that matched parameters, in
    * path order. remainingPathSegments is empty unless a wildcard matched, in
    * which case it holds the segments the wildcard swallowed.
    */
  def lookup(path: IndexedSeq[String]): Option[DispatchLookup[A]] =
    var closestWildcard: Option[A] = wildcard
    var closestWildcardDepth: Int = 0
    var closestWildcardParams: Int = 0 // number of params for the closest wildcard

    val bindings = Seq.newBuilder[String]

    var node = this

    var i = 0
    var nParams = 0
    while i < path.length do
      val segment = path(i)

      if node.statics.contains(segment) then
        node = node.statics(segment)
      else if node.param.isDefined then
        bindings += segment
        nParams += 1
        node = node.param.get
      else // no match
        closestWildcard match
          case Some(l) =>
            // Note about nParams: since we're backtracking we drop potentially
            // extra params. This isn't strictly necessary since in practice
            // we'll zip with endpoint params anyway (so extras don't really do
            // any harm), however out of hygiene we still drop them here.
            return Some(
              DispatchLookup(
                l,
                bindings.result().take(closestWildcardParams),
                path.slice(closestWildcardDepth, path.length)
              )
            )
          case None =>
            return None

      if node.wildcard.isDefined then
        closestWildcard = node.wildcard
        closestWildcardDepth = i + 1
        closestWildcardParams = nParams

      i += 1
    end while

    node.leaf match
      case Some(l) =>
        Some(
          DispatchLookup[A](
            l,
            bindings.result(), // no backtracking, so bindings can't be longer than params
            IndexedSeq.empty
          )
        )
      case None if closestWildcard.isDefined =>
        Some(
          DispatchLookup[A](
            closestWildcard.get,
            bindings.result().take(closestWildcardParams),
            path.slice(closestWildcardDepth, path.length)
          )
        )
      case None =>
        None // no segments left and no fallback

object DispatchTrie:

  case class DispatchLookup[+A](
    value: A,
    bindings: Seq[String],
    remainingPathSegments: IndexedSeq[String]
  )

  enum AddResult[+A]:
    case Ok
    case DuplicateEntry(
      prev: A,
      next: A,
      isWildcard: Boolean
    )
    case InvalidWildcard

