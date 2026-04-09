package oauth2

import scala.concurrent.duration.Duration

case class ServerConfig(
  host: String,
  port: Int,
  key: Option[os.Path],
  cert: Option[os.Path],
  secrets: Seq[String],
  sessionTtl: Duration,
  issuer: String,
  db: os.Path
)

case class Config(
  server: ServerConfig
)
