package keg.main

object Secret:
  private val hint = "You can generate a strong secret using `keg.Secret.random()`, or `openssl rand -base64 32`."

  def check(secret: String): Array[Byte] =
    val bytes = try
      java.util.Base64.getDecoder.decode(secret)
    catch
      case _: IllegalArgumentException =>
        throw new IllegalArgumentException("Secret must be a valid base64-encoded string. " + hint)

    if bytes.length < 32 then
      throw new IllegalArgumentException("Secret is too weak: it must be at least 32 bytes when decoded. " + hint)
    else
      bytes

  /** Generate a random secret */
  def random(): String =
    val bytes = new Array[Byte](32)
    val random = new java.security.SecureRandom()
    random.nextBytes(bytes)
    java.util.Base64.getEncoder.encodeToString(bytes)

  /** Use an existing secret or generate a new one if none exists.
    *
    * Secrets from this method are stored in a temp file and will be shared
    * across all callers of this method. Hence, this should only be used for
    * development and testing, not for production.
    */
  def persistent(): String =
    val tmpDir = System.getProperty("java.io.tmpdir")
    val path = java.nio.file.Paths.get(tmpDir, "keg-secret")
    if java.nio.file.Files.exists(path) then
      val s = java.nio.file.Files.readString(path)
      s
    else
      val secret = Secret.random()
      java.nio.file.Files.writeString(path, secret)
      secret
