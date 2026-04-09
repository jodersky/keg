package keg.transport

import scala.jdk.CollectionConverters.*
import java.net.Socket
import java.security.Principal
import java.security.PrivateKey
import java.security.cert.X509Certificate
import javax.net.ssl.{X509ExtendedKeyManager, X509TrustManager, SSLEngine}
import javax.net.ssl.SSLContext

class ReloadableKeyManager(initial: X509ExtendedKeyManager) extends X509ExtendedKeyManager:
  private val underlying = new java.util.concurrent.atomic.AtomicReference[X509ExtendedKeyManager](initial)

  def reload(km: X509ExtendedKeyManager): Unit =
    underlying.set(km)

  override def chooseClientAlias(keyType: Array[String], issuers: Array[Principal], socket: Socket): String =
    underlying.get().chooseClientAlias(keyType, issuers, socket)

  override def chooseServerAlias(keyType: String, issuers: Array[Principal], socket: Socket): String =
    underlying.get().chooseServerAlias(keyType, issuers, socket)

  override def getCertificateChain(alias: String): Array[X509Certificate] =
    underlying.get().getCertificateChain(alias)

  override def getClientAliases(keyType: String, issuers: Array[Principal]): Array[String] =
    underlying.get().getClientAliases(keyType, issuers)

  override def getServerAliases(keyType: String, issuers: Array[Principal]): Array[String] =
    underlying.get().getServerAliases(keyType, issuers)

  override def getPrivateKey(alias: String): PrivateKey =
    underlying.get().getPrivateKey(alias)

  override def chooseEngineClientAlias(keyType: Array[String], issuers: Array[Principal], engine: javax.net.ssl.SSLEngine): String =
    underlying.get().chooseEngineClientAlias(keyType, issuers, engine)

  override def chooseEngineServerAlias(keyType: String, issuers: Array[Principal], engine: SSLEngine): String =
    underlying.get().chooseEngineServerAlias(keyType, issuers, engine)

class ReloadableTrustManager(initial: X509TrustManager) extends X509TrustManager:
  private val underlying = new java.util.concurrent.atomic.AtomicReference[X509TrustManager](initial)

  def reload(tm: X509TrustManager): Unit =
    underlying.set(tm)

  override def getAcceptedIssuers: Array[X509Certificate] =
    underlying.get().getAcceptedIssuers

  override def checkClientTrusted(chain: Array[X509Certificate], authType: String): Unit =
    underlying.get().checkClientTrusted(chain, authType)

  override def checkServerTrusted(chain: Array[X509Certificate], authType: String): Unit =
    underlying.get().checkServerTrusted(chain, authType)

object Ssl:
  import java.nio.file.Path
  import java.nio.file.Files

  private def readPrivateKey(pem: String): PrivateKey =
    val keyPemClean = pem
      .replace("-----BEGIN PRIVATE KEY-----", "")
      .replace("-----END PRIVATE KEY-----", "")
      .replaceAll("\\s+", "")
    val keyBytes = java.util.Base64.getDecoder.decode(keyPemClean)
    val keySpec = new java.security.spec.PKCS8EncodedKeySpec(keyBytes)

    val algoTypes = Seq("RSA", "EC", "Ed25519").iterator
    var key: PrivateKey = null
    while key == null && algoTypes.hasNext do
      val algo = algoTypes.next()
      try key = java.security.KeyFactory.getInstance(algo).generatePrivate(keySpec)
      catch
        case _: java.security.spec.InvalidKeySpecException => // try the next algorithm
    if key == null then
      throw IllegalArgumentException(
        "Unsupported PKCS#8 key type: only RSA, EC, and Ed25519 are supported"
      )
    key

  private def readCertificate(pem: String): X509Certificate =
    val certFactory = java.security.cert.CertificateFactory.getInstance("X.509")
    certFactory.generateCertificate(
      new java.io.ByteArrayInputStream(pem.getBytes("UTF-8"))
    ).asInstanceOf[X509Certificate]

  private def keyManagerFromPem(keyPem: String, certPem: String): X509ExtendedKeyManager =
    val key = readPrivateKey(keyPem)
    val cert = readCertificate(certPem)
    val ks = java.security.KeyStore.getInstance("PKCS12") // PKCS12 is the newer standard, it supersedes JKS
    ks.load(null, null)
    ks.setKeyEntry("key", key, null, Array(cert))
    val kmf = javax.net.ssl.KeyManagerFactory.getInstance(javax.net.ssl.KeyManagerFactory.getDefaultAlgorithm)
    kmf.init(ks, null)
    kmf.getKeyManagers().head.asInstanceOf[X509ExtendedKeyManager]

  private def trustManagerFromPem(caPem: String): X509TrustManager =
    val cert = readCertificate(caPem)
    val ks = java.security.KeyStore.getInstance("PKCS12")
    ks.load(null, null)
    ks.setCertificateEntry("ca", cert)
    val tmf = javax.net.ssl.TrustManagerFactory.getInstance(javax.net.ssl.TrustManagerFactory.getDefaultAlgorithm)
    tmf.init(ks)
    tmf.getTrustManagers().head.asInstanceOf[X509TrustManager]

  private def load(
    keyPath: Path,
    certPath: Path,
    clientCaPath: Option[Path] = None,
  ): (X509ExtendedKeyManager, Option[X509TrustManager]) =
    val keyPem = new String(Files.readAllBytes(keyPath), "UTF-8")
    val certPem = new String(Files.readAllBytes(certPath), "UTF-8")
    val keyManager = keyManagerFromPem(keyPem, certPem)

    val trustManager = clientCaPath.map: path =>
      val caPem = new String(Files.readAllBytes(path), "UTF-8")
      trustManagerFromPem(caPem)

    (keyManager, trustManager)

  private def watch(
    keyPath: Path,
    certPath: Path,
    clientCaPath: Option[Path] = None,
    onChange: () => Unit = () => ()
  ): (ReloadableKeyManager, Option[ReloadableTrustManager]) =
    val (keyManager, trustManager) = load(keyPath, certPath, clientCaPath)
    val reloadableKeyManager = new ReloadableKeyManager(keyManager)
    val reloadableTrustManager = trustManager.map(tm => new ReloadableTrustManager(tm))

    // Watch for both ENTRY_MODIFY (direct file writes) and ENTRY_CREATE
    // (Kubernetes secret rotation, which atomically swaps a ..data symlink
    // rather than modifying files in-place).
    val watchKinds = Array(
      java.nio.file.StandardWatchEventKinds.ENTRY_CREATE,
      java.nio.file.StandardWatchEventKinds.ENTRY_MODIFY,
    )
    val watcher = java.nio.file.FileSystems.getDefault.newWatchService()
    (Seq(keyPath, certPath) ++ clientCaPath).map(_.getParent).distinct
      .foreach(_.register(watcher, watchKinds*))

    // Watch for changes in a separate thread. We don't filter by filename
    // because under Kubernetes the changed path is the ..data symlink, not
    // the cert/key filenames themselves.
    val thread = new Thread(() =>
      while true do
        val key = watcher.take()
        val hasRealEvent = key.pollEvents().asScala
          .exists(_.kind() != java.nio.file.StandardWatchEventKinds.OVERFLOW)
        if hasRealEvent then
          try
            val (newKeyManager, newTrustManager) = load(keyPath, certPath, clientCaPath)
            reloadableKeyManager.reload(newKeyManager)
            reloadableTrustManager.foreach(_.reload(newTrustManager.get))
            onChange()
          catch
            case ex: Exception =>
              println(s"Error reloading SSL context: ${ex.getMessage}")
        key.reset(),
      "sslwatcher"
    )
    thread.setDaemon(true)
    thread.start()

    (reloadableKeyManager, reloadableTrustManager)

  def mkSslContext(
    keyPath: Path,
    certPath: Path ,
    clientCaPath: Option[Path] = None,
    onChange: () => Unit = () => ()
  ): SSLContext =
    val (km, tm) = watch(keyPath, certPath, clientCaPath, onChange)
    val tms = tm match
      case Some(tm) => Array[javax.net.ssl.TrustManager](tm)
      case None => Array.empty[javax.net.ssl.TrustManager] // empty array disables client cert validation
    val sslContext = SSLContext.getInstance("TLS")
    sslContext.init(Array(km), tms, null)
    sslContext

  def extractClientCert(sslSession: javax.net.ssl.SSLSession): Option[keg.model.ClientCertificate] =
    try
      val certs = sslSession.getPeerCertificates()
      if certs == null || certs.isEmpty then None
      else
        val x509 = certs.head.asInstanceOf[java.security.cert.X509Certificate]
        val commonName = x509.getSubjectX500Principal.getName()

        val altNames =
          val raw = x509.getSubjectAlternativeNames()
          if raw == null then Seq.empty
          else
            raw.asScala.toSeq.flatMap: entry =>
              val tpe = entry.get(0).asInstanceOf[Int]
              val value = entry.get(1).toString
              val altNameType = tpe match
                case 1 => Some(keg.model.ClientCertificate.AltNameType.Email)
                case 2 => Some(keg.model.ClientCertificate.AltNameType.DNS)
                case 6 => Some(keg.model.ClientCertificate.AltNameType.URI)
                case 7 => Some(keg.model.ClientCertificate.AltNameType.IP)
                case _ => Some(keg.model.ClientCertificate.AltNameType.Other)
              altNameType.map(_ -> value)
        Some(keg.model.ClientCertificate(commonName, altNames))
    catch
      case _: javax.net.ssl.SSLPeerUnverifiedException => None
