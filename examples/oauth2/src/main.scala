package oauth2

import collection.mutable as m
import java.util.concurrent as juc
import com.github.benmanes.caffeine.cache.Caffeine
import com.github.benmanes.caffeine.cache.Cache
import scalatags.Text.all as h
import scalatags.Text.tags2.nav
import h.given
import keg.url

// a centered-full screen page, with flash message support
def fullPage(req: keg.Request, title: String)(content: h.Modifier*): h.doctype =
  h.doctype("html")(
    h.html(
      h.head(
        h.meta(h.charset := "UTF-8"),
        h.attr("title") := title,
        h.link(h.rel := "stylesheet", h.href := "/static/main.css"),
        h.link(h.rel := "icon", h.`type` := "image/svg+xml", h.href := "/static/logo.svg"),
        h.script(h.src := "/static/htmx.min.js")
      ),
      h.body(h.cls := "layout-full")(
        req.flash.map: (key, value) =>
          h.div(h.cls := s"flash $key")(value)
        .toSeq,
        content
      )
    )
  )

// a standard page
def page(req: keg.Request, title: String, username: String)(content: h.Modifier*): h.doctype =
  h.doctype("html")(
    h.html(
      h.head(
        h.meta(h.charset := "UTF-8"),
        h.attr("title") := title,
        h.link(h.rel := "stylesheet", h.href := "/static/main.css"),
        h.link(h.rel := "icon", h.`type` := "image/svg+xml", h.href := "/static/logo.svg"),
        h.script(h.src := "/static/htmx.min.js")
      ),
      h.body(h.cls := "layout-page")(
        h.header(h.cls := "topbar")(
          nav(
            h.img(h.src := "/static/logo.svg", h.cls := "topbar-logo"),
            h.a(h.href := "/")("Home"),
            " | ",
            h.a(h.href := "/admin/users")("Users"),
            " | ",
            h.a(h.href := "/admin/clients")("Clients")
          ),
          h.div(h.cls := "user-menu")(
            h.button(h.attr("popovertarget") := "user-menu-dropdown")(username),
            h.div(h.id := "user-menu-dropdown", h.cls := "user-menu-dropdown", h.attr("popover") := "auto")(
              h.form(h.action := "/logout", h.method := "POST")(
                h.button(h.`type` := "submit")("Logout")
              )
            )
          )
        ),
        h.div(h.cls := "content")(
          req.flash.map: (key, value) =>
            h.div(h.cls := s"flash $key")(value)
          .toSeq,
          content
        )
      )
    )
  )

case class User(id: String, name: String, sess: Session)

case class Session(
  id: String,
  hash: HashedToken,
  userId: String,
  name: String,
  lastAuthenticated: java.time.Instant,
  lastSudo: Option[java.time.Instant]
)

// case class UserConfig(
//   id: String,
//   name: String,
//   password: String
// ) derives upickle.default.ReadWriter

// case class ServerConfig(
//   host: String,
//   port: Int,
//   secrets: Seq[String],
//   sessionMaxAgeSeconds: Int = 3600
// ) derives upickle.default.ReadWriter

// case class Config(
//   users: Seq[UserConfig],
//   clients: Seq[ClientConfig]
// ) derives upickle.default.ReadWriter


case class SudoMode(user: User)

class App(config: Config) extends keg.derivation.Api:

  // maps sessionId -> userId
  val sessions: InMemStore2[HashedToken, String, Session] =
    InMemStore2(config.server.sessionTtl)

  def lookup(cookie: keg.model.Cookie): Option[User] =
    val tokenHash = oauth2.Token.fromHex(cookie.value).sha256()
    sessions.get1(tokenHash) match
      case None => None
      case Some(session) =>
        Some(User(session.userId, session.name, session))

  given UserFromRequest: FromRequest[User] = (req, _) =>
    def unauthed() =
      keg.Result.Respond(keg.Response((), 303,
        headers = Seq("location" -> url"/?dest=${req.target}")
      ))

    req.cookies.get("session").flatMap(s => lookup(s)) match
      case Some(user) => keg.Result.Continue(user)
      case None => unauthed()

  given UserOptFromRequest: FromRequest[Option[User]] = (req, _) =>
    keg.Result.Continue(req.cookies.get("session").flatMap(s => lookup(s)))

  given SudoModeFromRequest(using r: FromRequest[User]): FromRequest[SudoMode] = (req, ctx) =>
    r.fromRequest(req, ctx) match
      case keg.Result.Continue(user) =>
        val now = java.time.Instant.now()
        if user.sess.lastSudo.exists(s => s.plusSeconds(300).isAfter(now)) then
          keg.Result.Continue(SudoMode(user))
        else
          // val url = new StringBuilder(req.path)
          // if !req.params.isEmpty then
          //   val flattened = req.params.items
          //   url ++= "?"
          //   url ++= flattened.head._1
          //   url ++= "="
          //   url ++= flattened.head._2
          //   for ((key, value) <- flattened.tail) do
          //     url ++= s"&$key=$value"

          keg.Result.Respond(keg.Response((), 303,
            headers = Seq("location" -> url"/sudo?dest=${req.target}")
          ))
      case keg.Result.Respond(resp) => keg.Result.Respond(resp)
      case keg.Result.Abort(code, message) =>
        keg.Result.Respond(keg.Response(message, code))

@clip.group()
def common(config: os.Path = os.pwd / "config.yaml"): os.Path = config

@clip.command(help = "Generate a new random secret")
def gensecret() =
  clip.echo(keg.main.Secret.random())

private def getConfig(path: os.Path): Config =
  val host = sys.env.get("HOST").getOrElse("::")
  val port = sys.env.get("PORT").map(_.toInt).getOrElse(8080)
  val key = sys.env.get("KEY").map(os.Path(_))
  val cert = sys.env.get("CERT").map(os.Path(_))
  val db = sys.env.get("DB").map(os.Path(_)).getOrElse(os.pwd / "sso.db")
  val sessionTtl = scala.concurrent.duration.Duration(
    sys.env.get("SESSION_TTL").getOrElse("7d")
  )
  val issuer = sys.env.get("ISSUER").getOrElse(s"http://localhost:$port")
  val secret = sys.env.get("SECRET").toSeq

  Config(
    server = ServerConfig(
      host = host,
      port = port,
      key = key,
      cert = cert,
      db = db,
      sessionTtl = sessionTtl,
      issuer = issuer,
      secrets = secret
    )
  )


private def getDb(config: Config, migrate: Boolean = true): simplesql.DataSource =
  val db = simplesql.DataSource.pooled("jdbc:sqlite:" + config.server.db.toString)
  db.run: (conn) ?=>
    val stat = conn.underlying.createStatement()
    stat.execute("PRAGMA journal_mode=WAL")
    stat.execute("PRAGMA busy_timeout=5000")
  if migrate then
    simplesql.migrations.MigrationTool.fromClasspath(db).applyUp()

    // create jwk if none exists
    db.transaction:
      val count = sql"select count(*) from jwks".readOne[Int]
      if count == 0 then
        println("No signing keys found in database, generating a new one...")
        val keyPairGenerator = java.security.KeyPairGenerator.getInstance("EC")
        keyPairGenerator.initialize(new java.security.spec.ECGenParameterSpec("secp256r1"))
        val keyPair = keyPairGenerator.generateKeyPair()

        val privateKey = keyPair.getPrivate().getEncoded()
        val publicKey = keyPair.getPublic().getEncoded()
        val kid = oauth2.jwt.kid(keyPair.getPublic.asInstanceOf[java.security.interfaces.ECPublicKey])
        sql"""
          insert into jwks (kid, private_key, public_key) values
          ($kid, $privateKey, $publicKey)
        """.write()
  db

@clip.command(help = "Add an admin user (will prompt for password)")
def addAdmin(
  @clip.context configPath: os.Path
) =
  val config = getConfig(configPath)
  val db = getDb(config)
  val repo = UserInfoRepository(db)

  val username = clip.prompt[String]("Enter username for new admin user")
  if username.length == 0 then
    clip.abort("Username cannot be empty.")
  if repo.findByName(username).isDefined then
    clip.abort("User with that name already exists.")

  val password1 = clip.prompt[String]("Enter password for new admin user", password=true)
  if password1.length == 0 then
    clip.abort("Password cannot be empty.")

  val password2 = clip.prompt[String]("Re-enter password for new admin user", password=true)

  if password1 != password2 then
    clip.abort("Passwords do not match.")

  val hashedPassword = Password.hash(password1)

  val userInfo = UserInfo(
    id = java.util.UUID.randomUUID().toString,
    name = username,
    email = "",
    passwordArgon2 = hashedPassword,
    roles = Seq("admin"),
    extraClaims = Map.empty
  )
  repo.create(userInfo)
  clip.echo(s"Admin user '${username}' created successfully.")

@clip.command(help = "Run the server")
def serve(
  @clip.context configPath: os.Path
) =
  val config = getConfig(configPath)

  val db = getDb(config)
  val userInfoRepo = UserInfoRepository(db)
  val clientRepo = ClientRepository(db)

  val app = new App(config)
  val endpoints =
    UiModule(app, config, db, userInfoRepo, clientRepo).routes ++
    OidcModule(app, config, db, userInfoRepo, clientRepo).routes ++
    AdminModule(app, config, db, userInfoRepo, clientRepo).routes

  val listener = if config.server.key.isDefined then
    keg.Listener.Https(
      host = config.server.host,
      port = config.server.port,
      key = config.server.key.get.toNIO,
      cert = config.server.cert.get.toNIO
    )
  else
    keg.Listener.Http(
      host = config.server.host,
      port = config.server.port,
    )

  val server = keg.Server(
    endpoints = endpoints,
    listeners = Seq(listener),
    secrets = config.server.secrets,
    interceptors = Seq(
      keg.contrib.TimingInterceptor(),
    )
  )

  val metaServer = keg.Server(
    endpoints = Seq(
      keg.model.Endpoint(
        "/health", Seq("GET"),
        invoke = _ => keg.Response("OK")
      ),
      keg.model.Endpoint(
        "/metrics", Seq("GET"),
        invoke = _ => keg.Response(ustats.Registry.global)
      ),
    ),
    listeners = Seq(
      keg.Listener.Http(
        host = config.server.host,
        port = 8081
      )
    ),
    secrets = Seq(keg.Secret.random()), // we don't use secrets for the meta server
  )

  server.start()
  ustats.platform.allMetrics()
  metaServer.start()
  sys.addShutdownHook:
    scribe.info("Shutting down servers")
    server.stop()
    metaServer.stop()
    server.awaitTermination()
    metaServer.awaitTermination()

  server.awaitTermination()
  metaServer.awaitTermination()

def main(args: Array[String]): Unit =
  scribe.Logger.root
    .clearHandlers()
    .withHandler(
      formatter = scribe.format.Formatter.compact
    )
    .withMinimumLevel(scribe.Level.Info)
    .replace()

  // keep the noise down from these libraries we don't control; most of
  // them output stuff to INFO which should probably be DEBUG
  scribe.Logger("com.zaxxer").withMinimumLevel(scribe.Level.Warn).replace()
  scribe.Logger("io.undertow").withMinimumLevel(scribe.Level.Warn).replace()
  scribe.Logger("org.xnio").withMinimumLevel(scribe.Level.Warn).replace()
  clip.main(this, args)
