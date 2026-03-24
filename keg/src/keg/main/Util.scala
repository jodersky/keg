package keg.main

// Copied from Cask, by Li Haoyi

import java.io.{InputStream, PrintWriter, StringWriter}

import scala.collection.generic.CanBuildFrom
import scala.collection.mutable
import java.io.OutputStream

import scala.annotation.switch
import scala.concurrent.{ExecutionContext, Future, Promise}

import keg.util.MultiDict

object Util {
  def firstFutureOf[T](futures: Seq[Future[T]])(implicit ec: ExecutionContext) = {
    val p = Promise[T]
    futures.foreach(_.foreach(p.trySuccess))
    p.future
  }
  /**
    * Convert a string to a C&P-able literal. Basically
    * copied verbatim from the uPickle source code.
    */
  def literalize(s: IndexedSeq[Char], unicode: Boolean = true) = {
    val sb = new StringBuilder
    sb.append('"')
    var i = 0
    val len = s.length
    while (i < len) {
      (s(i): @switch) match {
        case '"' => sb.append("\\\"")
        case '\\' => sb.append("\\\\")
        case '\b' => sb.append("\\b")
        case '\f' => sb.append("\\f")
        case '\n' => sb.append("\\n")
        case '\r' => sb.append("\\r")
        case '\t' => sb.append("\\t")
        case c =>
          if (c < ' ' || (c > '~' && unicode)) sb.append("\\u%04x" format c.toInt)
          else sb.append(c)
      }
      i += 1
    }
    sb.append('"')

    sb.result()
  }

  def transferTo(in: InputStream, out: OutputStream) = {
    val buffer = new Array[Byte](8192)

    while ({
      in.read(buffer) match{
        case -1 => false
        case n =>
          out.write(buffer, 0, n)
          true
      }
    }) ()
  }
  def pluralize(s: String, n: Int) = {
    if (n == 1) s else s + "s"
  }

  /**
    * Splits a string into path segments; automatically removes all
    * leading/trailing slashes, and ignores empty path segments.
    *
    * Written imperatively for performance since it's used all over the place.
    */
  def splitPath(p: String): collection.IndexedSeq[String] = {
    val pLength = p.length
    var i = 0
    while(i < pLength && p(i) == '/') i += 1
    var segmentStart = i
    val out = mutable.ArrayBuffer.empty[String]

    def complete() = {
      if (i != segmentStart) {
        val s = p.substring(segmentStart, i)
        out += s
      }
      segmentStart = i + 1
    }

    while(i < pLength){
      if (p(i) == '/') complete()
      i += 1
    }
    complete()
    out
  }

  def stackTraceString(e: Throwable) = {
    val trace = new StringWriter()
    val pw = new PrintWriter(trace)
    e.printStackTrace(pw)
    pw.flush()
    trace.toString
  }
  def softWrap(s: String, leftOffset: Int, maxWidth: Int) = {
    val oneLine = s.linesIterator.mkString(" ").split(' ')

    lazy val indent = " " * leftOffset

    val output = new StringBuilder(oneLine.head)
    var currentLineWidth = oneLine.head.length
    for(chunk <- oneLine.tail){
      val addedWidth = currentLineWidth + chunk.length + 1
      if (addedWidth > maxWidth){
        output.append("\n" + indent)
        output.append(chunk)
        currentLineWidth = chunk.length
      } else{
        currentLineWidth = addedWidth
        output.append(' ')
        output.append(chunk)
      }
    }
    output.mkString
  }

  def decodeQueryString(qstring: String, maxParams: Int = 1024): MultiDict[String, String] =
    import collection.mutable as m
    import java.net.URLDecoder.decode as urlDecode

    val params = m.Map[String, m.ListBuffer[String]]()

    val l = qstring.length
    var i = 0

    var name: String = null
    var value: String = null

    def readName() =
      var start = i
      while i < l && qstring.charAt(i) != '=' && qstring.charAt(i) != '&' && qstring.charAt(i) != '#' do
        i += 1
      name = urlDecode(qstring.substring(start, i), "UTF-8")

    def readValue() =
      var start = i
      while i < l && qstring.charAt(i) != '&' && qstring.charAt(i) != '#' do
        i += 1
      value = urlDecode(qstring.substring(start, i), "UTF-8")

    var paramsRead = 0
    while i < l && paramsRead < maxParams do
      readName()
      if i < l && qstring.charAt(i) == '=' then
        i += 1
        readValue()
      else
        value = ""
      val values = params.getOrElseUpdate(name, m.ListBuffer[String]())
      values += value
      paramsRead += 1
      if i < l && qstring.charAt(i) == '&' then
        i += 1
      else if i < l && qstring.charAt(i) == '#' then
        i = l

    MultiDict(params)

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

}
