package oauth2

import collection.mutable as m
import java.util.concurrent as juc
import com.github.benmanes.caffeine.cache.Caffeine
import com.github.benmanes.caffeine.cache.Cache

case class User(id: String, name: String)

case class UserConfig(
  id: String,
  name: String,
  password: String
) derives upickle.default.ReadWriter
case class ClientConfig(
  clientId: String,
  clientSecret: String,
  redirectUris: Seq[String]
) derives upickle.default.ReadWriter
case class Config(
  users: Seq[UserConfig],
  clients: Seq[ClientConfig]
) derives upickle.default.ReadWriter

case class AuthorizationCode(
  userId: String,
  clientId: String,
  redirectUri: String,
  scope: String,
  nonce: String,
  expiresAt: java.time.Instant
)

// maps sessionId -> userId
val sessions: Cache[String, String] = Caffeine.newBuilder()
  .expireAfterWrite(java.time.Duration.ofMinutes(30))
  .build[String, String]()

val authorizationCodes = Caffeine.newBuilder()
  .expireAfterWrite(java.time.Duration.ofMinutes(10))
  .build[String, AuthorizationCode]()


val config = Config(
  users = Seq(
    UserConfig("1", "alice", "password123"),
    UserConfig("2", "bob", "securepassword")
  ),
  clients = Seq(
    ClientConfig(
      clientId = "client1",
      clientSecret = "secret1",
      redirectUris = Seq("http://localhost:8080/callback")
    )
  )
)

object app extends keg.derivation.Api:

  given UserFromRequest: FromRequest[User] = (req, _) =>
    def unauthed() =
      keg.Result.Respond(keg.Response((), 303,
        headers = Seq("location" -> ("/login?dest=" + java.net.URLEncoder.encode(req.path, "UTF-8")))
      ))

    req.cookies.get("session") match
      case Some(sessionId) =>
        sessions.getIfPresent(sessionId.value) match
          case null => unauthed()
          case userId =>
            config.users.find(_.id == userId) match
              case Some(userConfig) => keg.Result.Continue(User(userConfig.id, userConfig.name))
              case None => unauthed()
      case None => unauthed()

  given UserOptFromRequest: FromRequest[Option[User]] = (req, _) =>
    req.cookies.get("session") match
      case Some(sessionId) =>
        sessions.getIfPresent(sessionId.value) match
          case null => keg.Result.Continue(None)
          case userId =>
            config.users.find(_.id == userId) match
              case Some(userConfig) => keg.Result.Continue(Option(User(userConfig.id, userConfig.name)))
              case None => keg.Result.Continue(None)
      case None => keg.Result.Continue(None)


@app.get("/.well-known/openid-configuration")
def openidConfig() =
  import upickle.default.*
  val config = ujson.Obj(
    "issuer" -> "http://localhost:8080",
    "authorization_endpoint" -> "http://localhost:8080/authorize"
  )
  config


@app.get("/")
def index() =
  keg.Response(
    (), 308, headers = Seq("location" -> "/login")
  )

@app.get("/login")
def login(user: Option[User], req: keg.Request) =
  import scalatags.Text.all.*
  import scalatags.Text.all.given
  import scalatags.Text.tags2.title

  def renderFlash() =
    req.flash.map: (key, value) =>
      div(cls := s"flash $key")(value)
    .toSeq

  val dest = req.queryParams.get("dest").getOrElse("/")

  doctype("html")(
    html(
      head(
        meta(charset := "UTF-8"),
        title("Example")
      ),
      body(
        p("Currently logged in as: " + user.map(_.name).getOrElse("Guest")),
        p("This is a simple example using Keg framework."),
        renderFlash(),
        form(action := "/login", method := "post")(
          input(`type` := "text", name := "username", placeholder := "Username"),
          input(`type` := "password", name := "password", placeholder := "Password"),
          input(`type` := "hidden", name := "dest", value := dest),
          input(`type` := "submit", value := "Login")
        )
      )
    )
  )

@app.post("/login")
def postLogin(form: keg.FormData) =
  val username: String = form.fields.get("username").getOrElse("")
  val password: String = form.fields.get("password").getOrElse("")
  val dest: String = form.fields.get("dest").getOrElse("/")

  config.users.find(_.name == username) match
    case Some(userConfig) if userConfig.password == password =>
      val sessionId = java.util.UUID.randomUUID().toString

      sessions.put(sessionId, userConfig.id)
      keg.Response(
        (), 303,
        headers = Seq("Location" -> dest),
        cookies = Seq(keg.model.Cookie("session", sessionId)),
        flash = Map("success" -> "Successfully logged in!")
      )
    case _ =>
      keg.Response(
        (), 303,
        headers = Seq("Location" -> "/login"),
        flash = Map("error" -> "Invalid username or password.")
      )

@app.get("/info")
def info(user: User) =
  import scalatags.Text.all.*
  import scalatags.Text.all.given
  import scalatags.Text.tags2.title

  // doctype("html")(
    html(
      head(
        meta(charset := "UTF-8"),
        title("User Info")
      ),
      body(
        h1("User Info"),
        p(s"User ID: ${user.id}"),
        p(s"Username: ${user.name}"),
        a(href := "/")("Home")
      )
    )
  // )

  h1("User Info")



val rs: keg.model.Routes = keg.routes()

@main()
def main() =
  println(crypto.dummy.jwks.render())

  // val server = keg.Server(rs.endpoints)
  // server.start()
