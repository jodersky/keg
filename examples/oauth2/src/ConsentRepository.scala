package oauth2

case class Consent(
  clientId: String,
  userId: String,
  scope: Set[String]
) derives upickle.default.ReadWriter

class ConsentRepository(db: simplesql.DataSource):

  def findByClientId(userId: String, clientId: String): Option[Consent] =
    db.run:
      sql"""
      select data from consent where user_id = $userId and client_id = $clientId
      """.readOpt[String].map(upickle.default.read[Consent](_))

  def findByUserAndClientId(userId: String, clientId: String): Option[Consent] =
    db.run:
      sql"""
      select data from consent where user_id = $userId and client_id = $clientId
      """.readOpt[String].map(upickle.default.read[Consent](_))

  def findByUserId(userId: String): Seq[Consent] =
    db.run:
      sql"""
      select data from consent where user_id = $userId
      """.read[String].map(upickle.default.read[Consent](_)).toSeq

  def createOrModify(userId: String, clientId: String)(
    create: => Consent,
    modify: Consent => Consent
  ): Consent =
    db.transaction:
      sql"""
      select data from consent where user_id = $userId and client_id = $clientId
      """.readOpt[String].map(upickle.default.read[Consent](_)) match
        case Some(consent) =>
          val modified = modify(consent)
          sql"""
          update consent set data = ${upickle.default.write(modified)} where user_id = $userId and client_id = $clientId
          """.write()
          modified
        case None =>
          val created = create
          sql"""
          insert into consent (user_id, client_id, data) values ($userId, $clientId, ${upickle.default.write(created)})
          """.write()
          created

  def delete(userId: String, clientId: String): Unit =
    db.run:
      sql"""
      delete from consent where user_id = $userId and client_id = $clientId
      """.write()
