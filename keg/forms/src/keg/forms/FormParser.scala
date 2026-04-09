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
      java.net.URLDecoder.decode(s, "UTF-8")
    catch
      case e: IllegalArgumentException =>
        throw new InvalidFormEncodingException(s"Invalid URL encoding")

  def parseUrlEncoded[R](
    body: InputStream,
    visitor: FormVisitor[R],
    maxSize: Long = 30*1024, // 30KB, should be enough for most cases and prevents DoS with large form data
  ): R =
    val buffer = new StringBuilder
    var bytesRead = 0L

    var currentKey: String = null
    var byte = body.read()

    while byte != -1 do
      bytesRead += 1

      if bytesRead > maxSize then
        throw new FormTooLargeException(bytesRead, maxSize)

      byte match
        case '&' =>
          if currentKey != null then
            val value = buffer.toString()
            val decodedValue = decodeUrl(value)
            visitor.visitField(name = currentKey, value = decodedValue)
            currentKey = null
            buffer.clear()
            byte = body.read()
          else if buffer.nonEmpty then
            val key = buffer.toString()
            val decodedKey = decodeUrl(key)
            visitor.visitField(name = decodedKey, value = "")
            buffer.clear()
            byte = body.read()
        case '=' =>
          currentKey = decodeUrl(buffer.toString())
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
      visitor.visitField(name = currentKey, value = decodedValue)
    else if buffer.nonEmpty then
      val key = buffer.toString()
      val decodedKey = decodeUrl(key)
      visitor.visitField(name = decodedKey, value = "")
    visitor.visitEnd()
  end parseUrlEncoded

  def parseMultipart[R](
    body: InputStream,
    boundary: String,
    visitor: FormVisitor[R],
    maxSize: Long = 10*1024, // max total bytes for all non-file content (headers + field values)
    maxFileSize: Long = 100*1024*1024, // max bytes per file part
  ): R =
    val boundaryBytes = s"--$boundary".getBytes(US_ASCII)

    var byte = body.read()

    // Tracks bytes consumed for non-file content (headers, field values, boundary overhead)
    var nonFileBytesRead = 0L

    val buffer = new ByteArrayOutputStream()

    var name: String = ""
    var fileName: String = null

    // Reads the next byte from body, counting it against the non-file limit
    def readNext(): Unit =
      byte = body.read()
      nonFileBytesRead += 1
      if nonFileBytesRead > maxSize then
        throw new FormTooLargeException(nonFileBytesRead, maxSize)

    // Tries to consume the given string exactly. Reads the first char from the
    // current byte, not calling readNext first. Returns false immediately if
    // any char doesn't match.
    def expect(str: String): Boolean =
      var i = 0
      while i < str.length && byte != -1 do
        if byte != str.charAt(i) then return false
        i += 1
        readNext()
      i == str.length

    // Like expect, but matches ASCII letters case-insensitively.
    def expectCI(str: String): Boolean =
      var i = 0
      while i < str.length && byte != -1 do
        if byte.toChar.toLower != str.charAt(i).toLower then return false
        i += 1
        readNext()
      i == str.length

    def parseHeaders(): Unit =
      buffer.reset()
      // Skip any header lines before Content-Disposition
      while !expectCI("Content-Disposition: form-data; ") do
        if byte == -1 || byte == '\r' then
          throw new InvalidFormEncodingException("Expected Content-Disposition header")
        while byte != -1 && byte != '\r' do
          readNext()
        if byte == '\r' then readNext()
        if byte == '\n' then readNext()

      if !expectCI("name=\"") then
        throw new InvalidFormEncodingException("Expected name parameter in Content-Disposition")
      buffer.reset()
      while byte != -1 && byte != '"' do
        buffer.write(byte)
        readNext()
      name = decodeUrl(buffer.toString(UTF_8))
      readNext() // consume closing "

      // Optional filename parameter: either filename="..." (raw UTF-8) or
      // filename*=charset'language'percent-encoded (RFC 5987)
      if expectCI("; filename") then
        if expect("*=") then
          // RFC 5987: charset'language'percent-encoded-value
          buffer.reset()
          while byte != -1 && byte != '\'' do
            buffer.write(byte)
            readNext()
          val charset = buffer.toString(US_ASCII).trim
          readNext() // consume first '
          while byte != -1 && byte != '\'' do // skip language tag
            readNext()
          readNext() // consume second '
          buffer.reset()
          while byte != -1 && byte != '\r' && byte != ';' do
            buffer.write(byte)
            readNext()
          fileName = java.net.URLDecoder.decode(buffer.toString(US_ASCII), charset)
        else if expect("=\"") then
          // Quoted filename: raw UTF-8 bytes, not URL-encoded
          buffer.reset()
          while byte != -1 && byte != '"' do
            buffer.write(byte)
            readNext()
          fileName = buffer.toString(UTF_8)
          readNext() // consume closing "
        else
          throw new InvalidFormEncodingException("Expected filename parameter value")
      else
        fileName = null

      if !expect("\r\n") then
        throw new InvalidFormEncodingException("Expected CRLF after Content-Disposition")

      // Skip any remaining headers (e.g. Content-Type, custom headers) until blank line
      while byte != -1 && byte != '\r' do
        while byte != -1 && byte != '\r' do
          readNext()
        if byte == '\r' then readNext() // consume \r
        if byte == '\n' then readNext() // consume \n

      if !expect("\r\n") then
        throw new InvalidFormEncodingException("Expected blank line after headers")
    end parseHeaders

    // Parse part content until the next boundary, writing bytes to `out`.
    // `limit` is the max number of bytes that may be written; exceeding it
    // throws FormTooLargeException. Returns true if the final boundary was
    // reached, false if more parts follow.
    def parseContent(out: java.io.OutputStream, limit: Long): Boolean =
      var bytesWritten = 0L

      inline def writeByte(b: Int): Unit =
        if bytesWritten >= limit then
          throw new FormTooLargeException(bytesWritten + 1, limit)
        out.write(b)
        bytesWritten += 1

      inline def writeBytes(bytes: Array[Byte], offset: Int, length: Int): Unit =
        if bytesWritten + length > limit then
          throw new FormTooLargeException(bytesWritten + length, limit)
        out.write(bytes, offset, length)
        bytesWritten += length

      var matchedBoundary = false
      while !matchedBoundary && byte != -1 do
        while byte != -1 && byte != '\r' do
          writeByte(byte)
          byte = body.read() // file bytes are not counted against nonFileBytesRead
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
              // false alarm: the CRLF + partial boundary bytes belong to content
              writeByte('\r')
              writeByte('\n')
              writeBytes(boundaryBytes, 0, i)
          else
            writeByte('\r')
        end if
      end while

      if !matchedBoundary then
        throw new InvalidFormEncodingException("Unexpected end of stream while looking for boundary")

      // Boundary suffix: "--\r\n" means final, "\r\n" means more parts follow
      if expect("--") then
        if !expect("\r\n") then
          throw new InvalidFormEncodingException("Expected CRLF after final boundary")
        true
      else if expect("\r\n") then
        false
      else
        throw new InvalidFormEncodingException("Expected CRLF or -- after boundary")
    end parseContent

    if !expect(s"--$boundary\r\n") then
      throw new InvalidFormEncodingException("Expected starting boundary")

    var done = false
    while !done && byte != -1 do
      parseHeaders()

      if fileName != null then
        // File part: stream content to the visitor's output, limited per file
        val out = visitor.visitFileBegin(name, fileName)
        try
          done = parseContent(out, maxFileSize)
        finally
          visitor.visitFileEnd()
      else
        // Plain field: collect value into buffer, limited by remaining non-file budget
        buffer.reset()
        done = parseContent(buffer, maxSize - nonFileBytesRead)
        nonFileBytesRead += buffer.size()
        visitor.visitField(name, buffer.toString(UTF_8))
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
    contentType: String,
    visitor: FormVisitor[R],
    maxSize: Long = 30*1024, // 10KB, should be enough for most cases and prevents DoS with large form data
    maxFileSize: Long = 100*1024*1024, // *total* of all file parts
  ): R =
    contentType match
      case ct if ct.startsWith("application/x-www-form-urlencoded") =>
        parseUrlEncoded(body, visitor, maxSize)

      case ct if ct.startsWith("multipart/form-data") =>
        val boundaryPrefix = "boundary="
        val boundaryIdx = ct.indexOf(boundaryPrefix)
        if boundaryIdx < 0 then
          throw new InvalidFormEncodingException("Missing boundary in Content-Type header")
        val boundary = ct.substring(boundaryIdx + boundaryPrefix.length).trim
        parseMultipart(body, boundary, visitor, maxSize, maxFileSize)

      case _ =>
        throw new InvalidFormEncodingException("Unsupported Content-Type for form parsing")

  def tryParse[R](
    body: InputStream,
    contentType: String,
    visitor: FormVisitor[R],
    maxSize: Long = 30*1024,
    maxFileSize: Long = 100*1024*1024,
  ): Either[FormParseException, R] =
    try
      Right(parse(body, contentType, visitor, maxSize, maxFileSize))
    catch
      case e: FormParseException => Left(e)
