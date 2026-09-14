package client.flows

case class TokenResponse(
  accessToken: String,
  expiresIn: Long,
  refreshToken: Option[String],
  idToken: Option[String]
)

enum AuthorizationCodeFlowResult:
  case Success(tokenResponse: TokenResponse)
  case Error(code: String, description: Option[String])
  case NoBrowserSupport // couldn't open a browser
  case PortInUse // couldn't start the server on the specified port

def authorizationCodeFlow(
  clientId: String,
  authEndpoint: String = "http://localhost:8080/authorize",
  tokenEndpoint: String = "http://localhost:8080/token",
  redirectUri: String = "http://localhost:8082/callback",
  scope: String = "openid profile email offline_access",
  port: Int = 8082,
  callback: String = "/callback"
): AuthorizationCodeFlowResult =
  val latch = new java.util.concurrent.CountDownLatch(1)
  val result = new java.util.concurrent.atomic.AtomicReference[AuthorizationCodeFlowResult](
    AuthorizationCodeFlowResult.Error("internal", Some("No result yet"))
  )

  val state = java.util.UUID.randomUUID().toString
  val rawCodeVerifier = java.util.UUID.randomUUID().toString.getBytes("UTF-8")

  def b64(bytes: Array[Byte]): String =
    java.util.Base64.getUrlEncoder.withoutPadding().encodeToString(bytes)

  val codeVerifier = b64(rawCodeVerifier)
  val codeChallenge =
    val digest = java.security.MessageDigest.getInstance("SHA-256").digest(rawCodeVerifier)
    b64(digest)

  println(codeChallenge)
  println(codeVerifier)

  val ok = keg.Response("You may close this window.", 200)
  val failure = keg.Response("You may close this window.", 200)

  val ep = keg.model.Endpoint(
    callback, Seq("GET"),
    invoke = req =>
      try
        if req.params.contains("error") then
          val error = req.params.get("error").getOrElse("unknown_error")
          val description = req.params.get("error_description")
          result.set(AuthorizationCodeFlowResult.Error(error, description))
          failure
        else
          val code = req.params.get("code").getOrElse("")
          val returnedState = req.params.get("state").getOrElse("")
          if returnedState != state then
            result.set(AuthorizationCodeFlowResult.Error("invalid_state", Some(s"Expected state '$state' but got '$returnedState'")))
            failure
          else
            val response = requests.post(
              tokenEndpoint,
              data = Map(
                "grant_type" -> "authorization_code",
                "code" -> code,
                "redirect_uri" -> redirectUri,
                "client_id" -> clientId,
                "code_verifier" -> codeVerifier
              ),
              check = false
            )

            if response.is2xx then
              val json = ujson.read(response.text())
              val tokenResponse = TokenResponse(
                accessToken = json("access_token").str,
                expiresIn = json.obj("expires_in").num.toLong,
                refreshToken = json.obj.get("refresh_token").map(_.str),
                idToken = json.obj.get("id_token").map(_.str)
              )
              result.set(AuthorizationCodeFlowResult.Success(tokenResponse))
              ok
            else if response.is4xx then
              val json = ujson.read(response.text())
              val error = json.obj.get("error").map(_.str).getOrElse("unknown_error")
              val description = json.obj.get("error_description").map(_.str)
              result.set(AuthorizationCodeFlowResult.Error(error, description))
              failure
            else
              result.set(AuthorizationCodeFlowResult.Error("invalid_response", Some(s"Token endpoint returned status ${response.statusCode}")))
              failure
      catch
        case e: Exception =>
          result.set(AuthorizationCodeFlowResult.Error("internal", Some(e.getMessage)))
          failure
      finally
        latch.countDown()
  )

  val server = keg.Server(
    endpoints = Seq(ep),
    listeners = Seq(keg.Listener.Http("::", port)),
    transport = keg.transport.uhttp.UhttpTransport
  )

  try
    try
      server.start()
    catch
      case e: java.net.BindException =>
        return AuthorizationCodeFlowResult.PortInUse

    val url = authEndpoint + keg.util.makeUrl(
      queryParams = Seq(
        "response_type" -> "code",
        "client_id" -> clientId,
        "redirect_uri" -> redirectUri,
        "scope" -> scope,
        "state" -> state,
        "code_challenge" -> codeChallenge,
        "code_challenge_method" -> "S256")
    )

    if clip.launch(url) != 0 then
      return AuthorizationCodeFlowResult.NoBrowserSupport

    if !latch.await(2, java.util.concurrent.TimeUnit.MINUTES) then
      return AuthorizationCodeFlowResult.Error("timeout", Some("Authorization timed out"))

    result.get()

  finally
    server.stop()
    server.awaitTermination()


enum DeviceCodeFlowResult:
  case Success(tokenResponse: TokenResponse)
  case Error(code: String, description: Option[String])

def deviceCodeFlow(
  clientId: String,
  deviceAuthorizationEndpoint: String = "http://localhost:8080/device_authorization",
  tokenEndpoint: String = "http://localhost:8080/token",
  scope: String = "openid profile email offline_access",
  interval: Int = 5,
  onPending: (userCode: String, verificationUri: String) => Unit = (userCode, verificationUri) =>
    println(s"Go to $verificationUri and enter code: $userCode")
): DeviceCodeFlowResult =
  try
    // step 1: request device and user codes
    val initResponse = requests.post(
      deviceAuthorizationEndpoint,
      data = Map("client_id" -> clientId, "scope" -> scope),
      check = false
    )
    if !initResponse.is2xx then
      val json = ujson.read(initResponse.text())
      return DeviceCodeFlowResult.Error(
        json.obj.get("error").map(_.str).getOrElse("invalid_response"),
        json.obj.get("error_description").map(_.str)
      )

    val initJson     = ujson.read(initResponse.text())
    val deviceCode   = initJson("device_code").str
    val userCode     = initJson("user_code").str
    val verificationUri = initJson("verification_uri").str
    val expiresIn    = initJson("expires_in").num.toLong
    val pollInterval = initJson.obj.get("interval").map(_.num.toInt).getOrElse(interval)

    onPending(userCode, verificationUri)

    // step 2: poll until authorized, denied, or expired
    val deadline = System.currentTimeMillis() + expiresIn * 1000
    while System.currentTimeMillis() < deadline do
      Thread.sleep(pollInterval * 1000L)
      val pollResponse = requests.post(
        tokenEndpoint,
        data = Map(
          "grant_type" -> "urn:ietf:params:oauth:grant-type:device_code",
          "device_code" -> deviceCode,
          "client_id"   -> clientId
        ),
        check = false
      )
      if pollResponse.is2xx then
        val json = ujson.read(pollResponse.text())
        return DeviceCodeFlowResult.Success(TokenResponse(
          accessToken  = json("access_token").str,
          expiresIn    = json.obj("expires_in").num.toLong,
          refreshToken = json.obj.get("refresh_token").map(_.str),
          idToken      = json.obj.get("id_token").map(_.str)
        ))
      else
        val json  = ujson.read(pollResponse.text())
        val error = json.obj.get("error").map(_.str).getOrElse("unknown_error")
        error match
          case "authorization_pending" => // keep polling
          case "slow_down"             => Thread.sleep(pollInterval * 1000L) // back off
          case _                       =>
            return DeviceCodeFlowResult.Error(error, json.obj.get("error_description").map(_.str))

    DeviceCodeFlowResult.Error("expired_token", Some("Device authorization expired"))
  catch
    case e: Exception =>
      DeviceCodeFlowResult.Error("internal", Some(e.getMessage))


enum RefreshTokenFlowResult:
  case Success(tokenResponse: TokenResponse)
  case Error(code: String, description: Option[String])

def refreshTokenFlow(
  refreshToken: String,
  clientId: String,
  tokenEndpoint: String = "http://localhost:8080/token"
): RefreshTokenFlowResult =
  try
    val response = requests.post(
      tokenEndpoint,
      data = Map(
        "grant_type" -> "refresh_token",
        "refresh_token" -> refreshToken,
        "client_id" -> clientId
      ),
      check = false
    )
    if response.is2xx then
      val json = ujson.read(response.text())
      val tokenResponse = TokenResponse(
        accessToken = json("access_token").str,
        expiresIn = json.obj("expires_in").num.toLong,
        refreshToken = json.obj.get("refresh_token").map(_.str),
        idToken = json.obj.get("id_token").map(_.str)
      )
      RefreshTokenFlowResult.Success(tokenResponse)
    else if response.is4xx then
      val json = ujson.read(response.text())
      val error = json.obj.get("error").map(_.str).getOrElse("unknown_error")
      val description = json.obj.get("error_description").map(_.str)
      RefreshTokenFlowResult.Error(error, description)
    else
      RefreshTokenFlowResult.Error("invalid_response", Some(s"Token endpoint returned status ${response.statusCode}"))
  catch
    case e: Exception =>
      RefreshTokenFlowResult.Error("internal", Some(e.getMessage))

