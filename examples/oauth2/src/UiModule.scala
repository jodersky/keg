package oauth2

import scalatags.Text.all as h
import scalatags.Text.tags2.nav
import h.given

object UiModule:
  val failedLogins = ustats.counter.simple("logins_failed", "Number of failed login attempts")
  val successfulLogins = ustats.counter.simple("logins_successful", "Number of successful login attempts")

// user interface module, contains routes for login/logout and serving static assets
class UiModule(app: App, config: Config, db: simplesql.DataSource, userInfoRepo: UserInfoRepository, clientRepo: ClientRepository, webAuthn: WebAuthnService):
  UiModule // initialize the companion object to register metrics

  private val consentRepo = ConsentRepository(db)

  // Inline JavaScript for the security-key registration ceremony (account page)
  private val securityKeyJs = """
    (function(){
      const b64 = {
        decode: s => {
          const t = s.replace(/-/g,'+').replace(/_/g,'/');
          return Uint8Array.from(atob(t+'='.repeat((4-t.length%4)%4)),c=>c.charCodeAt(0)).buffer;
        },
        encode: buf =>
          btoa(String.fromCharCode(...new Uint8Array(buf)))
            .replace(/\+/g,'-').replace(/\//g,'_').replace(/=/g,'')
      };
      window.registerKey = async function() {
        const st = document.getElementById('yubikey-status');
        st.textContent = 'Waiting for security key\u2026';
        try {
          const r1 = await fetch('/webauthn/register/begin',{method:'POST'});
          if(!r1.ok){st.textContent='Error: '+await r1.text();return;}
          const opts = await r1.json();
          const cred = await navigator.credentials.create({publicKey:{
            ...opts,
            challenge: b64.decode(opts.challenge),
            user: {...opts.user, id: b64.decode(opts.user.id)},
            excludeCredentials: (opts.excludeCredentials||[]).map(c=>({...c,id:b64.decode(c.id)}))
          }});
          const r2 = await fetch('/webauthn/register/finish',{
            method:'POST', headers:{'Content-Type':'application/json'},
            body: JSON.stringify({
              id: cred.id, rawId: b64.encode(cred.rawId), type: cred.type,
              response:{
                clientDataJSON:    b64.encode(cred.response.clientDataJSON),
                attestationObject: b64.encode(cred.response.attestationObject)
              }, clientExtensionResults: {}
            })
          });
          if(r2.ok) location.reload();
          else st.textContent = 'Registration failed: '+await r2.text();
        } catch(e) {
          st.textContent = e.name!=='NotAllowedError' ? 'Error: '+e.message : '';
        }
      };
    })();
  """
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
      val creds = userInfoRepo.findByUserId(user.get.id)
        .map(_.webAuthnCredentials).getOrElse(Seq.empty)
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
          ),

        h.h2("Security Keys"),
        if creds.isEmpty then
          h.p(h.cls := "text-muted")("No security keys registered.")
        else
          h.div(h.cls := "item-list")(
            creds.map: cred =>
              h.div(h.cls := "item-row")(
                h.span(h.cls := "item-row-main")(
                  h.span(h.cls := "item-row-name")("Security Key"),
                  h.span(h.cls := "item-row-id")(
                    s"Added ${cred.addedAt.take(10)} \u00b7 ID: ${cred.id.take(8)}\u2026"
                  )
                ),
                h.form(h.action := s"/yubikey/remove/${cred.id}", h.method := "POST")(
                  h.button(h.`type` := "submit", h.cls := "confirm-dialog-danger")("Remove")
                )
              )
          ),
        h.button(h.onclick := "registerKey()")("Add Security Key"),
        h.p(h.id := "yubikey-status", h.cls := "text-muted")(""),
        h.script(h.raw(securityKeyJs))
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
        if user.webAuthnCredentials.nonEmpty then
          // First factor OK — require security key as second factor
          val pendingToken = oauth2.Token.random().toHex
          app.pendingYubikeyLogins.put(pendingToken, PendingYubikeyLogin(user.id, user.name, dest))
          keg.Response(
            (), 303,
            headers = Seq("location" -> "/login/yubikey"),
            cookies = Seq(keg.model.Cookie("pending_login", pendingToken, path = "/"))
          )
        else
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

  // ── Security-key challenge page (shown after password OK) ─────────────────

  @app.get("/login/yubikey")
  def getYubikeyChallenge(req: keg.Request): keg.RawResponse =
    val pendingToken = req.cookies.get("pending_login").map(_.value).getOrElse("")
    if pendingToken.isEmpty || app.pendingYubikeyLogins.get(pendingToken).isEmpty then
      keg.Response((), 303, headers = Seq("location" -> "/"))
    else
      val authJs = """
        (function(){
          const b64 = {
            decode: s => {
              const t = s.replace(/-/g,'+').replace(/_/g,'/');
              return Uint8Array.from(atob(t+'='.repeat((4-t.length%4)%4)),c=>c.charCodeAt(0)).buffer;
            },
            encode: buf =>
              btoa(String.fromCharCode(...new Uint8Array(buf)))
                .replace(/\+/g,'-').replace(/\//g,'_').replace(/=/g,'')
          };
          async function authenticate() {
            const st = document.getElementById('yubikey-status');
            st.textContent = 'Waiting for security key\u2026';
            try {
              const r1 = await fetch('/webauthn/authenticate/begin',{method:'POST'});
              if(!r1.ok){st.textContent='Error: '+await r1.text();return;}
              const raw = await r1.json();
              const pk = raw.publicKey || raw;
              const assertion = await navigator.credentials.get({publicKey:{
                ...pk,
                challenge: b64.decode(pk.challenge),
                allowCredentials: (pk.allowCredentials||[]).map(c=>({...c,id:b64.decode(c.id)}))
              }});
              const r2 = await fetch('/webauthn/authenticate/finish',{
                method:'POST', headers:{'Content-Type':'application/json'},
                body: JSON.stringify({
                  id: assertion.id, rawId: b64.encode(assertion.rawId), type: assertion.type,
                  response:{
                    authenticatorData: b64.encode(assertion.response.authenticatorData),
                    clientDataJSON:    b64.encode(assertion.response.clientDataJSON),
                    signature:         b64.encode(assertion.response.signature),
                    userHandle:        assertion.response.userHandle
                                         ? b64.encode(assertion.response.userHandle) : null
                  }, clientExtensionResults: {}
                })
              });
              if(r2.ok){const d=await r2.json();window.location.href=d.redirect;}
              else st.textContent='Authentication failed: '+await r2.text();
            } catch(e) {
              st.textContent = e.name!=='NotAllowedError' ? 'Error: '+e.message : 'Cancelled.';
            }
          }
          window.addEventListener('DOMContentLoaded', authenticate);
        })();
      """
      keg.Response(
        fullPage(req, "Security Key Required")(
          h.h1("Security Key Required"),
          h.p("Touch your security key to continue signing in."),
          h.p(h.id := "yubikey-status", h.cls := "text-muted")(""),
          h.script(h.raw(authJs))
        )
      )

  // ── WebAuthn registration ──────────────────────────────────────────────────

  @app.post("/webauthn/register/begin")
  def webAuthnRegisterBegin(user: User) =
    userInfoRepo.findByUserId(user.id) match
      case None => keg.abort(404)
      case Some(userInfo) =>
        val json = webAuthn.beginRegistration(user.sess.id, userInfo)
        keg.Response(json, 200, headers = Seq("Content-Type" -> "application/json"))

  @app.post("/webauthn/register/finish")
  def webAuthnRegisterFinish(user: User, body: String) =
    webAuthn.finishRegistration(user.sess.id, body) match
      case Left(err) => keg.Response(err, 400)
      case Right(cred) =>
        userInfoRepo.modify(user.id)(u =>
          u.copy(webAuthnCredentials = u.webAuthnCredentials :+ cred)
        )
        keg.Response("OK", 200)

  @app.post("/yubikey/remove/:credentialId")
  def removeYubikey(user: User, credentialId: String) =
    userInfoRepo.modify(user.id)(u =>
      u.copy(webAuthnCredentials = u.webAuthnCredentials.filterNot(_.id == credentialId))
    )
    keg.Response((), 303,
      headers = Seq("location" -> "/"),
      flash = Map("success" -> "Security key removed.")
    )

  // ── WebAuthn authentication (second factor during login) ───────────────────

  @app.post("/webauthn/authenticate/begin")
  def webAuthnAuthBegin(req: keg.Request) =
    val pendingToken = req.cookies.get("pending_login").map(_.value).getOrElse("")
    app.pendingYubikeyLogins.get(pendingToken) match
      case None => keg.Response("Session not found or expired", 404)
      case Some(pending) =>
        userInfoRepo.findByUserId(pending.userId) match
          case None => keg.Response("User not found", 500)
          case Some(userInfo) =>
            val json = webAuthn.beginAuthentication(pendingToken, userInfo)
            keg.Response(json, 200, headers = Seq("Content-Type" -> "application/json"))

  @app.post("/webauthn/authenticate/finish")
  def webAuthnAuthFinish(req: keg.Request, body: String): keg.RawResponse =
    val pendingToken = req.cookies.get("pending_login").map(_.value).getOrElse("")
    app.pendingYubikeyLogins.get(pendingToken) match
      case None => keg.Response("Session not found or expired", 404)
      case Some(pending) =>
        webAuthn.finishAuthentication(pendingToken, body) match
          case Left(err) => keg.Response(err, 401)
          case Right((credId, newSignCount)) =>
            // Update the stored sign count (replay-attack prevention)
            userInfoRepo.modify(pending.userId)(u =>
              u.copy(webAuthnCredentials = u.webAuthnCredentials.map(c =>
                if c.id == credId then c.copy(signCount = newSignCount) else c
              ))
            )
            app.pendingYubikeyLogins.remove(pendingToken)
            // Create the real session
            val token = oauth2.Token.random()
            val sessionId = java.util.UUID.randomUUID().toString
            val now = java.time.Instant.now()
            val session = Session(sessionId, token.sha256(), pending.userId, pending.username, now, Some(now))
            app.sessions.put(token.sha256(), sessionId, session)
            keg.Response(
              ujson.Obj("redirect" -> pending.dest),
              200,
              cookies = Seq(
                keg.model.Cookie("pending_login", "", maxAge = 0, path = "/"),
                keg.model.Cookie("session", token.toHex, path = "/")
              )
            )

  val routes: Seq[keg.Route] = keg.routes()
