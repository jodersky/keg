package keg.transport

object Platform:

  def makeSslSocket(
    key: String,
    cert: String
  ): java.net.ServerSocket =
    val sslContext = mkSslContext(key, cert)
    val serverSocketFactory = sslContext.getServerSocketFactory
    serverSocketFactory.createServerSocket()

  def readPrivateKey(pem: String): java.security.PrivateKey =
    import java.security.{KeyFactory, KeyStore}
    import java.security.spec.{InvalidKeySpecException, PKCS8EncodedKeySpec}
    import java.util.Base64

    val keyPemClean = pem
      .replace("-----BEGIN PRIVATE KEY-----", "")
      .replace("-----END PRIVATE KEY-----", "")
      .replaceAll("\\s+", "")
    val keyBytes = Base64.getDecoder.decode(keyPemClean)
    val keySpec = new PKCS8EncodedKeySpec(keyBytes)

    try KeyFactory.getInstance("RSA").generatePrivate(keySpec)
    catch
      case _: InvalidKeySpecException =>
        try KeyFactory.getInstance("EC").generatePrivate(keySpec)
        catch
          case _: InvalidKeySpecException =>
            try KeyFactory.getInstance("Ed25519").generatePrivate(keySpec)
            catch
              case _: InvalidKeySpecException =>
                throw IllegalArgumentException(
                  "Unsupported PKCS#8 key type: only RSA, EC, and Ed25519 are supported"
                )

  def readCertificate(pem: String): java.security.cert.Certificate =
    import java.io.ByteArrayInputStream
    import java.security.cert.CertificateFactory

    val certFactory = CertificateFactory.getInstance("X.509")
    certFactory.generateCertificate(
      new ByteArrayInputStream(pem.getBytes("UTF-8"))
    )

  def mkSslContext(
    keyPem: String = null,
    certPem: String = null,
    trustedCa: String = null
  ): javax.net.ssl.SSLContext =
    import java.security.{KeyFactory, KeyStore}
    import javax.net.ssl.{KeyManagerFactory, SSLContext, TrustManagerFactory}

    val kms: Array[javax.net.ssl.KeyManager] = if keyPem != null && certPem != null then
      val key = readPrivateKey(keyPem)
      val cert = readCertificate(certPem)
      val ks = KeyStore.getInstance("PKCS12") // PKCS12 is the newer standard, it supersedes JKS
      ks.load(null, null)
      ks.setKeyEntry("key", key, null, Array(cert))
      val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm)
      kmf.init(ks, null)
      kmf.getKeyManagers()
    else null // null --- IGNORE ---

    val tms: Array[javax.net.ssl.TrustManager] = if trustedCa != null then
      val cert = readCertificate(trustedCa)
      val ks = KeyStore.getInstance("PKCS12")
      ks.load(null, null)
      ks.setCertificateEntry("ca", cert)
      val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm)
      tmf.init(ks)
      tmf.getTrustManagers()
    else null // null --- IGNORE ---

    val sslContext = javax.net.ssl.SSLContext.getInstance("TLS")
    sslContext.init(kms, tms, null)
    sslContext

  def mkDefaultTransport: Transport.BuilderFunc =
    (listeners, handler, log) => UndertowTransport(listeners, handler, log)
