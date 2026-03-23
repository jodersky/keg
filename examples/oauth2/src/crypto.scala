package oauth2.crypto

import java.security.SecureRandom
import java.util.Base64

import Conversion.into

class Hasher(algorithm: String):
  private val instance = java.security.MessageDigest.getInstance(algorithm)
  val stream = java.security.DigestOutputStream(java.io.OutputStream.nullOutputStream(), instance)

  def update(data: geny.Writable): this.type =
    data.writeBytesTo(stream)
    this

  def digest(): Array[Byte] = instance.digest()
  def hexdigest(): String = digest().map("%02x" format _).mkString

object hashlib:

  def hasher(algorithm: String)(data: geny.Writable = null): Hasher =
    val hasher = new Hasher(algorithm)
    if data != null then
      hasher.update(data)
    hasher

  def sha256(data: geny.Writable = null): Hasher = hasher("SHA-256")(data)

  def sha1(data: geny.Writable = null): Hasher = hasher("SHA-1")(data)

  def sha224(data: geny.Writable = null): Hasher = hasher("SHA-224")(data)

  def sha383(data: geny.Writable = null): Hasher = hasher("SHA-384")(data)

  def sha512(data: geny.Writable = null): Hasher = hasher("SHA-512")(data)

  def sha3_224(data: geny.Writable = null): Hasher = hasher("SHA3-224")(data)

  def sha3_256(data: geny.Writable): Hasher =
      val hasher = new Hasher("SHA3-256")
      hasher.update(data)
      hasher

  def sha3_384(data: geny.Writable): Hasher =
    val hasher = new Hasher("SHA3-384")
    hasher.update(data)
    hasher

  def sha3_512(data: geny.Writable): Hasher =
    val hasher = new Hasher("SHA3-512")
    hasher.update(data)
    hasher

  def pbkdf2_hmac(
    password: String,
    salt: String,
    iterations: Int,
    algorithm: String = "SHA256",
    keyLength: Int = 256
  ) =
    val factory = javax.crypto.SecretKeyFactory.getInstance(s"PBKDF2WithHmac$algorithm")
    val spec = javax.crypto.spec.PBEKeySpec(
      password.toCharArray(),
      salt.getBytes("UTF-8"),
      iterations,
      keyLength
    )
    factory.generateSecret(spec).getEncoded().map("%02x" format _).mkString

  // def cstcmp(other: geny.Writable): Boolean =
  //   val otherHash = hashlib.sha256(other).digest()
  //   java.security.MessageDigest.isEqual(digest(), otherHash)



object dummy:
  val generator = java.security.KeyPairGenerator.getInstance("EC")
  val spec = new java.security.spec.ECGenParameterSpec("secp256r1")
  generator.initialize(spec)

  val pair = generator.generateKeyPair()
  val privateKey = pair.getPrivate.asInstanceOf[java.security.interfaces.ECPrivateKey]
  val publicKey = pair.getPublic.asInstanceOf[java.security.interfaces.ECPublicKey]


  val x = Base64.getUrlEncoder.withoutPadding().encodeToString(publicKey.getW().getAffineX().toByteArray.drop(1))
  val y = Base64.getUrlEncoder.withoutPadding().encodeToString(publicKey.getW().getAffineY().toByteArray.drop(1))
  // val d = Base64.getUrlEncoder.withoutPadding().encodeToString(privateKey.getS().toByteArray)

  val curveName = publicKey.getParams().getCurve().toString

  println(s"Generated EC key pair (curve: $curveName)")

  val hasher = java.security.MessageDigest.getInstance("SHA-256")
  hasher.update(publicKey.getW().getAffineX().toByteArray.drop(1))
  val hex = hasher.digest().map("%02x" format _).mkString
  val kid = hex.substring(0, 16)

  val jwks = ujson.Obj(
    "keys" -> ujson.Arr(
      ujson.Obj(
        "kty" -> "EC",
        "use" -> "sig",
        "alg" -> "ES256",
        "kid" -> kid,
        "crv" -> "P-256",
        "x" -> x,
        "y" -> y
      )
    )
  )

/** Get bytes of given BigInt, in big endian, assuming it is unsigned.
  *
  * While java's builtin BigInt#toByteArray already returns the bytes in big
  * endian, it prepends a 0x00 in case it is positive.
  */
def unsignedBytes(n: BigInt): Array[Byte] =
  val bytes = n.toByteArray
  if bytes(0) == 0 then bytes.drop(1) else bytes

// the key id is deterministically derived from the (public) x coordinate of
// the key
def kid(key: java.security.interfaces.ECPublicKey): String =
  val hasher = java.security.MessageDigest.getInstance("SHA-256")
  hasher.update(unsignedBytes(key.getW().getAffineX()))
  val hex = hasher.digest().map("%02x" format _).mkString
  hex.substring(0, 16)

/** Convert a Java PublicKey to a JSON web key (JWK) object.
  *
  * Only EC keys for the P-256 (secp256r1) curve are supported.
  *
  * See https://datatracker.ietf.org/doc/html/rfc7517
  */
def jwkFromKey(key0: java.security.PublicKey): ujson.Obj =
  val key = key0 match
    case ecKey: java.security.interfaces.ECPublicKey => ecKey
    case _ => throw IllegalArgumentException("Unsupported key type: only EC keys are supported")

  val params = key.getParams()

  // We only support the ES256 curve in the JWK standard, which is the P-256
  // curve (secp256r1), so we need to check that the provided java EC key is
  // for the correct curve. The way to do that in plain java is a bit
  // roundabout: we need to create load parameters for a named curve and then
  // check that the provided key's parameters match those of the named curve.
  val curveName = "secp256r1"
  val ap = java.security.AlgorithmParameters.getInstance("EC")
  ap.init(new java.security.spec.ECGenParameterSpec(curveName))
  val namedParams = ap.getParameterSpec(classOf[java.security.spec.ECParameterSpec])

  val ok = namedParams.getCurve() == params.getCurve() && namedParams.getOrder() == params.getOrder()
  if !ok then
    throw IllegalArgumentException(s"Unsupported curve: only $curveName is supported")

  val b64 = Base64.getUrlEncoder.withoutPadding()

  ujson.Obj(
    "kty" -> "EC",
    "use" -> "sig",
    "alg" -> "ES256",
    "kid" -> kid(key),
    "crv" -> "P-256",
    "x" -> b64.encodeToString(unsignedBytes(key.getW().getAffineX())),
    "y" -> b64.encodeToString(unsignedBytes(key.getW().getAffineY()))
  )

def signJwt(claims: ujson.Obj, privateKey: java.security.interfaces.ECPrivateKey, publicKey: java.security.interfaces.ECPublicKey): String =
  val header = ujson.Obj(
    "alg" -> "ES256",
    "typ" -> "JWT",
    "kid" -> kid(publicKey)
  )

  val buffer = new java.io.ByteArrayOutputStream()
  var b64 = Base64.getUrlEncoder.withoutPadding().wrap(buffer)
  header.writeBytesTo(b64)
  b64.close()

  buffer.write('.')

  b64 = Base64.getUrlEncoder.withoutPadding().wrap(buffer)
  claims.writeBytesTo(b64)
  b64.close()

  val payload = buffer.toByteArray()

  // The "SHA256withECDSAinP1363Format" algorithm in Java produces signatures in
  // the raw (r, s) format specified by the JWK standard
  val signature = java.security.Signature.getInstance("SHA256withECDSAinP1363Format")
  signature.initSign(privateKey)
  signature.update(payload)
  val sigBytes = signature.sign()

  buffer.write('.')

  b64 = Base64.getUrlEncoder.withoutPadding().wrap(buffer)
  b64.write(sigBytes)
  b64.close()

  new String(buffer.toByteArray(), "UTF-8")

def verifyJwt(token: String, publicKey: java.security.interfaces.ECPublicKey): Option[ujson.Obj] =
  val parts = token.split('.')
  if parts.length != 3 then return None

  val headerAndPayload = (parts(0) + "." + parts(1)).getBytes("UTF-8")
  val signature = Base64.getUrlDecoder.decode(parts(2))

  val verifier = java.security.Signature.getInstance("SHA256withECDSAinP1363Format")
  verifier.initVerify(publicKey)
  verifier.update(headerAndPayload)
  val valid = verifier.verify(signature)

  if valid then
    try
      val claimsJson = new String(Base64.getUrlDecoder.decode(parts(1)), "UTF-8")
      Some(ujson.read(claimsJson).obj)
    catch
      case _: Throwable => None
  else
    None

// _private_key = ec.generate_private_key(ec.SECP256R1())
// _public_key = _private_key.public_key()
// _pub_numbers = _public_key.public_numbers()

// # Stable key ID derived from the public key x coordinate
// _kid = hashlib.sha256(_pub_numbers.x.to_bytes(32, "big")).hexdigest()[:16]

// log.info("EC key generated (P-256), kid=%s", _kid)


// def _int_to_base64url(n: int) -> str:
//     length = (n.bit_length() + 7) // 8
//     return base64.urlsafe_b64encode(n.to_bytes(length, "big")).rstrip(b"=").decode()


// def _coord_to_base64url(n: int) -> str:
//     """Encode a P-256 field element (exactly 32 bytes) as unpadded base64url."""
//     return base64.urlsafe_b64encode(n.to_bytes(32, "big")).rstrip(b"=").decode()


// JWKS = {
//     "keys": [
//         {
//             "kty": "EC",
//             "use": "sig",
//             "alg": "ES256",
//             "kid": _kid,
//             "crv": "P-256",
//             "x": _coord_to_base64url(_pub_numbers.x),
//             "y": _coord_to_base64url(_pub_numbers.y),
//         }
//     ]
// }
