import utest.*

object ClientRepositoryTest extends TestSuite:

  private def withDb(f: oauth2.ClientRepository => Unit): Unit =
    val db = simplesql.DataSource.pooled("jdbc:sqlite::memory:")
    simplesql.migrations.MigrationTool.fromClasspath(db, log = _ => ()).applyUp()
    val repo = oauth2.ClientRepository(db)
    f(repo)

  private def sampleClient(
    id: String = "client1",
    secret: Option[oauth2.HashedToken] = Some(oauth2.HashedToken.fromHex("abcd" * 16)),
    redirectUris: Set[String] = Set("https://example.com/callback"),
    verifyInterval: Option[scala.concurrent.duration.Duration] = None
  ) = oauth2.Client(id, secret, redirectUris, verifyInterval)

  val tests = Tests:

    test("create and findByClientId"):
      withDb: repo =>
        val client = sampleClient()
        assert(repo.create(client) == true)
        val found = repo.findByClientId("client1")
        assert(found.isDefined)
        assert(found.get == client)

    test("findByClientId returns None for missing client"):
      withDb: repo =>
        val found = repo.findByClientId("nonexistent")
        assert(found.isEmpty)

    test("create duplicate returns false"):
      withDb: repo =>
        assert(repo.create(sampleClient()) == true)
        assert(repo.create(sampleClient()) == false)

    test("modify updates client"):
      withDb: repo =>
        repo.create(sampleClient())
        val result = repo.modify("client1")(_.copy(redirectUris = Set("https://new.example.com/cb")))
        assert(result.isDefined)
        assert(result.get.redirectUris == Set("https://new.example.com/cb"))
        val found = repo.findByClientId("client1")
        assert(found == result)

    test("modify returns None for missing client"):
      withDb: repo =>
        val result = repo.modify("nonexistent")(identity)
        assert(result.isEmpty)

    test("delete removes client"):
      withDb: repo =>
        repo.create(sampleClient())
        repo.delete("client1")
        assert(repo.findByClientId("client1").isEmpty)

    test("delete is idempotent for missing client"):
      withDb: repo =>
        repo.delete("nonexistent") // should not throw

    test("create multiple clients"):
      withDb: repo =>
        repo.create(sampleClient(id = "c1"))
        repo.create(sampleClient(id = "c2", redirectUris = Set("https://other.com/cb")))
        assert(repo.findByClientId("c1").isDefined)
        assert(repo.findByClientId("c2").isDefined)

    test("client with no secret"):
      withDb: repo =>
        val client = sampleClient(secret = None)
        repo.create(client)
        val found = repo.findByClientId("client1")
        assert(found.isDefined)
        assert(found.get.secret.isEmpty)

    test("client with verify interval"):
      withDb: repo =>
        import scala.concurrent.duration.*
        val client = sampleClient(verifyInterval = Some(30.minutes))
        repo.create(client)
        val found = repo.findByClientId("client1")
        assert(found.isDefined)
        assert(found.get.verifyInterval == Some(30.minutes))
