package oauth2

import scalatags.Text.all as h
import scalatags.Text.tags2.nav
import h.given

object UiModule:
  val failedLogins = ustats.counter.simple("logins_failed", "Number of failed login attempts")
  val successfulLogins = ustats.counter.simple("logins_successful", "Number of successful login attempts")

// user interface module, contains routes for login/logout and serving static assets
class UiModule(app: App, config: Config, db: simplesql.DataSource, userInfoRepo: UserInfoRepository, clientRepo: ClientRepository):
  UiModule // initialize the companion object to register metrics

  private val consentRepo = ConsentRepository(db)
  private val refreshTokenRepo = RefreshTokenRepository(db)

  @app.get("/static/*")
  def assets(req: keg.Request) =
    keg.model.Static.staticResources("/static", req.remainingPathSegments)

  // TODO: this should probably be in a separate module
  @app.get("/health")
  def health() = keg.Response(ujson.Obj("status" -> "ok"), 200)

  @app.get("/fail")
  def fail() =
    throw new Exception("This is a test exception for demonstrating error handling and logging")
    ""

  @app.get("/")
  def index(user: Option[User], req: keg.Request) =
    if user.isDefined then
      val consents = consentRepo.findByUserId(user.get.id)
      page(req, "Home", user.get.name)(
        h.h2("Account"),
        h.p("Welcome, " + user.get.name + "!"),
        h.form(h.action := "/logout", h.method := "POST")(
          h.input(h.`type` := "submit", h.value := "Logout")
        ),

        h.h2("Change Password"),
        h.form(h.action := "/change-password", h.method := "POST", h.cls := "change-password-form")(
          h.div(h.cls := "form-field")(
            h.label("Current Password"),
            h.input(h.`type` := "password", h.name := "currentPassword", h.autocomplete := "current-password")
          ),
          h.div(h.cls := "form-field")(
            h.label("New Password"),
            h.input(h.`type` := "password", h.name := "newPassword", h.autocomplete := "new-password")
          ),
          h.div(h.cls := "form-field")(
            h.label("Confirm New Password"),
            h.input(h.`type` := "password", h.name := "confirmPassword", h.autocomplete := "new-password")
          ),
          h.button(h.`type` := "submit")("Change Password")
        ),

        h.h2("Authorized Applications"),
        if consents.isEmpty then
          h.p(h.cls := "text-muted")("No applications have been authorized.")
        else
          h.div(h.cls := "item-list")(
            consents.map: consent =>
              val clientName = clientRepo.findByClientId(consent.clientId)
                .map(_.displayName).filter(_.nonEmpty).getOrElse(consent.clientId)
              h.div(h.cls := "item-row consent-row")(
                h.span(h.cls := "item-row-main")(
                  h.span(h.cls := "item-row-name")(clientName),
                  h.span(h.cls := "item-row-id")(
                    "Scopes: ",
                    if consent.scope.isEmpty then "none"
                    else consent.scope.mkString(", ")
                  )
                ),
                h.form(h.action := s"/revoke-consent/${consent.clientId}", h.method := "POST", h.cls := "consent-revoke-form")(
                  h.button(h.`type` := "submit", h.cls := "confirm-dialog-danger")("Revoke")
                )
              )
          )
      )
    else
      fullPage(req, "Home")(
        h.img(h.src := "/static/logo.svg", h.cls := "login-logo"),
        h.h1("Login"),
        h.form(h.action := "/login", h.method := "POST")(
          h.input(h.`type` := "text", h.name := "username", h.placeholder := "Username", h.autofocus := true, h.autocomplete := "username"),
          h.input(h.`type` := "password", h.name := "password", h.placeholder := "Password", h.autocomplete := "current-password"),
          h.input(h.`type` := "hidden", h.name := "dest", h.value := req.params.getOrElse("dest", "/")),
          h.input(h.`type` := "submit", h.value := "Login")
        )
      )

  @app.post("/change-password")
  def changePassword(user: User, req: keg.Request, form: keg.FormData) =
    val currentPassword = form.fields.get("currentPassword").getOrElse("")
    val newPassword = form.fields.get("newPassword").getOrElse("")
    val confirmPassword = form.fields.get("confirmPassword").getOrElse("")

    userInfoRepo.findByUserId(user.id) match
      case None => keg.abort(404)
      case Some(userInfo) =>
        if !Password.compare(userInfo.passwordArgon2, currentPassword) then
          keg.Response((), 303,
            headers = Seq("location" -> "/"),
            flash = Map("error" -> "Current password is incorrect.")
          )
        else if newPassword.isEmpty then
          keg.Response((), 303,
            headers = Seq("location" -> "/"),
            flash = Map("error" -> "New password cannot be empty.")
          )
        else if newPassword != confirmPassword then
          keg.Response((), 303,
            headers = Seq("location" -> "/"),
            flash = Map("error" -> "New passwords do not match.")
          )
        else
          userInfoRepo.modify(user.id)(_.copy(passwordArgon2 = Password.hash(newPassword)))
          keg.Response((), 303,
            headers = Seq("location" -> "/"),
            flash = Map("success" -> "Password changed successfully.")
          )

  @app.post("/revoke-consent/:clientId")
  def revokeConsent(user: User, req: keg.Request, clientId: String) =
    consentRepo.delete(user.id, clientId)
    refreshTokenRepo.deleteByClientId(clientId)
    keg.Response((), 303,
      headers = Seq("location" -> "/"),
      flash = Map("success" -> "Consent revoked and tokens cleared.")
    )

  @app.post("/logout")
  def logout(user: User, req: keg.Request) =
    app.sessions.remove2(user.sess.id)
    keg.Response(
      (), 303,
      headers = Seq("Location" -> "/"),
      cookies = Seq(keg.model.Cookie("session", "", maxAge = 0, path = "/")),
      flash = Map("success" -> "Successfully logged out!")
    )

  @app.post("/login")
  def postLogin(form: keg.FormData) =
    val username: String = form.fields.get("username").getOrElse("")
    val password: String = form.fields.get("password").getOrElse("")
    val dest: String = form.fields.get("dest").getOrElse("/")

    userInfoRepo.findByName(username) match
      case Some(user) if Password.compare(user.passwordArgon2, password) =>
        UiModule.successfulLogins.inc()
        val token = oauth2.Token.random()
        val sessionId = java.util.UUID.randomUUID().toString
        val now = java.time.Instant.now()
        val session = Session(sessionId, token.sha256(), user.id, username, now, Some(now))
          app.sessions.put(token.sha256(), sessionId, session)
          keg.Response(
            (), 303,
            headers = Seq("location" -> dest),
            cookies = Seq(keg.model.Cookie("session", token.toHex, path = "/")),
            flash = Map("success" -> "Successfully logged in!")
          )
      case _ =>
        UiModule.failedLogins.inc()
        keg.Response(
          (), 303,
          headers = Seq("location" -> "/"),
          flash = Map("error" -> "Invalid username or password.")
        )

  @app.get("/sudo")
  def getSudoMode(user: User, req: keg.Request) =
    fullPage(req, "Sudo Mode")(
      h.h1("Verify it is you"),
      h.p("Enter your password to continue."),
      h.form(h.action := "/sudo", h.method := "POST", h.cls := "sudo-form")(
        h.input(h.`type` := "password", h.name := "password", h.placeholder := "Password", h.autofocus := true, h.autocomplete := "current-password"),
        h.input(h.`type` := "hidden", h.name := "dest", h.value := req.params.getOrElse("dest", "/")),
        h.button(h.`type` := "submit")("Enter Sudo Mode")
      )
    )

  @app.post("/sudo")
  def postSudoMode(user: User, req: keg.Request, form: keg.FormData) =
    val password: String = form.fields.get("password").getOrElse("")
    val dest: String = form.fields.get("dest").getOrElse("/")
    userInfoRepo.findByUserId(user.id) match
      case Some(userInfo) if Password.compare(userInfo.passwordArgon2, password) =>
        val now = java.time.Instant.now()
        app.sessions.put(
          user.sess.hash,
          user.sess.id,
          user.sess.copy(lastSudo = Some(now))
        )
        keg.Response(
          (), 303,
          headers = Seq("location" -> dest),
          flash = Map("success" -> "Sudo mode activated!")
        )
      case _ =>
        keg.Response(
          (), 303,
          headers = Seq("location" -> ("/sudo?dest=" + java.net.URLEncoder.encode(dest, "UTF-8"))),
          flash = Map("error" -> "Incorrect password.")
        )

  val routes: Seq[keg.Route] = keg.routes()
