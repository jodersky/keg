package oauth2

import scalatags.Text.all as h
import h.given
import htmx as hx



case class CanAdmin(user: User)

class AdminModule(
  app: App,
  config: Config,
  db: simplesql.DataSource,
  userInfoRepo: UserInfoRepository,
  clientRepo: ClientRepository,
):

  ustats.gauge.callback(
    "users_total",
    "Total number of users",
    Seq(),
    gauge => gauge()(userInfoRepo.getAll.size.toLong)
  )

  ustats.gauge.callback(
    "clients_total",
    "Total number of clients",
    Seq("type"),
    gauge =>
      val all = clientRepo.getAll
      val confidential = all.count(_.secret.isDefined)
      val public = all.size - confidential
      gauge("confidential")(confidential.toLong)
      gauge("public")(public.toLong)
  )

  private val refreshTokenRepo = RefreshTokenRepository(db)

  private given app.FromRequest[CanAdmin] with
    def fromRequest(request: keg.Request, ctx: app.Context) =
      summon[app.FromRequest[User]].fromRequest(request, ctx) match
        case keg.Result.Continue(u) =>
          userInfoRepo.findByUserId(u.id) match
            case Some(info) =>
              if info.roles.contains("admin") then
                keg.Result.Continue(CanAdmin(u))
              else
                keg.Result.Abort(403, "Forbidden")
            case None =>
              // User exists in token but not in repo; treat as unauthorized
              keg.Result.Abort(401, "Unauthorized")
        case keg.Result.Abort(r, m) =>
          keg.Result.Abort(r, m)
        case keg.Result.Respond(r) =>
          keg.Result.Respond(r)

  private given Conversion[h.Frag, keg.Data] = (frag: h.Frag) =>
    new keg.Data:
      lazy val rendered = frag.render
      def headers = Seq("Content-Type" -> "text/html; charset=UTF-8")
      def contentLength: Option[Long] = Some(rendered.getBytes("UTF-8").length.toLong)
      def write(out: java.io.OutputStream): Unit = out.write(rendered.getBytes("UTF-8"))


  // private given app.ToResponse[h.Frag] with
  //   def toResponse(frag: h.Frag, ctx: app.Context) =
  //     keg.Response(frag.render, 200, headers = Seq("Content-Type" -> "text/html; charset=UTF-8"))

  private def shorten(s: String): h.Frag =
    h.span(h.title:=s)(s.take(8) + "...")

  private def copyButton(text: String): h.Frag =
    h.button(
      h.cls := "copy-btn",
      h.title := "Copy to clipboard",
      h.attr("onclick") := s"navigator.clipboard.writeText('$text');this.textContent='\u2713';setTimeout(()=>this.textContent='\u2398',1500)"
    )("\u2398") // ⎘

  private val intervalPattern = """(\d+)\s*(s|m|h|d)""".r

  private def parseIntervalValue(s: String): Either[String, scala.concurrent.duration.Duration] =
    s.trim match
      case intervalPattern(n, unit) =>
        val secs = unit match
          case "s" => n.toLong
          case "m" => n.toLong * 60
          case "h" => n.toLong * 3600
          case "d" => n.toLong * 86400
        if secs > 0 then Right(scala.concurrent.duration.Duration(secs, "seconds"))
        else Left("Must be a positive duration")
      case _ => Left("E.g. 30s, 60m, 8h, 1d")

  private def formatInterval(d: scala.concurrent.duration.Duration): String =
    val secs = d.toSeconds
    if secs % 86400 == 0 then s"${secs / 86400}d"
    else if secs % 3600 == 0 then s"${secs / 3600}h"
    else if secs % 60 == 0 then s"${secs / 60}m"
    else s"${secs}s"

  private val typeInfo =
    """|Confidential clients are applications for which a shared secret between the
       |server and client has been provisioned out-of-band. They are typically
       |server-side applications.
       |
       |Public clients are applications that don't have a unique pre-shared secret.
       |They are typically applications that run on a user's device, such as mobile
       |apps, desktop apps, single-page web apps, or command-line tools.
       |
       |Public clients come with a few additional requirements and limitations:
       |- they MUST use the authorization code flow with PKCE (otherwise
       |  authorization will be rejected)
       |- even after consent has been granted, any re-authorization will require
       |  explicit user confirmation in order to prevent the "silent impersonation"
       |  attack, where a malicious website initiates an OAuth flow with a public
       |  client's client_id and tricks the user into authorizing it, thereby
       |  granting the malicious website access to the user's data on the client.""".stripMargin

  private val verifyIntervalInfo =
    """|Requires the user to verify themselves (password, yubikey, etc) after this
       |interval.
       |
       |This interval is tracked per user session (or device in device code flow),
       |not per client. For example, if the user has last verified themselves 1 hour
       |ago by authorizing client A on their laptop, they won't need to verify
       |themselves again to authorize client B on the same laptop, provided it has
       |an interval greater than 1 hour, but they will need to verify themselves to
       |authorize any client on their phone.
       |
       |This notably has the following implications:
       |- refresh tokens issued using the device code flow will be only valid for
       |  this duration
       |- refresh tokens issued using other flows (authorization code flow, or
       |  refresh token flow itself) will be bound to the user's session on the
       |  device that issued the original authorization, meaning that logging out of
       |  that session also revokes the corresponding refresh tokens.
       |
       |This field is optional. If not set, refresh tokens will be valid until
       |revoked.
       |
       |This field is only intended for clients that use this service as a
       |single-sign-on provider. Other clients, which expect a user to delegate
       |access once and then rely on refresh tokens for long-term access, should not
       |set this field.""".stripMargin

  @app.get("/admin/clients")
  def clientsPage(req: keg.Request, canAdmin: CanAdmin) =
    page(req, "Clients", canAdmin.user.name)(
      h.h1("Clients"),
      h.div(h.cls := "item-list")(
        if clientRepo.getAll.isEmpty then
          h.div(h.cls := "item-list-empty")("No clients yet.")
        else
          clientRepo.getAll.map: client =>
            h.a(h.href := s"/admin/clients/${client.id}", h.cls := "item-row")(
              h.span(h.cls := "item-row-main")(
                h.span(h.cls := "item-row-name")(
                  if client.displayName.nonEmpty then client.displayName else h.em("Unnamed")
                ),
                h.span(h.cls := "item-row-id")(client.id)
              ),
              h.span(h.cls := s"item-badge ${if client.secret.isDefined then "badge-confidential" else "badge-public"}")(
                if client.secret.isDefined then "\uD83D\uDD12 Confidential" else "\uD83C\uDF10 Public"
              )
            )
      ),
      h.button(
        h.attr("onclick") := "document.getElementById('create-client-dialog').showModal()"
      )("Create new client"),
      h.tag("dialog")(h.id := "create-client-dialog", h.cls := "confirm-dialog")(
        h.h2("New Client"),
        h.form(h.method := "post", h.action := "/admin/clients")(
          h.div(h.cls := "form-field")(
            h.label("Display Name"),
            h.input(h.`type` := "text", h.name := "displayName", h.placeholder := "My App", h.autofocus := true)
          ),
          h.div(h.cls := "form-field")(
            h.label("Type"),
            h.tag("select")(h.name := "type")(
              h.tag("option")(h.value := "confidential")("Confidential"),
              h.tag("option")(h.value := "public")("Public")
            )
          ),
          h.div(h.cls := "confirm-dialog-actions")(
            h.button(h.`type` := "button",
              h.attr("onclick") := "document.getElementById('create-client-dialog').close()"
            )("Cancel"),
            h.button(h.`type` := "submit")("Create")
          )
        )
      )
    )

  @app.get("/admin/users")
  def usersPage(req: keg.Request, canAdmin: CanAdmin) =
    page(req, "Users", canAdmin.user.name)(
      h.h1("Users"),
      h.div(h.cls := "item-list")(
        if userInfoRepo.getAll.isEmpty then
          h.div(h.cls := "item-list-empty")("No users yet.")
        else
          userInfoRepo.getAll.map: user =>
            h.a(h.href := s"/admin/users/${user.id}", h.cls := "item-row")(
              h.span(h.cls := "item-row-main")(
                h.span(h.cls := "item-row-name")(user.name),
                h.span(h.cls := "item-row-id")(user.id)
              ),
              h.span(h.cls := "item-row-meta")(
                if user.roles.contains("admin") then
                  h.span(h.cls := "item-badge badge-admin")("\uD83D\uDEE1\uFE0F Admin")
                else (),
                if user.email.nonEmpty then h.span(h.cls := "item-row-email")(user.email)
                else ()
              )
            )
      ),
      h.button(
        h.attr("onclick") := "document.getElementById('create-user-dialog').showModal()"
      )("Create new user"),
      h.tag("dialog")(h.id := "create-user-dialog", h.cls := "confirm-dialog")(
        h.h2("New User"),
        h.form(h.method := "post", h.action := "/admin/users")(
          h.div(h.cls := "form-field")(
            h.label("Username"),
            h.input(h.`type` := "text", h.name := "username", h.placeholder := "jdoe", h.autofocus := true)
          ),
          h.div(h.cls := "form-field")(
            h.label("Password"),
            h.input(h.`type` := "password", h.name := "password")
          ),
          h.div(h.cls := "confirm-dialog-actions")(
            h.button(h.`type` := "button",
              h.attr("onclick") := "document.getElementById('create-user-dialog').close()"
            )("Cancel"),
            h.button(h.`type` := "submit")("Create")
          )
        )
      )
    )

  @app.post("/admin/users")
  def postCreateUser(req: keg.Request, form: keg.FormData, canAdmin: CanAdmin) =
    val username = form.fields.get("username").getOrElse("").trim
    val password = form.fields.get("password").getOrElse("")
    if username.isEmpty || password.isEmpty then
      keg.Response(
        (), 303,
        headers = Seq("location" -> "/admin/users"),
        flash = Map("error" -> "Username and password are required.")
      )
    else if userInfoRepo.findByName(username).isDefined then
      keg.Response(
        (), 303,
        headers = Seq("location" -> "/admin/users"),
        flash = Map("error" -> s"A user named '$username' already exists.")
      )
    else
      val user = UserInfo(
        id = java.util.UUID.randomUUID().toString,
        name = username,
        email = "",
        passwordArgon2 = Password.hash(password),
        roles = Seq.empty,
        extraClaims = Map.empty
      )
      userInfoRepo.create(user)
      keg.Response(
        (), 303,
        headers = Seq("location" -> s"/admin/users/${user.id}"),
        flash = Map("success" -> s"User '$username' created.")
      )

  @app.get("/admin/users/:id")
  def userDetail(req: keg.Request, canAdmin: CanAdmin, id: String) =
    val user = userInfoRepo.findByUserId(id) match
      case Some(u) => u
      case None => keg.abort(404)

    page(req, s"User: ${user.name}", canAdmin.user.name)(
      h.h1(user.name),
      h.dl(h.cls := "detail-list")(
        h.dt("User ID"),
        h.dd(h.code(user.id), copyButton(user.id)),

        h.dt("Username"),
        h.dd(user.name),

        h.dt("Email"),
        h.dd(userFieldDisplay(user.id, "email", user.email)),

        h.dt("Roles"),
        h.dd(userFieldDisplay(user.id, "roles", user.roles.mkString(", "))),

        h.dt("Extra Claims"),
        h.dd(userFieldDisplay(user.id, "extraClaims", user.extraClaims.map { case (k, v) => s"$k=$v" }.mkString(", ")))
      ),
      h.div(h.cls := "danger-zone")(
        h.button(
          h.attr("onclick") := "document.getElementById('confirm-reset-pw-dialog').showModal()"
        )("Reset Password"),
        h.button(
          h.cls := "confirm-dialog-danger",
          h.attr("onclick") := "document.getElementById('confirm-delete-user-dialog').showModal()"
        )("Delete User")
      ),
      h.tag("dialog")(h.id := "confirm-reset-pw-dialog", h.cls := "confirm-dialog")(
        h.h2("Reset Password"),
        h.form(h.method := "post", h.action := s"/admin/users/${user.id}/reset-password")(
          h.div(h.cls := "form-field")(
            h.label("New Password"),
            h.input(h.`type` := "password", h.name := "password", h.autofocus := true)
          ),
          h.div(h.cls := "confirm-dialog-actions")(
            h.button(h.`type` := "button",
              h.attr("onclick") := "document.getElementById('confirm-reset-pw-dialog').close()"
            )("Cancel"),
            h.button(h.`type` := "submit")("Reset")
          )
        )
      ),
      h.tag("dialog")(h.id := "confirm-delete-user-dialog", h.cls := "confirm-dialog")(
        h.p(s"Permanently delete user '${user.name}'? This cannot be undone."),
        h.div(h.cls := "confirm-dialog-actions")(
          h.button(
            h.attr("onclick") := "document.getElementById('confirm-delete-user-dialog').close()"
          )("Cancel"),
          h.form(h.method := "post", h.action := s"/admin/users/${user.id}/delete")(
            h.button(h.`type` := "submit", h.cls := "confirm-dialog-danger")("Delete")
          )
        )
      )
    )

  private def userFieldDisplay(userId: String, field: String, value: String): h.Frag =
    h.span(
      h.cls := "editable",
      h.tabindex := 0,
      hx.get := s"/admin/users/$userId/edit/$field",
      hx.swap := "outerHTML",
      hx.trigger := "click, keydown[key=='Enter']"
    )(if value.nonEmpty then value else h.em("empty"))

  private def userFieldEdit(userId: String, field: String, value: String): h.Frag =
    h.span(h.cls := "field-edit-group")(
      h.input(
        h.`type` := "text",
        h.name := field,
        h.value := value,
        h.cls := "field-edit",
        h.autofocus := true, h.attr("onfocus") := "this.setSelectionRange(this.value.length,this.value.length)",
        hx.patch := s"/admin/users/$userId",
        hx.swap := "outerHTML",
        hx.target := "closest .field-edit-group",
        hx.trigger := "keydown[key=='Enter']",
        h.attr("onkeydown") := s"if(event.key==='Escape'){htmx.ajax('GET','/admin/users/$userId/display/$field',{target:this.closest('.field-edit-group'),swap:'outerHTML'})}"
      ),
      h.button(h.cls := "edit-accept", h.title := "Save",
        h.attr("onclick") := "this.previousElementSibling.dispatchEvent(new KeyboardEvent('keydown',{key:'Enter',bubbles:true}))"
      )("\u2713"),
      h.button(h.cls := "edit-discard", h.title := "Discard",
        h.attr("onclick") := s"htmx.ajax('GET','/admin/users/$userId/display/$field',{target:this.closest('.field-edit-group'),swap:'outerHTML'})"
      )("\u2717")
    )

  @app.get("/admin/users/:id/display/:field")
  def userFieldDisplayView(canAdmin: CanAdmin, id: String, field: String) =
    val user = userInfoRepo.findByUserId(id) match
      case Some(u) => u
      case None => keg.abort(404)
    val value = field match
      case "email" => user.email
      case "roles" => user.roles.mkString(", ")
      case "extraClaims" => user.extraClaims.map { case (k, v) => s"$k=$v" }.mkString(", ")
      case _ => keg.abort(400)
    userFieldDisplay(id, field, value)

  @app.get("/admin/users/:id/edit/:field")
  def userFieldEditView(canAdmin: CanAdmin, id: String, field: String) =
    val user = userInfoRepo.findByUserId(id) match
      case Some(u) => u
      case None => keg.abort(404)
    val value = field match
      case "email" => user.email
      case "roles" => user.roles.mkString(", ")
      case "extraClaims" => user.extraClaims.map { case (k, v) => s"$k=$v" }.mkString(", ")
      case _ => keg.abort(400)
    userFieldEdit(id, field, value)

  @app.patch("/admin/users/:id")
  def userFieldPatch(canAdmin: CanAdmin, id: String, form: keg.FormData) =
    val (field, value) = form.fields.head
    val updated = userInfoRepo.modify(id): user =>
      field match
        case "email" => user.copy(email = value.trim)
        case "roles" => user.copy(roles = value.split(",").map(_.trim).filter(_.nonEmpty).toSeq)
        case "extraClaims" =>
          val claims = value.split(",").map(_.trim).filter(_.nonEmpty).flatMap: entry =>
            entry.split("=", 2) match
              case Array(k, v) => Some(k.trim -> v.trim)
              case _ => None
          .toMap
          user.copy(extraClaims = claims)
        case _ => keg.abort(400)
    updated match
      case Some(user) =>
        val displayValue = field match
          case "email" => user.email
          case "roles" => user.roles.mkString(", ")
          case "extraClaims" => user.extraClaims.map { case (k, v) => s"$k=$v" }.mkString(", ")
          case _ => ""
        userFieldDisplay(id, field, displayValue)
      case None => keg.abort(404)

  @app.post("/admin/users/:id/reset-password")
  def resetPassword(req: keg.Request, canAdmin: CanAdmin, id: String, form: keg.FormData) =
    val password = form.fields.get("password").getOrElse("")
    if password.isEmpty then
      keg.Response(
        (), 303,
        headers = Seq("location" -> s"/admin/users/$id"),
        flash = Map("error" -> "Password cannot be empty.")
      )
    else
      userInfoRepo.modify(id)(_.copy(passwordArgon2 = Password.hash(password))) match
        case Some(_) =>
          keg.Response(
            (), 303,
            headers = Seq("location" -> s"/admin/users/$id"),
            flash = Map("success" -> "Password has been reset.")
          )
        case None => keg.abort(404)

  @app.post("/admin/users/:id/delete")
  def deleteUser(req: keg.Request, canAdmin: CanAdmin, id: String) =
    userInfoRepo.findByUserId(id) match
      case None => keg.abort(404)
      case Some(user) =>
        userInfoRepo.delete(id)
        keg.Response(
          (), 303,
          headers = Seq("location" -> "/admin/users"),
          flash = Map("success" -> s"User '${user.name}' deleted.")
        )

  @app.post("/admin/clients")
  def postCreateClient(req: keg.Request, form: keg.FormData, canAdmin: CanAdmin) =
    val id = java.util.UUID.randomUUID().toString
    val displayName = form.fields.get("displayName").getOrElse("")
    val isPublic = form.fields.get("type").contains("public")
    val secret = if isPublic then None else Some(Token.random())
    val client = Client(id, secret.map(_.sha256()), Set.empty, None, displayName)
    if clientRepo.create(client) then
      val flash =
        if isPublic then Map("success" -> s"Client created.")
        else Map("success" -> s"Client created. Secret: ${secret.get.toHex} — copy it now, it won't be shown again.")
      keg.Response(
        (), 303,
        headers = Seq("location" -> s"/admin/clients/$id"),
        flash = flash
      )
    else
      keg.Response(
        (), 303,
        headers = Seq("location" -> "/admin/clients"),
        flash = Map("error" -> "Failed to create client.")
      )

  private def fieldError(clientId: String, field: String, value: String, error: String): h.Frag =
    h.span(h.cls := "field-edit-group")(
      h.input(
        h.`type` := "text",
        h.name := field,
        h.value := value,
        h.cls := "field-edit field-edit-invalid",
        h.autofocus := true, h.attr("onfocus") := "this.setSelectionRange(this.value.length,this.value.length)",
        hx.patch := s"/admin/clients/$clientId",
        hx.swap := "outerHTML",
        hx.target := "closest .field-edit-group",
        hx.trigger := "keydown[key=='Enter']",
        h.attr("onkeydown") := s"if(event.key==='Escape'){htmx.ajax('GET','/admin/clients/$clientId/display/$field',{target:this.closest('.field-edit-group'),swap:'outerHTML'})}"
      ),
      h.button(h.cls := "edit-accept", h.title := "Save",
        h.attr("onclick") := "this.previousElementSibling.dispatchEvent(new KeyboardEvent('keydown',{key:'Enter',bubbles:true}))"
      )("\u2713"),
      h.button(h.cls := "edit-discard", h.title := "Discard",
        h.attr("onclick") := s"htmx.ajax('GET','/admin/clients/$clientId/display/$field',{target:this.closest('.field-edit-group'),swap:'outerHTML'})"
      )("\u2717"),
      h.span(h.cls := "field-error-msg")(error)
    )

  // Renders a field as plain text; clicking it swaps in an edit input
  private def fieldDisplay(clientId: String, field: String, value: String): h.Frag =
    h.span(
      h.cls := "editable",
      h.tabindex := 0,
      hx.get := s"/admin/clients/$clientId/edit/$field",
      hx.swap := "outerHTML",
      hx.trigger := "click, keydown[key=='Enter']"
    )(if value.nonEmpty then value else h.em("empty"))

  // Renders an edit input with accept/discard buttons.
  private def fieldEdit(clientId: String, field: String, value: String): h.Frag =
    h.span(h.cls := "field-edit-group")(
      h.input(
        h.`type` := "text",
        h.name := field,
        h.value := value,
        h.cls := "field-edit",
        h.autofocus := true, h.attr("onfocus") := "this.setSelectionRange(this.value.length,this.value.length)",
        hx.patch := s"/admin/clients/$clientId",
        hx.swap := "outerHTML",
        hx.target := "closest .field-edit-group",
        hx.trigger := "keydown[key=='Enter']",
        h.attr("onkeydown") := s"if(event.key==='Escape'){htmx.ajax('GET','/admin/clients/$clientId/display/$field',{target:this.closest('.field-edit-group'),swap:'outerHTML'})}"
      ),
      h.button(h.cls := "edit-accept", h.title := "Save",
        h.attr("onclick") := "this.previousElementSibling.dispatchEvent(new KeyboardEvent('keydown',{key:'Enter',bubbles:true}))"
      )("\u2713"), // ✓
      h.button(h.cls := "edit-discard", h.title := "Discard",
        h.attr("onclick") := s"htmx.ajax('GET','/admin/clients/$clientId/display/$field',{target:this.closest('.field-edit-group'),swap:'outerHTML'})"
      )("\u2717") // ✗
    )

  private def encode(s: String) = java.net.URLEncoder.encode(s, "UTF-8")

  private def redirectUriItem(clientId: String, uri: String): h.Frag =
    h.li(h.cls := "uri-item")(
      h.span(
        h.cls := "editable",
        h.tabindex := 0,
        hx.get := s"/admin/clients/$clientId/redirect-uris/edit?uri=${encode(uri)}",
        hx.swap := "outerHTML",
        hx.target := "closest .uri-item",
        hx.trigger := "click, keydown[key=='Enter']"
      )(uri),
      h.button(
        h.cls := "uri-delete",
        hx.delete := s"/admin/clients/$clientId/redirect-uris?uri=${encode(uri)}",
        hx.swap := "delete swap:0.3s",
        hx.target := "closest .uri-item"
      )("\u00d7") // ×
    )

  private def redirectUriEditItem(clientId: String, uri: String): h.Frag =
    h.li(h.cls := "uri-item")(
      h.input(
        h.`type` := "text",
        h.name := "uri",
        h.value := uri,
        h.cls := "field-edit",
        h.autofocus := true, h.attr("onfocus") := "this.setSelectionRange(this.value.length,this.value.length)",
        hx.patch := s"/admin/clients/$clientId/redirect-uris?old=${encode(uri)}",
        hx.swap := "outerHTML",
        hx.target := "closest .uri-item",
        hx.trigger := "keydown[key=='Enter']",
        h.attr("onkeydown") := s"if(event.key==='Escape'){htmx.ajax('GET','/admin/clients/$clientId/redirect-uris/item?uri=${encode(uri)}',{target:this.closest('.uri-item'),swap:'outerHTML'})}"
      ),
      h.button(h.cls := "edit-accept", h.title := "Save",
        h.attr("onclick") := "this.closest('.uri-item').querySelector('.field-edit').dispatchEvent(new KeyboardEvent('keydown',{key:'Enter',bubbles:true}))"
      )("\u2713"),
      h.button(h.cls := "edit-discard", h.title := "Discard",
        h.attr("onclick") := s"htmx.ajax('GET','/admin/clients/$clientId/redirect-uris/item?uri=${encode(uri)}',{target:this.closest('.uri-item'),swap:'outerHTML'})"
      )("\u2717")
    )

  private def redirectUriAddItem(clientId: String): h.Frag =
    h.li(h.cls := "uri-item")(
      h.input(
        h.`type` := "text",
        h.name := "uri",
        h.value := "",
        h.cls := "field-edit",
        h.autofocus := true, h.attr("onfocus") := "this.setSelectionRange(this.value.length,this.value.length)",
        h.placeholder := "https://example.com/callback",
        hx.post := s"/admin/clients/$clientId/redirect-uris",
        hx.swap := "outerHTML",
        hx.target := "closest .uri-item",
        hx.trigger := "keydown[key=='Enter']",
        h.attr("onkeydown") := "if(event.key==='Escape'){this.closest('.uri-item').remove()}"
      ),
      h.button(h.cls := "edit-accept", h.title := "Save",
        h.attr("onclick") := "this.closest('.uri-item').querySelector('.field-edit').dispatchEvent(new KeyboardEvent('keydown',{key:'Enter',bubbles:true}))"
      )("\u2713"),
      h.button(h.cls := "edit-discard", h.title := "Discard",
        h.attr("onclick") := "this.closest('.uri-item').remove()"
      )("\u2717")
    )

  private def redirectUriErrorItem(clientId: String, uri: String, error: String, isNew: Boolean): h.Frag =
    h.li(h.cls := "uri-item")(
      h.input(
        h.`type` := "text",
        h.name := "uri",
        h.value := uri,
        h.cls := "field-edit field-edit-invalid",
        h.autofocus := true, h.attr("onfocus") := "this.setSelectionRange(this.value.length,this.value.length)",
        if isNew then hx.post := s"/admin/clients/$clientId/redirect-uris"
        else hx.patch := s"/admin/clients/$clientId/redirect-uris?old=${encode(uri)}",
        hx.swap := "outerHTML",
        hx.target := "closest .uri-item",
        hx.trigger := "keydown[key=='Enter']",
        if isNew then h.attr("onkeydown") := "if(event.key==='Escape'){this.closest('.uri-item').remove()}"
        else h.attr("onkeydown") := s"if(event.key==='Escape'){htmx.ajax('GET','/admin/clients/$clientId/redirect-uris/item?uri=${encode(uri)}',{target:this.closest('.uri-item'),swap:'outerHTML'})}"
      ),
      h.button(h.cls := "edit-accept", h.title := "Save",
        h.attr("onclick") := "this.closest('.uri-item').querySelector('.field-edit').dispatchEvent(new KeyboardEvent('keydown',{key:'Enter',bubbles:true}))"
      )("\u2713"),
      h.button(h.cls := "edit-discard", h.title := "Discard",
        if isNew then h.attr("onclick") := "this.closest('.uri-item').remove()"
        else h.attr("onclick") := s"htmx.ajax('GET','/admin/clients/$clientId/redirect-uris/item?uri=${encode(uri)}',{target:this.closest('.uri-item'),swap:'outerHTML'})"
      )("\u2717"),
      h.span(h.cls := "field-error-msg")(error)
    )

  private def redirectUriList(clientId: String, uris: Set[String]): h.Frag =
    h.div(h.cls := "uri-list-section")(
      h.ul(h.cls := "uri-list")(
        uris.toSeq.sorted.map(uri => redirectUriItem(clientId, uri))
      ),
      h.button(
        h.cls := "uri-add",
        hx.post := s"/admin/clients/$clientId/redirect-uris/add",
        hx.target := "previous .uri-list",
        hx.swap := "beforeend"
      )("+ Add URI")
    )

  @app.get("/admin/clients/:id")
  def clientDetail(req: keg.Request, canAdmin: CanAdmin, id: String) =
    val client = clientRepo.findByClientId(id) match
      case Some(c) => c
      case None => keg.abort(404)

    page(req, s"Client: ${client.displayName}", canAdmin.user.name)(
      h.h1(fieldDisplay(client.id, "displayName", client.displayName)),
      h.dl(h.cls := "detail-list")(
        h.dt("Client ID"),
        h.dd(h.code(client.id), copyButton(client.id)),

        h.dt("Redirect URIs"),
        h.dd(redirectUriList(client.id, client.redirectUris)),

        h.dt("Type"),
        h.dd(
          if client.secret.isDefined then "Confidential" else "Public",
          " ",
          h.span(h.cls := "info-icon", h.title := typeInfo)("ℹ")
        ),

        if client.secret.isDefined then h.frag(
          h.dt("Secret"),
          h.dd(secretField(client.id))
        ) else (),

        h.dt("Verify Interval"),
        h.dd(fieldDisplay(client.id, "verifyInterval", client.verifyInterval.map(formatInterval).getOrElse("")))
      ),
      h.div(h.cls := "danger-zone")(
        h.button(
          h.attr("onclick") := "document.getElementById('confirm-revoke-dialog').showModal()"
        )("Revoke All Tokens"),
        h.button(
          h.cls := "confirm-dialog-danger",
          h.attr("onclick") := "document.getElementById('confirm-delete-dialog').showModal()"
        )("Delete Client")
      ),
      h.tag("dialog")(h.id := "confirm-revoke-dialog", h.cls := "confirm-dialog")(
        h.p("Revoke all refresh tokens for this client? Users will need to re-authorize."),
        h.div(h.cls := "confirm-dialog-actions")(
          h.button(
            h.attr("onclick") := "document.getElementById('confirm-revoke-dialog').close()"
          )("Cancel"),
          h.form(h.method := "post", h.action := s"/admin/clients/${client.id}/revoke-all")(
            h.button(h.`type` := "submit", h.cls := "confirm-dialog-danger")("Revoke All")
          )
        )
      ),
      h.tag("dialog")(h.id := "confirm-delete-dialog", h.cls := "confirm-dialog")(
        h.p("Permanently delete this client and all its tokens? This cannot be undone."),
        h.div(h.cls := "confirm-dialog-actions")(
          h.button(
            h.attr("onclick") := "document.getElementById('confirm-delete-dialog').close()"
          )("Cancel"),
          h.form(h.method := "post", h.action := s"/admin/clients/${client.id}/delete")(
            h.button(h.`type` := "submit", h.cls := "confirm-dialog-danger")("Delete")
          )
        )
      )

    )

  private def secretField(clientId: String, revealed: Option[String] = None): h.Frag =
    val dialogId = "confirm-rotate-secret"
    h.span(h.cls := "secret-field")(
      revealed match
        case Some(secret) =>
          h.span(h.cls := "flash success secret-revealed")("New secret: ", h.code(secret), copyButton(secret), " — copy it now, it won't be shown again.")
        case None =>
          h.span("****")
      ,
      h.button(
        h.cls := "secret-rotate",
        h.attr("onclick") := s"document.getElementById('$dialogId').showModal()"
      )("\u21bb"), // ↻
      h.tag("dialog")(h.id := dialogId, h.cls := "confirm-dialog")(
        h.p("Generate a new client secret? The old secret will stop working immediately."),
        h.div(h.cls := "confirm-dialog-actions")(
          h.button(
            h.attr("onclick") := s"document.getElementById('$dialogId').close()"
          )("Cancel"),
          h.button(
            h.cls := "confirm-dialog-danger",
            hx.post := s"/admin/clients/$clientId/rotate-secret",
            hx.target := s"closest .secret-field",
            hx.swap := "outerHTML",
            h.attr("onclick") := s"document.getElementById('$dialogId').close()"
          )("Confirm")
        )
      )
    )

  @app.get("/admin/clients/:id/display/:field")
  def clientFieldDisplay(canAdmin: CanAdmin, id: String, field: String) =
    val client = clientRepo.findByClientId(id) match
      case Some(c) => c
      case None => keg.abort(404)

    val value = field match
      case "displayName" => client.displayName
      case "verifyInterval" => client.verifyInterval.map(formatInterval).getOrElse("")
      case _ => keg.abort(400)

    fieldDisplay(client.id, field, value)

  @app.get("/admin/clients/:id/edit/:field")
  def clientFieldEdit(canAdmin: CanAdmin, id: String, field: String) =
    val client = clientRepo.findByClientId(id) match
      case Some(c) => c
      case None => keg.abort(404)

    val value = field match
      case "displayName" => client.displayName
      case "verifyInterval" => client.verifyInterval.map(formatInterval).getOrElse("")
      case _ => keg.abort(400)

    fieldEdit(client.id, field, value)

  @app.patch("/admin/clients/:id")
  def clientFieldPatch(canAdmin: CanAdmin, id: String, form: keg.FormData) =
    val (field, value) = form.fields.head

    def parseInterval(s: String): Either[String, Option[scala.concurrent.duration.Duration]] =
      s.trim match
        case "" => Right(None)
        case v => parseIntervalValue(v).map(Some(_))

    field match
      case "verifyInterval" =>
        parseInterval(value) match
          case Left(err) => fieldError(id, field, value, err)
          case Right(interval) =>
            clientRepo.modify(id)(_.copy(verifyInterval = interval)) match
              case Some(client) => fieldDisplay(client.id, field, client.verifyInterval.map(formatInterval).getOrElse(""))
              case None => keg.abort(404)
      case _ =>
        val updated = clientRepo.modify(id): client =>
          field match
            case "displayName" => client.copy(displayName = value)
            case _ => keg.abort(400)

        updated match
          case Some(client) =>
            val displayValue = field match
              case "displayName" => client.displayName
              case _ => ""
            fieldDisplay(client.id, field, displayValue)
          case None => keg.abort(404)

  private def isValidUri(s: String): Boolean =
    try
      val uri = new java.net.URI(s)
      uri.getScheme != null && uri.getHost != null
    catch case _: Exception => false

  @app.get("/admin/clients/:id/redirect-uris/item")
  def redirectUriItemView(canAdmin: CanAdmin, id: String, req: keg.Request) =
    val uri = req.params.getOrElse("uri", "")
    redirectUriItem(id, uri)

  @app.get("/admin/clients/:id/redirect-uris/edit")
  def redirectUriEditView(canAdmin: CanAdmin, id: String, req: keg.Request) =
    val uri = req.params.getOrElse("uri", "")
    redirectUriEditItem(id, uri)

  @app.post("/admin/clients/:id/redirect-uris/add")
  def redirectUriAddView(canAdmin: CanAdmin, id: String) =
    redirectUriAddItem(id)

  @app.post("/admin/clients/:id/redirect-uris")
  def redirectUriCreate(canAdmin: CanAdmin, id: String, form: keg.FormData) =
    val uri = form.fields.getOrElse("uri", "").trim
    if uri.isEmpty then
      h.raw("")
    else if !isValidUri(uri) then
      redirectUriErrorItem(id, uri, "Must be a valid URI (e.g. https://example.com/callback)", isNew = true)
    else
      clientRepo.modify(id)(c => c.copy(redirectUris = c.redirectUris + uri))
      redirectUriItem(id, uri)

  @app.patch("/admin/clients/:id/redirect-uris")
  def redirectUriUpdate(canAdmin: CanAdmin, id: String, req: keg.Request, form: keg.FormData) =
    val old = req.params.getOrElse("old", "")
    val uri = form.fields.getOrElse("uri", "").trim
    if uri.isEmpty then
      clientRepo.modify(id)(c => c.copy(redirectUris = c.redirectUris - old))
      h.raw("")
    else if !isValidUri(uri) then
      redirectUriErrorItem(id, uri, "Must be a valid URI (e.g. https://example.com/callback)", isNew = false)
    else
      clientRepo.modify(id)(c => c.copy(redirectUris = c.redirectUris - old + uri))
      redirectUriItem(id, uri)

  @app.delete("/admin/clients/:id/redirect-uris")
  def redirectUriDelete(canAdmin: CanAdmin, id: String, req: keg.Request) =
    val uri = req.params.getOrElse("uri", "")
    clientRepo.modify(id)(c => c.copy(redirectUris = c.redirectUris - uri))
    h.raw("")

  @app.post("/admin/clients/:id/rotate-secret")
  def rotateSecret(canAdmin: CanAdmin, id: String) =
    val newSecret = Token.random()
    clientRepo.modify(id)(_.copy(secret = Some(newSecret.sha256()))) match
      case Some(_) => secretField(id, Some(newSecret.toHex))
      case None => keg.abort(404)


  @app.post("/admin/clients/:id/revoke-all")
  def revokeAllTokens(req: keg.Request, canAdmin: CanAdmin, id: String) =
    clientRepo.findByClientId(id) match
      case None => keg.abort(404)
      case Some(_) =>
        refreshTokenRepo.deleteByClientId(id)
        keg.Response(
          (), 303,
          headers = Seq("location" -> s"/admin/clients/$id"),
          flash = Map("success" -> "All refresh tokens have been revoked.")
        )

  @app.post("/admin/clients/:id/delete")
  def deleteClient(req: keg.Request, canAdmin: CanAdmin, id: String) =
    clientRepo.findByClientId(id) match
      case None => keg.abort(404)
      case Some(_) =>
        refreshTokenRepo.deleteByClientId(id)
        clientRepo.delete(id)
        keg.Response(
          (), 303,
          headers = Seq("location" -> "/admin/clients"),
          flash = Map("success" -> "Client deleted.")
        )

  val routes = keg.routes()
