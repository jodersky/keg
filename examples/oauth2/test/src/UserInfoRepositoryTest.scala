import utest.*

object UserInfoRepositoryTest extends TestSuite:

  private def withDb(f: oauth2.UserInfoRepository => Unit): Unit =
    val db = simplesql.DataSource.pooled("jdbc:sqlite::memory:")
    simplesql.migrations.MigrationTool.fromClasspath(db, log = _ => ()).applyUp()
    val repo = oauth2.UserInfoRepository(db)
    f(repo)

  private def sampleUser(
    id: String = "u1",
    name: String = "alice",
    email: String = "alice@example.com",
    password: String = "hashed",
    roles: Seq[String] = Seq("user"),
    extraClaims: Map[String, String] = Map.empty
  ) = oauth2.UserInfo(id, name, email, password, roles, extraClaims)

  val tests = Tests:

    test("create and findByUserId"):
      withDb: repo =>
        val user = sampleUser()
        assert(repo.create(user) == true)
        val found = repo.findByUserId("u1")
        assert(found.isDefined)
        assert(found.get == user)

    test("findByUserId returns None for missing user"):
      withDb: repo =>
        val found = repo.findByUserId("nonexistent")
        assert(found.isEmpty)

    test("create and findByName"):
      withDb: repo =>
        val user = sampleUser()
        repo.create(user)
        val found = repo.findByName("alice")
        assert(found.isDefined)
        assert(found.get == user)

    test("findByName returns None for missing user"):
      withDb: repo =>
        val found = repo.findByName("nobody")
        assert(found.isEmpty)

    test("modify updates user"):
      withDb: repo =>
        repo.create(sampleUser())
        val result = repo.modify("u1")(_.copy(email = "new@example.com", roles = Seq("admin")))
        assert(result.isDefined)
        assert(result.get.email == "new@example.com")
        assert(result.get.roles == Seq("admin"))
        val found = repo.findByUserId("u1")
        assert(found == result)

    test("modify updates name column"):
      withDb: repo =>
        repo.create(sampleUser())
        val result = repo.modify("u1")(_.copy(name = "bob"))
        assert(result.isDefined)
        assert(result.get.name == "bob")
        val found = repo.findByName("bob")
        assert(found == result)

    test("modify returns None for missing user"):
      withDb: repo =>
        val result = repo.modify("nonexistent")(identity)
        assert(result.isEmpty)

    test("delete removes user"):
      withDb: repo =>
        repo.create(sampleUser())
        repo.delete("u1")
        assert(repo.findByUserId("u1").isEmpty)

    test("delete is idempotent for missing user"):
      withDb: repo =>
        repo.delete("nonexistent") // should not throw

    test("create duplicate returns false"):
      withDb: repo =>
        assert(repo.create(sampleUser()) == true)
        assert(repo.create(sampleUser()) == false)

    test("create multiple users"):
      withDb: repo =>
        repo.create(sampleUser(id = "u1", name = "alice"))
        repo.create(sampleUser(id = "u2", name = "bob", email = "bob@example.com"))
        assert(repo.findByUserId("u1").isDefined)
        assert(repo.findByUserId("u2").isDefined)
        assert(repo.findByName("alice").isDefined)
        assert(repo.findByName("bob").isDefined)
