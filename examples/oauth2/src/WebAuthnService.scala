package oauth2

import com.yubico.webauthn.*
import com.yubico.webauthn.data.*
import com.yubico.webauthn.exception.*
import scala.jdk.OptionConverters.*
import scala.jdk.CollectionConverters.*
import java.util.Optional

/** Wraps webauthn-server-core to handle registration and assertion ceremonies.
  * All crypto is done locally — no external service calls.
  *
  * @param rpId     Relying party ID (must equal the hostname the browser uses, e.g. "localhost")
  * @param rpName   Human-readable name shown in the browser's security-key dialog
  * @param origin   Full origin the browser connects to, e.g. "http://localhost:8080"
  */
class WebAuthnService(
  rpId: String,
  rpName: String,
  origin: String,
  userInfoRepo: UserInfoRepository
):
  // Short-lived in-memory stores for ongoing ceremonies (5-minute TTL)
  private val pendingRegistrations: InMemStore[String, (String, PublicKeyCredentialCreationOptions)] =
    InMemStore(scala.concurrent.duration.Duration("5 minutes"))

  private val pendingAssertions: InMemStore[String, AssertionRequest] =
    InMemStore(scala.concurrent.duration.Duration("5 minutes"))

  // Encode a user ID string as the opaque WebAuthn user handle
  private def userHandle(userId: String): ByteArray =
    new ByteArray(userId.getBytes("UTF-8"))

  private val credRepo: CredentialRepository = new CredentialRepository:
    def getCredentialIdsForUsername(username: String): java.util.Set[PublicKeyCredentialDescriptor] =
      userInfoRepo.findByName(username)
        .toSeq.flatMap(_.webAuthnCredentials)
        .map(c =>
          PublicKeyCredentialDescriptor.builder()
            .id(ByteArray.fromBase64Url(c.id))
            .`type`(PublicKeyCredentialType.PUBLIC_KEY)
            .build()
        ).toSet.asJava

    def getUserHandleForUsername(username: String): Optional[ByteArray] =
      userInfoRepo.findByName(username).map(u => userHandle(u.id)).toJava

    def getUsernameForUserHandle(uh: ByteArray): Optional[String] =
      userInfoRepo.findByUserId(new String(uh.getBytes, "UTF-8")).map(_.name).toJava

    def lookup(credentialId: ByteArray, uh: ByteArray): Optional[RegisteredCredential] =
      userInfoRepo.findByUserId(new String(uh.getBytes, "UTF-8"))
        .flatMap(_.webAuthnCredentials.find(_.id == credentialId.getBase64Url))
        .map(c =>
          RegisteredCredential.builder()
            .credentialId(ByteArray.fromBase64Url(c.id))
            .userHandle(uh)
            .publicKeyCose(ByteArray.fromBase64Url(c.publicKey))
            .signatureCount(c.signCount)
            .build()
        ).toJava

    def lookupAll(credentialId: ByteArray): java.util.Set[RegisteredCredential] =
      userInfoRepo.getAll
        .flatMap(user =>
          user.webAuthnCredentials
            .find(_.id == credentialId.getBase64Url)
            .map(c =>
              RegisteredCredential.builder()
                .credentialId(ByteArray.fromBase64Url(c.id))
                .userHandle(userHandle(user.id))
                .publicKeyCose(ByteArray.fromBase64Url(c.publicKey))
                .signatureCount(c.signCount)
                .build()
            )
        ).toSet.asJava

  private val rp = RelyingParty.builder()
    .identity(
      RelyingPartyIdentity.builder()
        .id(rpId)
        .name(rpName)
        .build()
    )
    .credentialRepository(credRepo)
    .origins(Set(origin).asJava)
    .build()

  /** Start a registration ceremony. Returns JSON to pass to navigator.credentials.create(). */
  def beginRegistration(token: String, user: UserInfo): String =
    val options = rp.startRegistration(
      StartRegistrationOptions.builder()
        .user(
          UserIdentity.builder()
            .name(user.name)
            .displayName(user.name)
            .id(userHandle(user.id))
            .build()
        )
        .authenticatorSelection(
          // CROSS_PLATFORM: require a roaming authenticator (USB/NFC security key)
          AuthenticatorSelectionCriteria.builder()
            .authenticatorAttachment(AuthenticatorAttachment.CROSS_PLATFORM)
            .residentKey(ResidentKeyRequirement.DISCOURAGED)
            .build()
        )
        .build()
    )
    pendingRegistrations.put(token, (user.id, options))
    options.toCredentialsCreateJson()

  /** Complete a registration ceremony. Returns the new credential on success. */
  def finishRegistration(token: String, responseJson: String): Either[String, WebAuthnCredential] =
    pendingRegistrations.get(token) match
      case None => Left("Registration session not found or expired")
      case Some((_, options)) =>
        try
          val response = PublicKeyCredential.parseRegistrationResponseJson(responseJson)
          val result = rp.finishRegistration(
            FinishRegistrationOptions.builder()
              .request(options)
              .response(response)
              .build()
          )
          pendingRegistrations.remove(token)
          Right(WebAuthnCredential(
            id        = result.getKeyId.getId.getBase64Url,
            publicKey = result.getPublicKeyCose.getBase64Url,
            signCount = result.getSignatureCount,
            addedAt   = java.time.Instant.now().toString
          ))
        catch
          case e: RegistrationFailedException =>
            scribe.warn(s"WebAuthn registration failed: ${e.getMessage}")
            Left(s"Registration failed: ${e.getMessage}")
          case e: Exception =>
            scribe.warn(s"WebAuthn registration error: ${e.getMessage}")
            Left("Internal error during registration")

  /** Start an assertion ceremony. Returns JSON to pass to navigator.credentials.get(). */
  def beginAuthentication(token: String, user: UserInfo): String =
    val assertionRequest = rp.startAssertion(
      StartAssertionOptions.builder()
        .username(Optional.of(user.name))
        .build()
    )
    pendingAssertions.put(token, assertionRequest)
    assertionRequest.toCredentialsGetJson()

  /** Complete an assertion ceremony.
    * Returns (credentialId, newSignCount) on success so the caller can update the stored count.
    */
  def finishAuthentication(token: String, responseJson: String): Either[String, (String, Long)] =
    pendingAssertions.get(token) match
      case None => Left("Authentication session not found or expired")
      case Some(assertionRequest) =>
        try
          val response = PublicKeyCredential.parseAssertionResponseJson(responseJson)
          val result = rp.finishAssertion(
            FinishAssertionOptions.builder()
              .request(assertionRequest)
              .response(response)
              .build()
          )
          pendingAssertions.remove(token)
          if result.isSuccess then
            Right((result.getCredential.getCredentialId.getBase64Url, result.getSignatureCount))
          else
            Left("Authentication was not successful")
        catch
          case e: AssertionFailedException =>
            scribe.warn(s"WebAuthn assertion failed: ${e.getMessage}")
            Left(s"Authentication failed: ${e.getMessage}")
          case e: Exception =>
            scribe.warn(s"WebAuthn authentication error: ${e.getMessage}")
            Left("Internal error during authentication")
