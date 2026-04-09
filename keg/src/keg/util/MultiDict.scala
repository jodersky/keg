package keg.util

/**
  * A multidict is a map that can associate a sequence of values to a given key.
  * Access by default returns the first value associated to a key, but all the
  * values can be accessed through
  *
  * @tparam K the type of keys
  * @tparam V the type of values
  */
case class MultiDict[K, V](underlying: collection.Map[K, Iterable[V]]):

  def get(key: K): Option[V] = underlying.get(key).flatMap(_.headOption)
  def getOrElse(key: K, default: => V): V = get(key).getOrElse(default)
  def getAll(key: K): Iterable[V] = underlying.getOrElse(key, Seq.empty)

  def contains(key: K): Boolean = underlying.contains(key)

  def apply(key: K): V = get(key).getOrElse(throw new NoSuchElementException(s"key not found: $key"))

  def map(fn: (K, V) => (K, V)): MultiDict[K, V] =
    MultiDict(underlying.view.flatMap { case (k, vs) => vs.map(v => fn(k, v)) }.groupBy(_._1).view.mapValues(_.map(_._2).toSeq).toMap)

  def flatMap(fn: (K, V) => Seq[(K, V)]): MultiDict[K, V] =
    MultiDict(underlying.view.flatMap { case (k, vs) => vs.flatMap(v => fn(k, v)) }.groupBy(_._1).view.mapValues(_.map(_._2).toSeq).toMap)

  def mapValues(fn: V => V): MultiDict[K, V] =
    MultiDict(underlying.view.mapValues(_.map(fn)).toMap)

  def mapKeys(fn: K => K): MultiDict[K, V] =
    MultiDict(underlying.view.flatMap { case (k, vs) => Seq((fn(k), vs)) }.groupBy(_._1).view.mapValues(_.flatMap(_._2).toSeq).toMap)

  def filterKeys(fn: K => Boolean): MultiDict[K, V] =
    MultiDict(underlying.view.filterKeys(fn).toMap)

  def filterValues(fn: V => Boolean): MultiDict[K, V] =
    MultiDict(underlying.view.mapValues(_.filter(fn)).toMap)

  def filter(fn: (K, V) => Boolean): MultiDict[K, V] =
    MultiDict(underlying.view.flatMap { case (k, vs) => vs.filter(v => fn(k, v)).map(v => (k, v)) }.groupBy(_._1).view.mapValues(_.map(_._2).toSeq).toMap)

  def keys: Iterable[K] = underlying.keys
  def values: Iterable[V] = underlying.values.flatten
  def items: Iterable[(K, V)] = underlying.view.flatMap { case (k, vs) => vs.map(v => (k, v)) }

  def isEmpty: Boolean = underlying.isEmpty
  def nonEmpty: Boolean = underlying.nonEmpty

object MultiDict:
  def empty[K, V]: MultiDict[K, V] = MultiDict(Map.empty)
