package client

import client.flows.TokenResponse

case class Data(
  token: flows.TokenResponse,
  expiresAt: Long
)

class CliClient(
  clientId: String,
  authEndpoint: String = "http://localhost:8080/authorize",
  tokenEndpoint: String = "http://localhost:8080/token",
  scope: String = "openid profile email offline_access kubernetes"
):
  private val dataDirectory = clip.userdirs("mycli").state
  private val runDirectory = clip.userdirs("mycli").runtime

  private val dataFile = dataDirectory / "token.json"
  private val lockFile = runDirectory / "lock"

  private val lock = new java.util.concurrent.locks.ReentrantLock()
  private def withLock[T](f: => T): T =
    lock.lock()
    try
      os.makeDir.all(lockFile / os.up)
      val channel = java.nio.channels.FileChannel.open(
        lockFile.toNIO,
        java.nio.file.StandardOpenOption.CREATE,
        java.nio.file.StandardOpenOption.WRITE
      )
      try
        val fileLock = channel.lock()
        try f
        finally fileLock.release()
      finally channel.close()
    finally lock.unlock()

  private def getDataFromFile(): Option[Data] =
    if os.exists(dataFile) then
      val json = ujson.read(os.read(dataFile))
      val data = Data(
        token = flows.TokenResponse(
          accessToken = json("access_token").str,
          idToken = json.obj.get("id_token").map(_.str),
          expiresIn = json.obj("expires_in").num.toLong,
          refreshToken = json.obj.get("refresh_token").map(_.str)
        ),
        expiresAt = json.obj("expires_at").num.toLong
      )
      Some(data)
    else None

  def getToken(): Option[flows.TokenResponse] = withLock:
    renewToken()

  private def renewToken(): Option[flows.TokenResponse] =
    var token: flows.TokenResponse = null
    var refreshToken: String = null

    getDataFromFile() match
      case Some(data) if data.expiresAt > System.currentTimeMillis() / 1000 =>
        token = data.token
      case Some(data) =>
        scribe.debug("Access token expired, trying to refresh")
        refreshToken = data.token.refreshToken.orNull
      case _ =>
        scribe.debug("No access token found, trying to log in")

    // attempt to use refresh token
    if token == null && refreshToken != null then
      scribe.debug("Trying to refresh access token using refresh token")
      flows.refreshTokenFlow(refreshToken, clientId, tokenEndpoint) match
        case flows.RefreshTokenFlowResult.Success(newToken) =>
          token = newToken
        case flows.RefreshTokenFlowResult.Error(code, description) =>
          scribe.debug(s"Failed to refresh token: $code ${description.getOrElse("")}")

    // attempt authorization code flow
    if token == null then
      flows.authorizationCodeFlow(clientId, authEndpoint, tokenEndpoint, scope = scope) match
        case flows.AuthorizationCodeFlowResult.Success(tokenResponse) =>
          token = tokenResponse
        case flows.AuthorizationCodeFlowResult.PortInUse =>
          scribe.debug("Failed to start local server for authorization code flow: port in use")
        case flows.AuthorizationCodeFlowResult.NoBrowserSupport =>
          scribe.debug("Failed to open browser for authorization code flow: no browser support")
        case flows.AuthorizationCodeFlowResult.Error(code, description) =>
          scribe.debug(s"Authorization code flow failed: $code ${description.getOrElse("")}")

    if token != null then
      val expiresAt = System.currentTimeMillis() / 1000 + token.expiresIn
      val json = ujson.Obj(
        "access_token" -> token.accessToken,
        "expires_in" -> ujson.Num(token.expiresIn),
        "expires_at" -> ujson.Num(expiresAt)
      )
      token.refreshToken.foreach(rt => json("refresh_token") = rt)
      token.idToken.foreach(idt => json("id_token") = idt)
      os.write.over(dataFile, json.render(), createFolders = true)

    Option(token)

  def getIdToken(): Option[String] =
    getToken().flatMap(_.idToken)
