package oauth2

// these are more like user-sessions, longer lived and can be used to obtain new
// access tokens without re-authenticating the user via a web UI
case class RefreshToken(
  hash: HashedToken,
  clientId: String,
  userId: String,
  scope: Set[String],
  sessionId: Option[String], // the browser session associated with the refresh token
  expiresAt: Option[Long], // if expiresAt is None, the refresh token never expires, but can still be revoked by deleting it from the database or deleting the associated consent
  nonce: Option[String] // store the nonce from the original authorization request to include in the ID token when using this refresh token
) derives upickle.default.ReadWriter

class RefreshTokenRepository(db: simplesql.DataSource):

  def findByToken(hash: HashedToken): Option[RefreshToken] =
    db.run:
      sql"""
      select data from refresh_token where token_sha256 = ${hash.toHex}
      """.readOpt[String].map(upickle.default.read[RefreshToken](_))

  def create(refreshToken: RefreshToken): Unit =
    db.run:
      sql"""
        insert into refresh_token (token_sha256, data) values (${refreshToken.hash.toHex}, ${upickle.default.write(refreshToken)})
      """.write()

  def delete(token: String): Unit =
    db.run:
      sql"""
      delete from refresh_token where token_sha256 = ${token}
      """.write()

  def deleteByClientId(clientId: String): Unit =
    db.run:
      sql"""
      delete from refresh_token where data->>'clientId' = $clientId
      """.write()
