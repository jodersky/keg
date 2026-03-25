package keg.transport

enum Listener:
  case Http(host: String, port: Int)
  case Https(host: String, port: Int, key: String, cert: String)

  def asUrl: String = this match
    case Http(host, port) => s"http://${Listener.ipAsUrl(host)}:$port"
    case Https(host, port, _, _) => s"https://${Listener.ipAsUrl(host)}:$port"

object Listener:
  private def ipAsUrl(addr: String): String =
    if addr.contains(":") then s"[$addr]"
    else addr
