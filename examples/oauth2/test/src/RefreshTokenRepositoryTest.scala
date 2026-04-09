import utest.*

object RefreshTokenRepositoryTest extends TestSuite:

  private def withDb(f: oauth2.RefreshTokenRepository => Unit): Unit =
    val db = simplesql.DataSource.pooled("jdbc:sqlite::memory:")
    simplesql.migrations.MigrationTool.fromClasspath(db, log = _ => ()).applyUp()
    val repo = oauth2.RefreshTokenRepository(db)
    f(repo)

  private def sampleToken(
    hash: oauth2.HashedToken = oauth2.Token.random().sha256(),
    clientId: String = "client1",
    userId: String = "user1",
    scope: Set[String] = Set("openid", "profile"),
    sessionId: Option[String] = Some("session1"),
    expiresAt: Option[Long] = Some(System.currentTimeMillis() / 1000 + 3600),
    nonce: Option[String] = Some("nonce1")
  ) = oauth2.RefreshToken(hash, clientId, userId, scope, sessionId, expiresAt, nonce)

  val tests = Tests:

    test("create and findByToken"):
      withDb: repo =>
        val hash = oauth2.Token.random().sha256()
        val token = sampleToken(hash = hash)
        repo.create(token)
        val found = repo.findByToken(hash)
        assert(found.isDefined)
        assert(found.get == token)

    test("findByToken returns None for missing token"):
      withDb: repo =>
        val hash = oauth2.Token.random().sha256()
        val found = repo.findByToken(hash)
        assert(found.isEmpty)

    test("delete removes token"):
      withDb: repo =>
        val hash = oauth2.Token.random().sha256()
        val token = sampleToken(hash = hash)
        repo.create(token)
        repo.delete(hash.toHex)
        assert(repo.findByToken(hash).isEmpty)

    test("delete is idempotent for missing token"):
      withDb: repo =>
        repo.delete("nonexistent") // should not throw

    test("multiple tokens for same user"):
      withDb: repo =>
        val hash1 = oauth2.Token.random().sha256()
        val hash2 = oauth2.Token.random().sha256()
        repo.create(sampleToken(hash = hash1))
        repo.create(sampleToken(hash = hash2))
        assert(repo.findByToken(hash1).isDefined)
        assert(repo.findByToken(hash2).isDefined)

    test("token with no expiry"):
      withDb: repo =>
        val hash = oauth2.Token.random().sha256()
        val token = sampleToken(hash = hash, expiresAt = None)
        repo.create(token)
        val found = repo.findByToken(hash)
        assert(found.isDefined)
        assert(found.get.expiresAt.isEmpty)

    test("token with no session"):
      withDb: repo =>
        val hash = oauth2.Token.random().sha256()
        val token = sampleToken(hash = hash, sessionId = None)
        repo.create(token)
        val found = repo.findByToken(hash)
        assert(found.isDefined)
        assert(found.get.sessionId.isEmpty)

    test("delete only removes targeted token"):
      withDb: repo =>
        val hash1 = oauth2.Token.random().sha256()
        val hash2 = oauth2.Token.random().sha256()
        repo.create(sampleToken(hash = hash1))
        repo.create(sampleToken(hash = hash2))
        repo.delete(hash1.toHex)
        assert(repo.findByToken(hash1).isEmpty)
        assert(repo.findByToken(hash2).isDefined)
