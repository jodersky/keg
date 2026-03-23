package keg.forms

import java.io.InputStream
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.charset.StandardCharsets.US_ASCII
import java.io.ByteArrayOutputStream

// https://url.spec.whatwg.org/#concept-urlencoded
// https://html.spec.whatwg.org/multipage/form-control-infrastructure.html#multipart-form-data
// https://www.rfc-editor.org/rfc/rfc7578


object FormParser:

  sealed trait FormParseException extends Exception

  /** Exception thrown when the form data exceeds the maximum allowed size */
  class FormTooLargeException(val bytesRead: Long, val maxSize: Long)
      extends Exception(s"Form data exceeded maximum size: $bytesRead > $maxSize bytes") with FormParseException

  class InvalidFormEncodingException(message: String) extends Exception(message) with FormParseException

  private inline def decodeUrl(s: String): String =
    try
      java.net.URLDecoder.decode(s, UTF_8)
    catch
      case e: IllegalArgumentException =>
        throw new InvalidFormEncodingException(s"Invalid URL encoding")

  def parseUrlEncoded[R](
    body: InputStream,
    visitor: FormVisitor[R],
    maxNameSize: Long = 512,
  ): R =
    val buffer = new StringBuilder
    var max: Long = maxNameSize
    var bytesRead = 0L

    var currentKey: String = null
    var byte = body.read()

    while byte != -1 do
      bytesRead += 1

      if bytesRead > max then
        throw new FormTooLargeException(bytesRead, max)

      byte match
        case '&' =>
          if currentKey != null then
            val value = buffer.toString()
            val decodedValue = decodeUrl(value)
            visitor.visitFieldValue(decodedValue)
            bytesRead = 0L
            max = maxNameSize
            currentKey = null
            buffer.clear()
            byte = body.read()
          else if buffer.nonEmpty then
            val key = buffer.toString()
            val decodedKey = decodeUrl(key)
            visitor.visitFieldName(decodedKey, null)
            visitor.visitFieldValue("")
            bytesRead = 0L
            max = maxNameSize
            buffer.clear()
            byte = body.read()
        case '=' =>
          currentKey = buffer.toString()
          max = visitor.visitFieldName(decodeUrl(currentKey), null)
          if max < 0 then
            throw new InvalidFormEncodingException("Field with streaming not supported in url-encoded forms")
          bytesRead = 0L
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
      val decodedValue = decodeUrl(value)
      visitor.visitFieldValue(decodedValue)
    else if buffer.nonEmpty then
      val key = buffer.toString()
      val decodedKey = decodeUrl(key)
      visitor.visitFieldName(decodedKey, null)
      visitor.visitFieldValue("")

    visitor.visitEnd()
  end parseUrlEncoded

  def parseMultipart[R](
    body: InputStream,
    boundary: String,
    visitor: FormVisitor[R],
    maxMetaSize: Long = 512, // field name and headers
  ): R =
    val boundaryBytes = s"--$boundary".getBytes(US_ASCII)

    var byte = body.read()

    var partBytesRead = 0L

    val buffer = new ByteArrayOutputStream()

    var name: String = ""
    var fileName: String = null
    var contentType: String = ""

    def readNext(): Unit =
      byte = body.read()
      partBytesRead += 1
      if partBytesRead > maxMetaSize then
        throw new FormTooLargeException(partBytesRead, maxMetaSize)

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
      partBytesRead = 0L
      buffer.reset()
      if !expect("Content-Disposition: form-data; ") then
        throw new InvalidFormEncodingException("Expected Content-Disposition header")

      if !expect("name=\"") then
        throw new InvalidFormEncodingException("Expected name parameter in Content-Disposition")
      buffer.reset()
      while byte != -1 && byte != '"' do
        buffer.write(byte)
        readNext()
      name = decodeUrl(buffer.toString(UTF_8))
      readNext() // consume closing "

      if expect("; filename=\"") then
        buffer.reset()
        while byte != -1 && byte != '"' do
          buffer.write(byte)
          readNext()
        fileName = decodeUrl(buffer.toString(UTF_8))
        readNext() // consume closing "
      else
        fileName = null

      if !expect("\r\n") then
        throw new InvalidFormEncodingException("Expected CRLF after Content-Disposition")

      if expect("Content-Type: ") then
        buffer.reset()
        while byte != -1 && byte != '\r' do
          buffer.write(byte)
          readNext()
        contentType = buffer.toString(UTF_8).trim
        // consume CRLF
        if !expect("\r\n") then
          throw new InvalidFormEncodingException("Expected CRLF after Content-Type")

      if !expect("\r\n") then
        throw new InvalidFormEncodingException("Expected blank line after headers")
    end parseHeaders

    var stream: java.io.OutputStream = null
    var streamPos: Long = 0
    var maxContentBytes = 0L

    inline def writeByte(b: Int): Unit =
      if streamPos >= maxContentBytes then
        throw new FormTooLargeException(streamPos, maxContentBytes)
      stream.write(b)
      streamPos += 1

    inline def writeBytes(bytes: Array[Byte], offset: Int, length: Int): Unit =
      if streamPos + length > maxContentBytes then
        throw new FormTooLargeException(streamPos + length, maxContentBytes)
      stream.write(bytes, offset, length)
      streamPos += length

    // Parse content until the next boundary, returns true if final boundary
    // reached
    def parseContent(): Boolean =
      var matchedBoundary = false
      while !matchedBoundary && byte != -1 do
        while byte != -1 && byte != '\r' do
          writeByte(byte)
          byte = body.read() // no readNext here, as part bytes are not counted
        if byte == '\r' then
          byte = body.read() // consume '\r'
          if byte == '\n' then
            byte = body.read() // consume '\n'
            var i = 0
            matchedBoundary = true
            while matchedBoundary && i < boundaryBytes.length && byte != -1 do
              if byte != boundaryBytes(i) then
                matchedBoundary = false
              else
                i += 1
                byte = body.read()
            if !matchedBoundary then
              // false alarm, write bytes to output
              writeByte('\r')
              writeByte('\n')
              writeBytes(boundaryBytes, 0, i)
          else
            writeByte('\r')
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

    partBytesRead = 0L
    if !expect(s"--$boundary\r\n") then
      throw new InvalidFormEncodingException("Expected starting boundary")
    while byte != -1 do
      parseHeaders()

      maxContentBytes = visitor.visitFieldName(name, fileName)

      if maxContentBytes < 0 then
        maxContentBytes = -maxContentBytes
        // multipart with streaming
        stream = visitor.visitPartBegin()
        streamPos = 0L
        try
          parseContent()
        finally
          visitor.visitPartEnd()
      else
        // multipart with buffered content
        buffer.reset()
        stream = buffer
        streamPos = 0L
        parseContent()
        val value = buffer.toString(UTF_8)
        visitor.visitFieldValue(value)

    end while

    visitor.visitEnd()
  end parseMultipart

  /** Parses form data from the given input stream and headers, invoking the
    * provided visitor methods for each field name and value encountered.
    * Supports both url-encoded and multipart form data.
    *
    * @param body the input stream containing the form data (url-encoded or
    * multipart)
    * @param headers the HTTP headers associated with the form submission, used
    * to determine content type
    * @param visitor the visitor that will be called for each field name and
    * value parsed from the form data
    * @param maxMetaSize the maximum allowed size for field names and headers
    * (max size of field values is determined by the visitor's return value for
    * each field name)
    *
    * @throws FormTooLargeException if any field name or value exceeds the specified maximum size
    * @throws InvalidFormEncodingException if the form data is not properly encoded according to the expected format
    * @returns the result produced by the visitor after parsing is complete
    */
  def parse[R](
    body: InputStream,
    headers: Map[String, String],
    visitor: FormVisitor[R],
    maxMetaSize: Long = 512,
  ): R =
    headers.get("content-type") match
      case Some(ct) if ct.startsWith("application/x-www-form-urlencoded") =>
        parseUrlEncoded(body, visitor, maxMetaSize)

      case Some(ct) if ct.startsWith("multipart/form-data") =>
        val boundaryPrefix = "boundary="
        val boundaryIdx = ct.indexOf(boundaryPrefix)
        if boundaryIdx < 0 then
          throw new InvalidFormEncodingException("Missing boundary in Content-Type header")
        val boundary = ct.substring(boundaryIdx + boundaryPrefix.length).trim
        parseMultipart(body, boundary, visitor, maxMetaSize)

      case _ =>
        throw new InvalidFormEncodingException("Unsupported Content-Type for form parsing")

  def tryParse[R](
    body: InputStream,
    headers: Map[String, String],
    visitor: FormVisitor[R],
    maxMetaSize: Long = 512,
  ): Either[FormParseException, R] =
    try
      Right(parse(body, headers, visitor, maxMetaSize))
    catch
      case e: FormParseException => Left(e)
