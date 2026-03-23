package keg.main

case class Secret(bytes: Array[Byte]):
  def toBase64: String =
    java.util.Base64.getEncoder.encodeToString(bytes)

object Secret:
  private val hint = "You can generate a strong secret using `Secret.random().toBase64`, or `openssl rand -base64 32`."

  def apply(s: String): Secret =
    try
      val bytes = java.util.Base64.getDecoder.decode(s)
      if bytes.length < 32 then
        throw new IllegalArgumentException(
          "Secret is too weak: it must be at least 32 bytes when decoded. " + hint)
      Secret(bytes)
    catch
      case _: IllegalArgumentException =>
        throw new IllegalArgumentException("Secret must be a valid base64-encoded string. " + hint)

  // given stringAsSecret: Conversion[String, Secret] with
  //   def apply(s: String): Secret = Secret(s)

  def random(): Secret =
    val bytes = new Array[Byte](32)
    val random = new java.security.SecureRandom()
    random.nextBytes(bytes)
    Secret(bytes)

  def fromTmp(): Secret =
    val tmpDir = System.getProperty("java.io.tmpdir")
    val path = java.nio.file.Paths.get(tmpDir, "keg-secret")
    if java.nio.file.Files.exists(path) then
      val s = java.nio.file.Files.readString(path)
      Secret(s)
    else
      val secret = Secret.random()
      java.nio.file.Files.writeString(path, secret.toBase64)
      secret
