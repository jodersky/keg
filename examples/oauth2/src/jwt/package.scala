package oauth2.jwt

import java.util.Base64
import java.security.MessageDigest

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

def readEcPublicKey(data: Array[Byte]): java.security.interfaces.ECPublicKey =
  val keyFactory = java.security.KeyFactory.getInstance("EC")
  val publicKeySpec = new java.security.spec.X509EncodedKeySpec(data)
  keyFactory.generatePublic(publicKeySpec).asInstanceOf[java.security.interfaces.ECPublicKey]

def readPemEcPublicKey(pem: String): java.security.interfaces.ECPublicKey =
  val publicKeyBytes = Base64.getDecoder.decode(
    pem
      .replace("-----BEGIN PUBLIC KEY-----", "")
      .replace("-----END PUBLIC KEY-----", "")
      .replaceAll("\\s", "")
  )
  readEcPublicKey(publicKeyBytes)

def readEcPrivateKey(data: Array[Byte]): java.security.interfaces.ECPrivateKey =
  val keyFactory = java.security.KeyFactory.getInstance("EC")
  val privateKeySpec = new java.security.spec.PKCS8EncodedKeySpec(data)
  keyFactory.generatePrivate(privateKeySpec).asInstanceOf[java.security.interfaces.ECPrivateKey]

def readPemEcPrivateKey(pem: String): java.security.interfaces.ECPrivateKey =
  val privateKeyBytes = Base64.getDecoder.decode(
    pem
      .replace("-----BEGIN PRIVATE KEY-----", "")
      .replace("-----END PRIVATE KEY-----", "")
      .replaceAll("\\s", "")
  )
  readEcPrivateKey(privateKeyBytes)
