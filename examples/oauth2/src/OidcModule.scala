package oauth2

import com.github.benmanes.caffeine.cache.Caffeine
import keg.url

case class AuthorizationCode(
  code_hash: HashedToken,
  clientId: String,
  userId: String,
  redirectUri: String,
  scope: Set[String],
  nonce: Option[String],
  codeChallenge: Option[String],
  sessionId: String // the session associated with browser interaction
) derives upickle.default.ReadWriter

// short-lived access tokens
case class AccessToken(
  token: String,
  clientId: String,
  userId: String,
  scope: Set[String]
) derives upickle.default.ReadWriter


class OidcModule(app: App, config: Config, db: simplesql.DataSource, userInfoRepository: UserInfoRepository, clientRepo: ClientRepository):

  val consentRepo = ConsentRepository(db)
  val refreshTokenRepo = RefreshTokenRepository(db)

  val authorizationCodes = InMemStore[HashedToken, AuthorizationCode](
    scala.concurrent.duration.Duration(10, "minutes")
  )

  val accessTokens = Caffeine.newBuilder()
    .expireAfterWrite(java.time.Duration.ofHours(1))
    .build[String, AccessToken]()

    /*

DISCOVERY = {
    "issuer": ISSUER,
    "authorization_endpoint": f"{ISSUER}/authorize",
    "token_endpoint": f"{ISSUER}/token",
    "userinfo_endpoint": f"{ISSUER}/userinfo",
    "jwks_uri": f"{ISSUER}/.well-known/jwks.json",
    "response_types_supported": ["code"],
    "subject_types_supported": ["public"],
    "id_token_signing_alg_values_supported": ["ES256"],
    "scopes_supported": ["openid", "profile", "email", "groups"],
    "token_endpoint_auth_methods_supported": ["client_secret_basic", "client_secret_post", "none"],
    "code_challenge_methods_supported": ["S256"],
    "claims_supported": ["sub", "iss", "aud", "exp", "iat", "name", "email", "preferred_username", "groups"],
    "device_authorization_endpoint": f"{ISSUER}/device_authorization",
    "grant_types_supported": ["authorization_code", "refresh_token", "urn:ietf:params:oauth:grant-type:device_code"],
}*/

  @app.get("/.well-known/openid-configuration")
  def getOpenIdConfiguration() =
    val issuer = config.server.issuer
    ujson.Obj(
      "issuer" -> issuer,
      "authorization_endpoint" -> s"$issuer/authorize",
      "token_endpoint" -> s"$issuer/token",
      "userinfo_endpoint" -> s"$issuer/userinfo",
      "jwks_uri" -> s"$issuer/.well-known/jwks.json",
      "response_types_supported" -> ujson.Arr("code"),
      "subject_types_supported" -> ujson.Arr("public"),
      "id_token_signing_alg_values_supported" -> ujson.Arr("ES256"),
      "scopes_supported" -> ujson.Arr("openid", "profile", "email", "roles"),
      "token_endpoint_auth_methods_supported" -> ujson.Arr("client_secret_basic", "client_secret_post", "none"),
      "code_challenge_methods_supported" -> ujson.Arr("S256"),
      "claims_supported" -> ujson.Arr("sub", "iss", "aud", "exp", "iat", "name", "email", "roles", "preferred_username", "nonce"),
      "grant_types_supported" -> ujson.Arr("authorization_code", "refresh_token", "urn:ietf:params:oauth:grant-type:device_code")
    )

  val allKeys =
    val keys = db.run:
      sql"""select private_key, public_key from jwks""".read[(Array[Byte], Array[Byte])]

    for (privateKeyBytes, publicKeyBytes) <- keys yield
      val pubKey = oauth2.jwt.readEcPublicKey(publicKeyBytes)
      val jwk = oauth2.jwt.jwkFromKey(pubKey)
      (privateKeyBytes, publicKeyBytes, jwk)


  val (privateKeyBytes, publicKeyBytes, jwk) = allKeys.headOption.getOrElse(throw new RuntimeException("No keys found in database"))

  @app.get("/.well-known/jwks.json")
  def getJwks() =
    ujson.Obj(
      "keys" -> ujson.Arr.from(allKeys.map(_._3))
    )

  private def error(tpe: String, desc: String, code: Int = 400): keg.Response[keg.Data] =
    keg.Response(ujson.Obj(
      "error" -> tpe,
      "error_description" -> desc
    ), code)

  @app.get("/authorize")
  def authorize(req: keg.Request, user: User): keg.Response[keg.Data] =
    if req.params.get("response_type") != Some("code") then
      return error("unsupported_response_type", "Only response_type=code is supported")

    val scope = req.params.get("scope") match
      case Some(s) => s.split(" ").toSet
      case None => Set.empty[String]

    val state = req.params.get("state")
    val nonce = req.params.get("nonce")

    // validate client
    val client = req.params.get("client_id").flatMap(id => clientRepo.findByClientId(id)) match
      case Some(c) => c
      case None => return error("invalid_request", "Unknown client_id")

    val redirectUri = req.params.get("redirect_uri") match
      case Some(uri) if client.redirectUris.contains(uri) => uri
      case _ => return error("invalid_request", "Invalid or missing redirect_uri")

    // for public clients, require PKCE (only the "S256" method is supported)
    val codeChallengeMethod = req.params.get("code_challenge_method") match
      case Some("S256") => Some("S256")
      case None => None
      case _ => return error("invalid_request", "Only code_challenge_method=S256 is supported")

    val codeChallenge = req.params.get("code_challenge")
    val clientIsPublic = client.secret.isEmpty
    if clientIsPublic && codeChallenge.isEmpty then
        return error("invalid_request", "code_challenge is required for public clients")

    val lastVerified = user.sess.lastSudo.getOrElse(java.time.Instant.EPOCH)
    val now = java.time.Instant.now()
    if lastVerified.plusSeconds(300).isBefore(now) then
      val destination = url"/sudo?dest=${req.target}"
      return keg.Response((), 303, headers = Seq("location" -> destination))

    val existingConsent = consentRepo.findByUserAndClientId(user.id, client.id)

    val missingScopes = existingConsent match
      case Some(consent) => scope.diff(consent.scope)
      case None => scope

    if !clientIsPublic && missingScopes.isEmpty then
      // if the client is confidential and there are no new scopes requested, skip consent and immediately issue code
      return issueCodeAndRedirect(
        client = client,
        user = user,
        scope = scope,
        state = state,
        nonce = nonce,
        redirectUri = redirectUri,
        codeChallenge = codeChallenge
      )

    import scalatags.Text.all as h
    import h.given
    val r = fullPage(req, s"Authorize ${client.id}")(
      h.h1(s"${user.name} ↔ ${client.displayName}"),
      h.p(s"Application ", h.strong(client.id), " is requesting access to your account:"),
      h.ul(
        for s <- scope.toSeq.sorted yield
          if missingScopes.contains(s) then
            h.li(h.strong(s))
          else
            h.li(s + " (already authorized)")
      ),
      h.p("You will be redirected to: ", h.code(redirectUri)),
      h.form(
        h.method := "post",
        h.action := "/grant-authorization",
        h.input(h.`type` := "hidden", h.name := "scope", h.value := scope.mkString(" ")),
        state.map(s => h.input(h.`type` := "hidden", h.name := "state", h.value := s)),
        nonce.map(n => h.input(h.`type` := "hidden", h.name := "nonce", h.value := n)),
        h.input(h.`type` := "hidden", h.name := "client_id", h.value := client.id),
        h.input(h.`type` := "hidden", h.name := "redirect_uri", h.value := redirectUri),
        codeChallenge.map(c => h.input(h.`type` := "hidden", h.name := "code_challenge", h.value := c)),
        h.div(h.display.flex, h.attr("gap") := "0.75rem")(
          h.button(h.`type` := "submit", h.name := "action", h.value := "allow")("Allow"),
          h.button(h.`type` := "submit", h.name := "action", h.value := "deny",
            h.backgroundColor := "transparent", h.color := "#666", h.border := "1px solid #ccc"
          )("Deny")
        )
      )
    )
    keg.Response(r, 200)

  private def issueCodeAndRedirect(
    client: Client,
    user: User,
    scope: Set[String],
    state: Option[String],
    nonce: Option[String],
    redirectUri: String,
    codeChallenge: Option[String]
  ): keg.Response[keg.Data] =
    val tok = Token.random()
    authorizationCodes.put(tok.sha256(), AuthorizationCode(
      code_hash = tok.sha256(),
      clientId = client.id,
      userId = user.id,
      redirectUri = redirectUri,
      scope = scope,
      nonce = nonce,
      codeChallenge = codeChallenge,
      sessionId = user.sess.id
    ))
    val destination = s"$redirectUri?code=${tok.toHex}" + state.map(s => s"&state=${java.net.URLEncoder.encode(s, "UTF-8")}").getOrElse("")
    keg.Response((), 302, headers = Seq("location" -> destination))

  @app.post("/grant-authorization")
  def grantAuthorization(req: keg.Request, sudo: SudoMode, data: keg.FormData): keg.Response[keg.Data] =
    val user = sudo.user
    val action = data.fields.getOrElse("action", "deny")

    val clientId = data.fields.getOrElse("client_id", keg.abort(400))
    val client = clientRepo.findByClientId(clientId) match
      case Some(c) => c
      case None => keg.abort(400)
    val redirectUri = data.fields.get("redirect_uri") match
      case Some(uri) if client.redirectUris.contains(uri) => uri
      case _ => keg.abort(400)

    val scope = data.fields.get("scope").map(_.split(" ").toSet).getOrElse(Set.empty)
    val state = data.fields.get("state")
    val nonce = data.fields.get("nonce")
    val codeChallenge = data.fields.get("code_challenge")

    if action == "deny" then
      val destination = redirectUri + keg.util.makeUrl(queryParams = Seq(
        "error" -> "access_denied",
        "error_description" -> "User denied consent"
      ) ++ state.map(s => "state" -> s))
      return keg.Response((), 302, headers = Seq("location" -> destination))

    // record consent
    consentRepo.createOrModify(user.id, client.id)(
      create = Consent(
        userId = user.id,
        clientId = client.id,
        scope = scope
      ),
      modify = (existing) => existing.copy(scope = existing.scope ++ scope)
    )

    issueCodeAndRedirect(
      client = client,
      user = user,
      scope = scope,
      state = state,
      nonce = nonce,
      redirectUri = redirectUri,
      codeChallenge = codeChallenge
    )

  private def userClaims(user: UserInfo, scope: Set[String]): ujson.Obj =
    val claims = ujson.Obj("sub" -> user.id)
    if scope.contains("roles") then
      claims("roles") = user.roles
    if scope.contains("profile") then
      claims("name") = user.name
      claims("preferred_username") = user.name
    if scope.contains("email") then
      claims("email") = user.email
    claims

  private def buildResponse(
    client: Client,
    user: UserInfo,
    scope: Set[String],
    sessionId: Option[String],
    nonce: Option[String] = None
  ): ujson.Obj =
    val responseObject = ujson.Obj()
    val accessTtlSeconds = 600

    val exp = (System.currentTimeMillis() / 1000 + accessTtlSeconds) // expires in 1 hour
    val iat = (System.currentTimeMillis() / 1000)
    val accessToken = java.util.UUID.randomUUID().toString
    accessTokens.put(accessToken, AccessToken(
      token = accessToken,
      clientId = client.id,
      userId = user.id,
      scope = scope
    ))
    responseObject("access_token") = accessToken
    responseObject("token_type") = "Bearer"
    responseObject("expires_in") = accessTtlSeconds

    if scope.contains("openid") then
      val claims = userClaims(user, scope)
      claims("iss") = config.server.issuer
      claims("aud") = client.id
      claims("exp") = ujson.Num(exp)
      claims("iat") = ujson.Num(iat)
      nonce.foreach(n => claims("nonce") = n)
      val idToken = oauth2.jwt.signJwt(
        claims = claims,
        privateKey = oauth2.jwt.readEcPrivateKey(privateKeyBytes),
        publicKey = oauth2.jwt.readEcPublicKey(publicKeyBytes)
      )
      responseObject("id_token") = idToken

    if scope.contains("offline_access") then
      val refreshToken = Token.random()

      val value = RefreshToken(
        hash = refreshToken.sha256(),
        clientId = client.id,
        userId = user.id,
        scope = scope,
        sessionId = sessionId,
        expiresAt = None, // TODO
        nonce = nonce
      )

      responseObject("refresh_token") = refreshToken.toHex

    responseObject

  private def getClientCreds(req: keg.Request, data: keg.FormData): Option[(String, String)] =
    // first check for HTTP Basic auth
    req.headers.get("authorization") match
      case Some(h) if h.startsWith("Basic ") =>
        try
          val decoded = new String(java.util.Base64.getDecoder.decode(h.substring("Basic ".length)), java.nio.charset.StandardCharsets.UTF_8)
          decoded.split(":", 2) match
            case Array(id, secret) => Some((id, secret))
            case _ => None
        catch
          case _: IllegalArgumentException => None // invalid Base64
      case _ =>
        // if no Basic auth, check for client_id and client_secret in the request body
        val id = data.fields.get("client_id")
        val secret = data.fields.get("client_secret").getOrElse("")
        id.map(i => (i, secret))

  @app.post("/token")
  def token(req: keg.Request, data: keg.FormData): keg.Response[keg.Data] =
    val grantType = data.fields.get("grant_type") match
      case Some(gt) => gt
      case None => return error("invalid_request", "Missing grant_type parameter")

    val clientCreds = getClientCreds(req, data)

    val client = clientCreds.flatMap { case (id, secret) => clientRepo.findByClientId(id) } match
      case Some(c) => c
      case None => return error("invalid_client", "Unknown client", 401)

    // if the client has a secret, require it and validate it (public clients
    // are verified with PKCE in the corresponding grant types)
    if client.secret.nonEmpty then
      val providedSecret = clientCreds.flatMap { case (_, secret) => Some(Token.fromHex(secret).sha256()) } match
        case Some(s) => s
        case None => return error("invalid_client", "Missing client secret", 401)
      if providedSecret != client.secret.get then
        return error("invalid_client", "Invalid client secret", 401)

    grantType match
      case "authorization_code" =>
        val code = data.fields.get("code") match
          case Some(c) => Token.fromHex(c).sha256()
          case None => return error("invalid_request", "Missing code parameter")
        val redirectUri = data.fields.get("redirect_uri")

        val entry0 = authorizationCodes.get(code)
        authorizationCodes.remove(code) // authorization codes are one-time use, so remove it immediately

        val entry = entry0 match
          case None => return error("invalid_grant", "Invalid or expired authorization code")
          case Some(e) => e

        if entry.clientId != client.id then
          return error("invalid_grant", "Authorization code was not issued to this client")
        if redirectUri.isEmpty || entry.redirectUri != redirectUri.get then
          return error("invalid_grant", "redirect_uri does not match the one used in the authorization request")

        val isPublicClient = client.secret.isEmpty
        if isPublicClient then
          val codeVerifier = data.fields.get("code_verifier") match
            case Some(cv) => Token.fromBase64Url(cv)
            case None => return error("invalid_request", "Missing code_verifier for public client")

          val storedChallenge = entry.codeChallenge match
            case Some(cc) => HashedToken.fromBase64Url(cc)
            case None => return error("invalid_grant", "code_challenge") // TODO

          println(s"Verifying code challenge: verifier=${codeVerifier.sha256().toBase64Url}, storedChallenge=${storedChallenge.toBase64Url}")

          if codeVerifier.sha256() != storedChallenge then
            return error("invalid_grant", "Invalid code_verifier")

        val user = userInfoRepository.findByUserId(entry.userId) match
          case Some(u) => u
          case None => return error("invalid_grant", "User no longer exists")

        keg.Response(buildResponse(client, user, entry.scope, Some(entry.sessionId), entry.nonce))
      case "refresh_token" =>
        val token = data.fields.get("refresh_token") match
          case Some(t) => t
          case None => return error("invalid_request", "Missing refresh_token parameter")

        val entry0 = refreshTokenRepo.findByToken(Token.fromHex(token).sha256())
        refreshTokenRepo.delete(token) // Invalidate the refresh token immediately to prevent reuse. If the client needs a new one, it can request it with the new access token.

        val entry = entry0 match
          case None => return error("invalid_grant", "Invalid or expired refresh token")
          case Some(e) => e

        if entry.clientId != client.id then
          return error("invalid_grant", "Refresh token was not issued to this client")

        val user = userInfoRepository.findByUserId(entry.userId) match
          case Some(u) => u
          case None => return error("invalid_grant", "User no longer exists")

        entry.sessionId match
          case Some(sessionId) =>
            app.sessions.get2(sessionId) match
              case None => return error("interaction_required", "Session associated with this refresh token is no longer active")
              case Some(s) => s
          case None => // nothing to check

        // if client.reauthenticate.isDefined then
        //   val now = java.time.Instant.now()
        //   if session.lastAuthenticated.plusSeconds(client.reauthenticate.get.toSeconds).isBefore(now) then
        //     return error("invalid_grant", "Session requires reauthentication")

        keg.Response(buildResponse(client, user, entry.scope, entry.sessionId, entry.nonce))

      case other =>
        return error("unsupported_grant_type", s"Grant type $other is not supported")

  @keg.get("/userinfo")
  def userinfo(req: keg.Request): keg.Response[keg.Data] =
    val authHeader = req.headers.get("authorization") match
      case Some(h) if h.startsWith("Bearer ") => h.substring("Bearer ".length)
      case _ => return error("invalid_request", "Missing or invalid Authorization header")

    val tokenEntry = accessTokens.getIfPresent(authHeader)
    if tokenEntry == null then
      return error("invalid_token", "Access token is invalid or expired", 401)

    val user = userInfoRepository.findByUserId(tokenEntry.userId) match
      case Some(u) => u
      case None => return error("invalid_token", "User no longer exists", 401)

    keg.Response(userClaims(user, tokenEntry.scope))

  val routes: Seq[keg.Route] = keg.routes()
