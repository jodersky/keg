package keg.main

import collection.mutable as m
import collection.IndexedSeq

case class DispatchTrie[A](capturesSubpath: Boolean = false):
  import DispatchTrie.*

  // Map from method (GET, POST, etc) to value
  private val items = m.Map.empty[String, A]

  // Children for static path segments (i.e., not starting with ":")
  private val staticChildren = m.Map.empty[String, DispatchTrie[A]]

  // Child for dynamic path segments (i.e., starting with ":")
  private var dynamicChildSegment: String = null // e.g., ":id"
  private var dynamicChildren: DispatchTrie[A] = null

  def add(
    path: Iterable[String],
    method: String,
    value: A,
    capturesSubpath: Boolean
  ): AddResult[A] =
    val currPath = m.StringBuilder()
    currPath ++= "/"

    var curr: DispatchTrie[A] = this

    val segments = path.iterator
    while segments.hasNext do
      val segment = segments.next()
      val terminal = !segments.hasNext

      if curr.capturesSubpath then
        return AddResult.SubpathConflict(currPath.toString(), curr.items.toMap)

      if segment.startsWith(":") then
        if curr.dynamicChildren == null then
          curr.dynamicChildSegment = segment.drop(1)
          curr.dynamicChildren = DispatchTrie[A](capturesSubpath && terminal)
        curr = curr.dynamicChildren
      else
        val child = curr.staticChildren.getOrElseUpdate(segment, DispatchTrie[A](capturesSubpath && terminal))
        curr = child

      currPath ++= segment
      currPath ++= "/"

    if curr.items.contains(method) then
      return AddResult.MethodConflict(currPath.toString(), curr.items.toMap)
    else
      curr.items(method) = value
    AddResult.Ok

  /** Fast O(length of path) lookup */
  def lookup(path: IndexedSeq[String]): DispatchLookup[A] =
    var curr: DispatchTrie[A] = this
    val bindings = m.Map.empty[String, String]
    var i = 0

    while i < path.length do
      val segment = path(i)
      i += 1

      if curr.capturesSubpath then
        return DispatchLookup(curr.items.toMap, bindings.toMap, path.slice(i - 1, path.length))
      else if curr.staticChildren.contains(segment) then
        curr = curr.staticChildren(segment)
      else if curr.dynamicChildren != null then
        bindings += (curr.dynamicChildSegment -> segment)
        curr = curr.dynamicChildren
      else
        return DispatchLookup(Map.empty, bindings.toMap, path.slice(i - 1, path.length))

    DispatchLookup(curr.items.toMap, bindings.toMap, IndexedSeq.empty)

object DispatchTrie:
  case class DispatchLookup[A](
    items: collection.Map[String, A],
    bindings: Map[String, String],
    remainingPathSegments: IndexedSeq[String]
  )

  enum AddResult[+A]:
    case Ok
    case SubpathConflict(
      existingPath: String,
      existingItems: Map[String, A]
    )
    case MethodConflict(
      existingPath: String,
      existingItems: Map[String, A],
    )
