package oauth2

case class UserInfo(
  id: String,
  name: String,
  email: String,
  passwordArgon2: String,
  roles: Seq[String],
  extraClaims: Map[String, String]
) derives upickle.default.ReadWriter

class UserInfoRepository(db: simplesql.DataSource):

  def findByUserId(userId: String): Option[UserInfo] =
    db.run:
      sql"""
      select data from user_info where id = $userId
      """.readOpt[String].map(upickle.default.read[UserInfo](_))

  def findByName(name: String): Option[UserInfo] =
    db.run:
      sql"""
      select data from user_info where name = $name
      """.readOpt[String].map(upickle.default.read[UserInfo](_))

  def create(user: UserInfo): Boolean =
    db.run:
      sql"""
        insert into user_info (id, name, data) values (${user.id}, ${user.name}, ${upickle.default.write(user)}) on conflict(id) do nothing
      """.write() > 0

  def modify(id: String)(f: UserInfo => UserInfo): Option[UserInfo] =
    db.transaction:
      sql"""
      select data from user_info where id = $id
      """.readOpt[String].map(upickle.default.read[UserInfo](_)) match
        case None => None
        case Some(user) =>
          val modified = f(user)
          sql"""
          update user_info set name = ${modified.name}, data = ${upickle.default.write(modified)} where id = $id
          """.write()
          Some(modified)

  def delete(userId: String): Unit =
    db.run:
      sql"""
      delete from user_info where id = ${userId}
      """.write()

  def getAll: Seq[UserInfo] =
    db.run:
      sql"""
      select data from user_info
      """.read[String].map(upickle.default.read[UserInfo](_))
