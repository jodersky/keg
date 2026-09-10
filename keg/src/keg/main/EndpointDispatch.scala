package keg.main

import keg.model.Endpoint
import collection.mutable as m

// a higher-level wrapping of DispatchTrie, with support for HTTP methods
class EndpointDispatch:
  import EndpointDispatch.*

  private val trie = DispatchTrie[m.Map[String, AddedEndpoint]]()

  def add(ep: Endpoint): Unit =
    val segs = keg.util.Util.splitPath(ep.path)
    val (mmap, params) = trie.getOrElseUpdate(segs, m.Map())

    val value = AddedEndpoint(ep, params)

    val methods = if ep.methods.isEmpty then Seq("*") else ep.methods

    for method <- methods do
      mmap.get(method) match
        case Some(existing) =>
          val meta = existing.ep.meta
          throw IllegalArgumentException(
            s"error adding endpoint '${method.toUpperCase()} /${segs.mkString("/")}' (${meta}): another endpoint already exists at the same path and method"
          )
        case None =>
          mmap(method) = value

  def lookup(path: collection.IndexedSeq[String], method: String): Option[LookupResult] =
    trie.lookup(path) match
      case None => None
      case Some(result) =>
        result.value.get(method) match
          case Some(ok) =>
            Some(LookupResult(ok.ep, ok.paramNames.zip(result.bindings).toMap, result.remainingPathSegments))
          case None =>
            // '*' is a fallback
            result.value.get("*") match
              case Some(ok) =>
                Some(LookupResult(ok.ep, ok.paramNames.zip(result.bindings).toMap, result.remainingPathSegments))
              case None => None

object EndpointDispatch:
  // some information such as parameter names can only
  // be known after and endpoint has been added
  private class AddedEndpoint(
    val ep: Endpoint,
    var paramNames: Seq[String]
  )

  case class LookupResult(
    ep: Endpoint,
    bindings: Map[String, String],
    remainingPathSegments: collection.IndexedSeq[String]
  )
