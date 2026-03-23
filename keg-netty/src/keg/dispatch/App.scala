package keg.dispatch

import collection.mutable as m

case class Config(
  host: String = "",
  port: Int = 8080,
  debug: Boolean = false
)

class App():
  private val _endpoints = m.ListBuffer.empty[Endpoint]

  def endpoint(eps: Endpoint*): this.type =
    // todo add to trie
    _endpoints ++= eps
    this

  def host(h: String): this.type =
    // todo set host
    this

  def port(p: Int): this.type =
    // todo set port
    this

  def start(): Unit =
    println(s"Starting app with ${_endpoints.length} endpoints")

    ???



