package oauth2

case class Client(
  id: String,
  secret: Option[HashedToken],
  redirectUris: Set[String],
  verifyInterval: Option[scala.concurrent.duration.Duration] = None,
  displayName: String = ""
) derives upickle.default.ReadWriter

class ClientRepository(db: simplesql.DataSource):

  def findByClientId(clientId: String): Option[Client] =
    db.run:
      sql"""
      select data from client where id = $clientId
      """.readOpt[String].map(upickle.default.read[Client](_))

  def create(client: Client): Boolean =
    db.run:
      sql"""
        insert into client (id, data) values (${client.id}, ${upickle.default.write(client)}) on conflict(id) do nothing
      """.write() > 0

  def modify(clientId: String)(f: Client => Client): Option[Client] =
    db.transaction:
      val client = sql"""
        select data from client where id = $clientId
      """.readOpt[String].map(upickle.default.read[Client](_))
      client match
        case Some(c) =>
          val modified = f(c)
          sql"""
            update client set data = ${upickle.default.write(modified)} where id = ${modified.id}
          """.write()
          Some(modified)
        case None =>
          None

  def delete(clientId: String): Unit =
    db.run:
      sql"""
      delete from client where id = ${clientId}
      """.write()

  def getAll: Seq[Client] =
    db.run:
      sql"""
      select data from client
      """.read[String].map(upickle.default.read[Client](_))
