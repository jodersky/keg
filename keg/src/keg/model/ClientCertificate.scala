package keg.model

case class ClientCertificate(
  subject: String,
  altNames: Seq[(ClientCertificate.AltNameType, String)]
)

object ClientCertificate:
  enum AltNameType:
    case Other, Email, DNS, URI, IP
