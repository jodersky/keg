import utest.*

object ConsentRepositoryTest extends TestSuite:

  private def withDb(f: oauth2.ConsentRepository => Unit): Unit =
    val db = simplesql.DataSource.pooled("jdbc:sqlite::memory:")
    simplesql.migrations.MigrationTool.fromClasspath(db, log = _ => ()).applyUp()
    val repo = oauth2.ConsentRepository(db)
    f(repo)

  private def sampleConsent(
    clientId: String = "client1",
    userId: String = "user1",
    scope: Set[String] = Set("openid", "profile")
  ) = oauth2.Consent(clientId, userId, scope)

  val tests = Tests:

    test("createOrModify creates new consent"):
      withDb: repo =>
        val consent = sampleConsent()
        val result = repo.createOrModify("user1", "client1")(
          create = consent,
          modify = identity
        )
        assert(result == consent)
        val found = repo.findByClientId("user1", "client1")
        assert(found.isDefined)
        assert(found.get == consent)

    test("createOrModify modifies existing consent"):
      withDb: repo =>
        val consent = sampleConsent()
        repo.createOrModify("user1", "client1")(
          create = consent,
          modify = identity
        )
        val result = repo.createOrModify("user1", "client1")(
          create = sampleConsent(), // should not be used
          modify = _.copy(scope = Set("openid", "profile", "email"))
        )
        assert(result.scope == Set("openid", "profile", "email"))
        val found = repo.findByClientId("user1", "client1")
        assert(found.get.scope == Set("openid", "profile", "email"))

    test("findByClientId returns None for missing consent"):
      withDb: repo =>
        val found = repo.findByClientId("user1", "nonexistent")
        assert(found.isEmpty)

    test("findByUserAndClientId returns same result as findByClientId"):
      withDb: repo =>
        val consent = sampleConsent()
        repo.createOrModify("user1", "client1")(
          create = consent,
          modify = identity
        )
        val found1 = repo.findByClientId("user1", "client1")
        val found2 = repo.findByUserAndClientId("user1", "client1")
        assert(found1 == found2)

    test("delete removes consent"):
      withDb: repo =>
        repo.createOrModify("user1", "client1")(
          create = sampleConsent(),
          modify = identity
        )
        repo.delete("user1", "client1")
        assert(repo.findByClientId("user1", "client1").isEmpty)

    test("delete is idempotent for missing consent"):
      withDb: repo =>
        repo.delete("user1", "nonexistent") // should not throw

    test("multiple consents for same user"):
      withDb: repo =>
        repo.createOrModify("user1", "client1")(
          create = sampleConsent(clientId = "client1"),
          modify = identity
        )
        repo.createOrModify("user1", "client2")(
          create = sampleConsent(clientId = "client2"),
          modify = identity
        )
        assert(repo.findByClientId("user1", "client1").isDefined)
        assert(repo.findByClientId("user1", "client2").isDefined)

    test("consents are scoped to user"):
      withDb: repo =>
        repo.createOrModify("user1", "client1")(
          create = sampleConsent(userId = "user1"),
          modify = identity
        )
        assert(repo.findByClientId("user2", "client1").isEmpty)
