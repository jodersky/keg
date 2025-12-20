package keg.forms

import java.io.InputStream
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.io.ByteArrayOutputStream

// https://url.spec.whatwg.org/#concept-urlencoded
// https://html.spec.whatwg.org/multipage/form-control-infrastructure.html#multipart-form-data
// https://www.rfc-editor.org/rfc/rfc7578

enum FormPart:
  case Field(name0: String, value: String)
  case File(name0: String, fileName: String) // filename can be used to lookup file on based on FileDest

  // TODO: investigate why name0 can't be called from within the macro, and see how we can avoid these
  // gymnastics
  def name = this match
    case Field(n, _)    => n
    case File(n, _)     => n

case class FormData(
  parts: Seq[FormPart]
)

object FormParser:

  trait FormParserException extends Exception

  /** Exception thrown when the form data exceeds the maximum allowed size */
  class FormTooLargeException(val bytesRead: Long, val maxSize: Long)
      extends Exception(s"Form data exceeded maximum size: $bytesRead > $maxSize bytes") with FormParserException

  class FormPartTooLargeException(val partName: String, val partSize: Long, val maxPartSize: Long)
      extends Exception(s"Form part '$partName' exceeded maximum size: $partSize > $maxPartSize bytes") with FormParserException

  class InvalidFormEncodingException(message: String) extends Exception(message) with FormParserException

  private inline def decodeUrl(s: String): String =
    try
    URLDecoder.decode(s, StandardCharsets.UTF_8)
    catch
      case e: IllegalArgumentException =>
        throw new InvalidFormEncodingException(s"Invalid URL encoding")

  def parseUrlEncoded(
    body: InputStream,
    maxSize: Long = 10 * 1024
  ): Seq[FormPart] =
    val buffer = new StringBuilder
    var bytesRead = 0L
    val result = scala.collection.mutable.ArrayBuffer[FormPart]()

    var currentKey: String = null
    var byte = body.read()

    while byte != -1 do
      bytesRead += Character.charCount(byte)

      if bytesRead > maxSize then
        throw new FormTooLargeException(bytesRead, maxSize)

      byte match
        case '&' =>
          if currentKey != null then
            val value = buffer.toString()
            val decodedKey = decodeUrl(currentKey)
            val decodedValue = decodeUrl(value)
            result += FormPart.Field(decodedKey, decodedValue)
            currentKey = null
            buffer.clear()
            byte = body.read()
          else if buffer.nonEmpty then
            val key = buffer.toString()
            val decodedKey = decodeUrl(key)
            result += FormPart.Field(decodedKey, "")
            buffer.clear()
            byte = body.read()
        case '=' =>
          currentKey = buffer.toString()
          buffer.clear()
          byte = body.read()
        case '%' | '+'  =>
          buffer.append((byte & 0xff).asInstanceOf[Char])
          byte = body.read()
        case n if n < 0 =>
          buffer.append((byte & 0xff).asInstanceOf[Char])
          byte = body.read()
        case n =>
          buffer.append(n.asInstanceOf[Char])
          byte = body.read()
    end while

    // Handle last key-value pair
    if currentKey != null then
      val value = buffer.toString()
      val decodedKey = decodeUrl(currentKey)
      val decodedValue = decodeUrl(value)
      result += FormPart.Field(decodedKey, decodedValue)
    else if buffer.nonEmpty then
      val key = buffer.toString()
      val decodedKey = decodeUrl(key)
      result += FormPart.Field(decodedKey, "")

    result.toSeq

  /** Destination for storing uploaded files */
  trait FileDest:

    /** Create an output stream on which to write the uploaded file.
      *
      * Note: it is the caller's responsibility to close the stream after
      * writing.
      */
    def openStream(filename: String): java.io.OutputStream

  object FsDest:
    class Fs(dir: java.nio.file.Path) extends FileDest:
      override def openStream(filename: String): java.io.OutputStream =
        val safeName = java.util.Base64.getUrlEncoder().encodeToString(filename.getBytes(StandardCharsets.UTF_8))
        val path = dir.resolve(safeName)
        java.nio.file.Files.newOutputStream(path)
    object Null extends FileDest:
      override def openStream(filename: String): java.io.OutputStream =
        java.io.OutputStream.nullOutputStream()

  def parseMultipart(
    body: InputStream,
    boundary: String,
    fileDest: FileDest, // where to store uploaded files
    maxSize: Long, // total size, including files and fields
    maxMetaSize: Long, // max size for part metadata (headers)
    maxFieldSize: Long, // max size for a single field
    maxFileSize: Long, // max size for an individual file
  ): Seq[FormPart] =
    val boundaryBytes = s"--$boundary".getBytes(StandardCharsets.US_ASCII)
    val result = scala.collection.mutable.ArrayBuffer[FormPart]()
    var totalBytesRead = 0L

    // temporary buffer for reading string data
    val buffer = new StringBuilder(128)
    // temporary buffer for reading binary data
    val byteBuffer = new ByteArrayOutputStream(128)

    var byte = body.read()

    var partMaxSize = 0L
    var partBytesRead = 0L

    var name: String = ""
    var fileName: String = null
    var contentType: String = ""

    def readNext(): Unit =
      byte = body.read()
      totalBytesRead += 1
      partBytesRead += 1
      if totalBytesRead > maxSize then
        throw new FormTooLargeException(totalBytesRead, maxSize)
      if partBytesRead > partMaxSize then
        if name == "" then
          throw new FormPartTooLargeException("Metadata", partBytesRead, partMaxSize)
        else
          throw new FormPartTooLargeException(name, partBytesRead, partMaxSize)

    def expect(str: String): Boolean =
      var i = 0
      while i < str.length && byte != -1 do
        if byte != str.charAt(i) then return false
        i += 1
        readNext()
      i == str.length

    // Note: out of simplicity we assume that there are only the following
    // headers, and in this order:
    // 1. Content-Disposition, with name and optional filename
    // 2. optional Content-Type
    // While not strictly conforming to the spec, this should work for most
    // practical cases. The RFC even suggests that only these two headers are
    // commonly used.
    def parseHeaders() =
      buffer.clear()
      if !expect("Content-Disposition: form-data; ") then
        throw new InvalidFormEncodingException("Expected Content-Disposition header")

      if !expect("name=\"") then
        throw new InvalidFormEncodingException("Expected name parameter in Content-Disposition")
      buffer.clear()
      partMaxSize = maxMetaSize
      partBytesRead = 0L
      while byte != -1 && byte != '"' do
        buffer.append(byte.asInstanceOf[Char])
        readNext()
      name = decodeUrl(buffer.toString())
      readNext() // consume closing "

      if expect("; filename=\"") then
        buffer.clear()
        partMaxSize = maxMetaSize
        partBytesRead = 0L
        while byte != -1 && byte != '"' do
          buffer.append(byte.asInstanceOf[Char])
          readNext()
        fileName = decodeUrl(buffer.toString())
        readNext() // consume closing "
      else
        fileName = null

      if !expect("\r\n") then
        throw new InvalidFormEncodingException("Expected CRLF after Content-Disposition")

      if expect("Content-Type: ") then
        buffer.clear()
        partMaxSize = maxMetaSize
        partBytesRead = 0L
        while byte != -1 && byte != '\r' do
          buffer.append(byte.asInstanceOf[Char])
          readNext()
        contentType = buffer.toString().trim
        // consume CRLF
        if !expect("\r\n") then
          throw new InvalidFormEncodingException("Expected CRLF after Content-Type")

      if !expect("\r\n") then
        throw new InvalidFormEncodingException("Expected blank line after headers")
    end parseHeaders

    // Parse content until the next boundary, returns true if final boundary
    // reached
    def parseContent(out: java.io.OutputStream, maxSize: Long): Boolean =
      partMaxSize = maxSize
      partBytesRead = 0L
      var matchedBoundary = false
      while !matchedBoundary && byte != -1 do
        while byte != -1 && byte != '\r' do
          out.write(byte)
          readNext()
        if byte == '\r' then
          readNext() // consume '\r'
          if byte == '\n' then
            readNext() // consume '\n'
            var i = 0
            matchedBoundary = true
            while matchedBoundary && i < boundaryBytes.length && byte != -1 do
              if byte != boundaryBytes(i) then
                matchedBoundary = false
              else
                i += 1
                readNext()
            if !matchedBoundary then
              // false alarm, write bytes to output
              out.write('\r')
              out.write('\n')
              out.write(boundaryBytes, 0, i)
          else
            out.write('\r')
        end if
      end while
      if matchedBoundary then
        if expect("--\r\n") then
          // final boundary
          true
        else if expect("\r\n") then
          // continue to next part
          false
        else
          throw new InvalidFormEncodingException("Expected CRLF or -- after boundary")
      else
        throw new InvalidFormEncodingException("Unexpected end of stream while looking for boundary")
    end parseContent

    partMaxSize = maxMetaSize
    partBytesRead = 0L
    if !expect(s"--$boundary\r\n") then
      throw new InvalidFormEncodingException("Expected starting boundary")
    while byte != -1 do
      parseHeaders()

      if fileName != null then
        val stream: java.io.OutputStream = fileDest.openStream(fileName)
        try
          parseContent(stream, maxFileSize)
        finally
          stream.close()
        result += FormPart.File(name, fileName)
      else
        byteBuffer.reset()
        parseContent(byteBuffer, maxFieldSize)
        val value = new String(byteBuffer.toByteArray(), StandardCharsets.UTF_8) // TODO: handle encoding from headers
        result += FormPart.Field(name, value)
    end while

    result.toSeq

  def parse(
    body: InputStream,
    headers: Map[String, String],
    fileDest: FileDest,
    maxSize: Long = 50 * 1024 * 1024,
    maxMetaSize: Long = 256,
    maxFieldSize: Long = 10 * 1024,
    maxFormSize: Long = 10 * 1024 * 1024
  ): Seq[FormPart] =
    headers.get("content-type") match
      case Some(ct) if ct.startsWith("application/x-www-form-urlencoded") =>
        parseUrlEncoded(body, maxSize)

      case Some(ct) if ct.startsWith("multipart/form-data") =>
        val boundaryPrefix = "boundary="
        val boundaryIdx = ct.indexOf(boundaryPrefix)
        if boundaryIdx < 0 then
          throw new InvalidFormEncodingException("Missing boundary in Content-Type header")
        val boundary = ct.substring(boundaryIdx + boundaryPrefix.length).trim
        parseMultipart(body, boundary, fileDest, maxSize, maxMetaSize, maxFieldSize, maxFormSize)

      case _ =>
        throw new InvalidFormEncodingException("Unsupported Content-Type for form parsing")
