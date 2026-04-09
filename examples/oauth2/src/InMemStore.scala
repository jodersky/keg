package oauth2

import com.github.benmanes.caffeine.cache.{Cache, Caffeine}
import scala.concurrent.duration.Duration

class InMemStore[K,V](ttl: Duration):
  val cache: Cache[K,V] = Caffeine.newBuilder()
    .expireAfterWrite(InMemStore.jts(ttl))
    .build[K,V]()

  def get(key: K): Option[V] =
    Option(cache.getIfPresent(key))

  def put(key: K, value: V): Unit =
    cache.put(key, value)

  def remove(key: K): Unit =
    cache.invalidate(key)

object InMemStore:
  private[oauth2] def jts(d: Duration): java.time.Duration =
    java.time.Duration.ofSeconds(d.toSeconds)

// an in-memory store with a secondary index
class InMemStore2[K1, K2, V](ttl: Duration):
  case class Entry(k1: K1, k2: K2, v: V)

  val cache: Cache[K1,Entry] = Caffeine.newBuilder()
    .expireAfterWrite(InMemStore.jts(ttl))
    .build[K1,Entry]()
  val cache2: Cache[K2,K1] = Caffeine.newBuilder()
    .expireAfterWrite(InMemStore.jts(ttl))
    .build[K2,K1]()

  def get1(key: K1): Option[V] =
    Option(cache.getIfPresent(key)).map(_.v)

  def get2(key: K2): Option[V] =
    cache2.getIfPresent(key) match
      case null => None
      case k1 => get1(k1)

  def put(key1: K1, key2: K2, value: V): Unit =
    cache.put(key1, Entry(key1, key2, value))
    cache2.put(key2, key1)

  def remove1(key1: K1): Unit =
    cache.getIfPresent(key1) match
      case null => ()
      case entry =>
        cache2.invalidate(entry.k2)
    cache.invalidate(key1)

  def remove2(key2: K2): Unit =
    cache2.getIfPresent(key2) match
      case null => ()
      case k1 =>
        cache.invalidate(k1)
    cache2.invalidate(key2)
