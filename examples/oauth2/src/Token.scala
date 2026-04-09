package oauth2

import java.sql.ResultSet

// We need this indirection because we can't override equals and hashCode on
// opaque types
class Blob(val underlying: Array[Byte]):
  override def equals(obj: Any): Boolean =
    obj match
      case that: Blob =>
        java.security.MessageDigest.isEqual(this.underlying, that.underlying)
      case _ => false
  override def hashCode(): Int =
    java.util.Arrays.hashCode(underlying)

opaque type Token = Blob
object Token:
  def hex(data: Array[Byte]): String =
    val builder = new StringBuilder
    for i <- 0 until data.length do
      val b = data(i)
      val upperNibble = (b >>> 4) & 0x0f
      val lowerNibble = b & 0x0f
      builder += Character.forDigit(upperNibble, 16)
      builder += Character.forDigit(lowerNibble, 16)
    builder.result()

  def unhex(data: String): Array[Byte] =
    val bytes = new Array[Byte](data.length / 2)
    for i <- 0 until data.length / 2 do
      val upperNibble = Character.digit(data.charAt(i * 2), 16)
      val lowerNibble = Character.digit(data.charAt(i * 2 + 1), 16)
      bytes(i) = ((upperNibble << 4) | lowerNibble).toByte
    bytes

  def apply(bytes: Array[Byte]): Token = Blob(bytes)

  def fromHex(s: String): Token = Blob(unhex(s))

  def fromBase64Url(s: String): Token =
    Blob(java.util.Base64.getUrlDecoder.decode(s))

  def random(length: Int = 32): Token =
    val bytes = new Array[Byte](length)
    val sec = new java.security.SecureRandom()
    sec.nextBytes(bytes)
    Token(bytes)

  extension (t: Token)
    /** The hexadecimal representation of the token */
    def toHex: String = hex(t.underlying)

    def toBase64Url: String =
      java.util.Base64.getUrlEncoder.withoutPadding().encodeToString(t.underlying)

    /** Hash the token using SHA-256 */
    def sha256(): HashedToken =
      val md = java.security.MessageDigest.getInstance("SHA-256")
      md.update(t.underlying)
      Blob(md.digest())

opaque type HashedToken = Blob
object HashedToken:
  def apply(bytes: Array[Byte]): HashedToken = Blob(bytes)

  def fromHex(s: String): HashedToken = Blob(Token.unhex(s))

  def fromBase64Url(s: String): HashedToken =
    Blob(java.util.Base64.getUrlDecoder.decode(s))

  extension (t: HashedToken)
    /** The hexadecimal representation of the hashed token */
    def toHex: String = Token.hex(t.underlying)

    def toBase64Url: String =
      java.util.Base64.getUrlEncoder.withoutPadding().encodeToString(t.underlying)

    // /** Compare the hashed token with another token in constant time */
    // def equalsCst(other: HashedToken): Boolean =
    //   java.security.MessageDigest.isEqual(t.underlying, other.underlying)


  given (using r: simplesql.SimpleReader[String]): simplesql.SimpleReader[HashedToken] with
    def readIdx(results: ResultSet, idx: Int): HashedToken =
      fromHex(r.readIdx(results, idx))
    def readName(result: ResultSet, name: String): HashedToken =
      fromHex(r.readName(result, name))

  given (using rw: upickle.ReadWriter[String]): upickle.ReadWriter[HashedToken] =
    rw.bimap(
      t => t.toHex,
      s => fromHex(s)
    )

// def foo =
//   val token = Token.random()
//   println(s"Token: ${token.toHex}")
//   println(s"Hash: ${token.sha256().toHex}")
//   println(s"Compare hash: ${token.equalsCst(token.sha256())}")
