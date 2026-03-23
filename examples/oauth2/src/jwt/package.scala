package oauth2.crypto.jwt

import java.security.interfaces.ECPublicKey
import java.security.PublicKey
import java.security.PrivateKey
import java.security.interfaces.ECPrivateKey

sealed trait Algorithm
sealed trait SymmetricAlgorithm
sealed trait AsymmetricAlgorithm

sealed trait HmacAlgorithm extends SymmetricAlgorithm
sealed trait RsaAlgorithm extends AsymmetricAlgorithm
sealed trait EcAlgorithm extends AsymmetricAlgorithm
sealed trait EdAlgorithm extends AsymmetricAlgorithm

object Algorithm:
  case object HS256 extends HmacAlgorithm
  case object ES256 extends EcAlgorithm
  case object RS256 extends RsaAlgorithm

object Es:

  private def getCurveParams(name: String): java.security.spec.ECParameterSpec =
    val ap = java.security.AlgorithmParameters.getInstance("EC")
    ap.init(new java.security.spec.ECGenParameterSpec(name))
    ap.getParameterSpec(classOf[java.security.spec.ECParameterSpec])

  val SupportedCurveParams = Seq("secp256r1").map(c => c -> getCurveParams(c)).toMap

  def getCurveName(key: ECPublicKey | ECPrivateKey): String =
    val params = key match
      case k: ECPublicKey => k.getParams()
      case k: ECPrivateKey => k.getParams()

    val it = SupportedCurveParams.iterator
    while it.hasNext do
      val (curve, knownParams) = it.next()
      if knownParams.getCurve() == params.getCurve() &&
        knownParams.getOrder == params.getOrder then
          return curve

    throw new IllegalArgumentException(s"Unsupported curve: only ${SupportedCurveParams.keys.mkString(", ")} are supported")


    ???

case class Es256PublicKey private (underlying: java.security.interfaces.ECPublicKey)

object Es256PublicKey:
  ???

def checkSignature(
  jwt: String,
  lookupKey: String => Es256PublicKey
)= ???
