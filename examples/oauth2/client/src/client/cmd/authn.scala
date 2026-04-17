package client.cmd.authn

class AuthnCtx:
  val clientId = "a90185bb-20fd-44c2-a2cd-74906c0058ab"

@clip.group("authn", "Authentication commands")
def authn() = AuthnCtx()

@clip.command()
def login(
  @clip.context ctx: AuthnCtx,
  device: Boolean = false
) =
  scribe.info("Logging in...")

  if device then
    scribe.info("Using device code flow")
    val result = _root_.client.flows.deviceCodeFlow(
      clientId = ctx.clientId,
      deviceAuthorizationEndpoint = "http://localhost:8080/device_authorization",
      tokenEndpoint = "http://localhost:8080/token",
      scope = "openid profile email offline_access kubernetes"
    )
    println(result)
  else
    scribe.info("Using authorization code flow")
    val result = _root_.client.flows.authorizationCodeFlow(
      clientId = ctx.clientId,
      authEndpoint = "http://localhost:8080/authorize",
      tokenEndpoint = "http://localhost:8080/token",
      scope = "openid profile email offline_access kubernetes"
    )
    println(result)

@clip.command()
def logout() =
  scribe.info("Logging out...")

@clip.command()
def accessToken(
  @clip.context ctx: AuthnCtx
) =
  scribe.info("Getting access token...")
  val instance = new client.CliClient(clientId = ctx.clientId)
  val token = instance.getToken()
  scribe.info(token.map(_.accessToken).getOrElse("No token found"))

@clip.command()
def idToken(
  @clip.context ctx: AuthnCtx,
  decode: Boolean = false
) =
  scribe.info("Getting ID token...")
  val instance = new client.CliClient(clientId = ctx.clientId)
  val token = instance.getToken()

  if decode then
    token.flatMap(_.idToken) match
      case Some(idToken) =>
        val parts = idToken.split('.')
        if parts.length == 3 then
          val payload = parts(1)
          val decoded = new String(java.util.Base64.getUrlDecoder.decode(payload), "UTF-8")
          ujson.writeToOutputStream(ujson.read(decoded), System.out, 2)
          System.out.println()
        else
          scribe.error("Invalid ID token format")
      case None => scribe.error("No ID token found")

  else
    token.flatMap(_.idToken) match
      case Some(idToken) => clip.echo(idToken)
      case None => scribe.error("No ID token found")

@clip.command(
  "configure-kubectl",
  "Output a kubeconfig file that uses this CLI for authentication"
)
def configureKubectl(
  @clip.arg("--out", Seq("-o"), "Output file for the kubeconfig. Defaults to ~/.kube/config. Use - for stdout.")
  out: clip.Output = clip.Output.File(os.home / ".kube" / "config")
) =
  scribe.info("Configuring kubectl...")

  out.stream: s =>
    val str =
      s"""|apiVersion: v1
          |clusters:
          |- cluster:
          |    certificate-authority-data: Ci0tLS0tQkVHSU4gQ0VSVElGSUNBVEUtLS0tLQpNSUlCZURDQ0FSMmdBd0lCQWdJQkFEQUtCZ2dxaGtqT1BRUURBakFqTVNFd0h3WURWUVFEREJock0zTXRjMlZ5CmRtVnlMV05oUURFM056RTRPRGd4TURnd0hoY05Nall3TWpJek1qTXdPREk0V2hjTk16WXdNakl4TWpNd09ESTQKV2pBak1TRXdId1lEVlFRRERCaHJNM010YzJWeWRtVnlMV05oUURFM056RTRPRGd4TURnd1dUQVRCZ2NxaGtqTwpQUUlCQmdncWhrak9QUU1CQndOQ0FBVFZKckExeGNFUFdaRHFhNFllRkRBczFKbG1TOWhyczhMZzhiYmdzbXNkCmthQzluTTRiTzJwYkU2amE0RmpwQTNQMlFUcU5DVHF0bU1hVDk5WHlZN0xtbzBJd1FEQU9CZ05WSFE4QkFmOEUKQkFNQ0FxUXdEd1lEVlIwVEFRSC9CQVV3QXdFQi96QWRCZ05WSFE0RUZnUVVWRmp6K1BhdGtWTU80YjIvd2d2dwpwV1Z0VlhRd0NnWUlLb1pJemowRUF3SURTUUF3UmdJaEFKRlk1cXJBZ2ROYXJ3QlpDMUgrUFhTdzVmV2pqL0RHCjk1MktaUFpaUDh2NUFpRUFyK0dObG12aVdzb0QvcjRlVGdVVitzdmcveEgra0c0RFNUTXVNTlEvVm5zPQotLS0tLUVORCBDRVJUSUZJQ0FURS0tLS0tCiAgICA=
          |    server: https://[fd42:aa00:4320:2000::1]:6443
          |  name: homelab
          |contexts:
          |- context:
          |    cluster: homelab
          |    user: homelab-user
          |  name: homelab
          |current-context: homelab
          |kind: Config
          |preferences: {}
          |users:
          |- name: homelab-user
          |  user:
          |    exec:
          |      apiVersion: client.authentication.k8s.io/v1beta1
          |      args:
          |      - get-creds
          |      command: hl
          |      env: null
          |      interactiveMode: IfAvailable
          |      provideClusterInfo: false
          |""".stripMargin
    s.write(str.getBytes("UTF-8"))

val command = clip.commandFor(this)
