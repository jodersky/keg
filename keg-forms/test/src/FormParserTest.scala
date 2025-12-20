import utest.*
import keg.forms.FormParser
import keg.forms.FormPart
import java.io.ByteArrayInputStream
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}

object FormParserTest extends TestSuite:

  val tempDir = Files.createTempDirectory("form-parser-test")

  override def utestAfterAll(): Unit =
    def deleteRecursively(path: Path): Unit =
      if Files.isDirectory(path) then
        val stream = Files.newDirectoryStream(path)
        try
          stream.forEach(child => deleteRecursively(child))
        finally
          stream.close()
      Files.delete(path)
    deleteRecursively(tempDir)

  val tests = Tests:

    test("parseUrlEncoded"):
      test("basic parsing"):
        val input = "name=John&age=30&city=NYC"
        val stream = new ByteArrayInputStream(input.getBytes(StandardCharsets.UTF_8))
        val result = FormParser.parseUrlEncoded(stream)
        assert(result == Seq(FormPart.Field("name", "John"), FormPart.Field("age", "30"), FormPart.Field("city", "NYC")))

      test("empty values"):
        val input = "key1=&key2=value2&key3="
        val stream = new ByteArrayInputStream(input.getBytes(StandardCharsets.UTF_8))
        val result = FormParser.parseUrlEncoded(stream)
        assert(result == Seq(FormPart.Field("key1", ""), FormPart.Field("key2", "value2"), FormPart.Field("key3", "")))

      test("keys without values"):
        val input = "key1&key2&key3"
        val stream = new ByteArrayInputStream(input.getBytes(StandardCharsets.UTF_8))
        val result = FormParser.parseUrlEncoded(stream)
        assert(result == Seq(FormPart.Field("key1", ""), FormPart.Field("key2", ""), FormPart.Field("key3", "")))

      test("URL-encoded special characters"):
        val input = "name=John+Doe&email=john%40example.com&message=Hello%20World%21"
        val stream = new ByteArrayInputStream(input.getBytes(StandardCharsets.UTF_8))
        val result = FormParser.parseUrlEncoded(stream)
        assert(result == Seq(FormPart.Field("name", "John Doe"), FormPart.Field("email", "john@example.com"), FormPart.Field("message", "Hello World!")))

      test("equals sign in value"):
        val input = "equation=a%3Db%2Bc"
        val stream = new ByteArrayInputStream(input.getBytes(StandardCharsets.UTF_8))
        val result = FormParser.parseUrlEncoded(stream)
        assert(result == Seq(FormPart.Field("equation", "a=b+c")))

      test("duplicate keys"):
        val input = "tag=scala&tag=java&tag=python"
        val stream = new ByteArrayInputStream(input.getBytes(StandardCharsets.UTF_8))
        val result = FormParser.parseUrlEncoded(stream)
        assert(result == Seq(FormPart.Field("tag", "scala"), FormPart.Field("tag", "java"), FormPart.Field("tag", "python")))

      test("empty input"):
        val input = ""
        val stream = new ByteArrayInputStream(input.getBytes(StandardCharsets.UTF_8))
        val result = FormParser.parseUrlEncoded(stream)
        assert(result.isEmpty)

      test("size limit enforcement"):
        val input = "a" * 2000
        val stream = new ByteArrayInputStream(input.getBytes(StandardCharsets.UTF_8))
        try
          FormParser.parseUrlEncoded(stream, maxSize = 1000)
          sys.error("Should have thrown FormTooLargeException")
        catch
          case e: FormParser.FormTooLargeException =>
            assert(e.bytesRead > 1000)
            assert(e.maxSize == 1000)

      test("size limit enforcement 2"):
        val input = "a" * 1001
        val stream = new ByteArrayInputStream(input.getBytes(StandardCharsets.UTF_8))
        try
          FormParser.parseUrlEncoded(stream, maxSize = 1000)
          sys.error("Should have thrown FormTooLargeException")
        catch
          case e: FormParser.FormTooLargeException =>
            assert(e.bytesRead > 1000)
            assert(e.maxSize == 1000)

      test("size limit"):
        val input = "a" * 1000
        val stream = new ByteArrayInputStream(input.getBytes(StandardCharsets.UTF_8))
        assert(FormParser.parseUrlEncoded(stream, maxSize = 1000).nonEmpty)

      test("garbage input"):
        val input = "%%%&&&==="
        val stream = new ByteArrayInputStream(input.getBytes(StandardCharsets.UTF_8))
        assertThrows[FormParser.InvalidFormEncodingException](FormParser.parseUrlEncoded(stream))

      test("encoded unicode input"):
        val input = "%E4%BD%A0%E5%A5%BD%E4%B8%96%E7%95%8C=%E2%87%94"
        val stream = new ByteArrayInputStream(input.getBytes(StandardCharsets.UTF_8))
        val result = FormParser.parseUrlEncoded(stream)
        assert(result == Seq(FormPart.Field("你好世界", "⇔")))

      test("encoded ampersand in value"):
        val input = "data=one%26two%26three"
        val stream = new ByteArrayInputStream(input.getBytes(StandardCharsets.UTF_8))
        val result = FormParser.parseUrlEncoded(stream)
        assert(result == Seq(FormPart.Field("data", "one&two&three")))

      test("trailing ampersand"):
        val input = "key1=value1&key2=value2&"
        val stream = new ByteArrayInputStream(input.getBytes(StandardCharsets.UTF_8))
        val result = FormParser.parseUrlEncoded(stream)
        assert(result == Seq(FormPart.Field("key1", "value1"), FormPart.Field("key2", "value2")))

    test("parseMultipart"):
      val dest = FormParser.FileDest.Fs(tempDir)

      test("text fields only"):
        val boundary = "----WebKitFormBoundary7MA4YWxkTrZu0gW"
        val body = (
          "------WebKitFormBoundary7MA4YWxkTrZu0gW\r\n" +
          "Content-Disposition: form-data; name=\"username\"\r\n" +
          "\r\n" +
          "alice\r\n" +
          "------WebKitFormBoundary7MA4YWxkTrZu0gW\r\n" +
          "Content-Disposition: form-data; name=\"email\"\r\n" +
          "\r\n" +
          "alice@example.com\r\n" +
          "------WebKitFormBoundary7MA4YWxkTrZu0gW--\r\n"
        )
        val stream = new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8))
        val result = FormParser.parseMultipart(
          stream, boundary, dest,
          maxSize = 1024 * 1024,
          maxMetaSize = 512,
          maxFieldSize = 10 * 1024,
          maxFileSize = 10 * 1024 * 1024
        )

        assert(result.length == 2)
        result(0) match
          case FormPart.Field(name, value) =>
            assert(name == "username")
            assert(value == "alice")
          case _ => sys.error("Expected Field")

        result(1) match
          case FormPart.Field(name, value) =>
            assert(name == "email")
            assert(value == "alice@example.com")
          case _ => sys.error("Expected Field")

      test("file upload"):
        val boundary = "----WebKitFormBoundary7MA4YWxkTrZu0gW"
        val fileContent = "Hello, this is file content!"
        val body = (
          "------WebKitFormBoundary7MA4YWxkTrZu0gW\r\n" +
          "Content-Disposition: form-data; name=\"document\"; filename=\"test.txt\"\r\n" +
          "Content-Type: text/plain\r\n" +
          "\r\n" +
          fileContent + "\r\n" +
          "------WebKitFormBoundary7MA4YWxkTrZu0gW--\r\n"
        )

        val stream = new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8))
        val result = FormParser.parseMultipart(
          stream, boundary, dest,
          maxSize = 1024 * 1024,
          maxMetaSize = 512,
          maxFieldSize = 10 * 1024,
          maxFileSize = 10 * 1024 * 1024
        )

        assert(result.length == 1)
        result(0) match
          case FormPart.File(name, fileName) =>
            assert(name == "document")
            assert(fileName == "test.txt")
            // Verify file was written
            val safeName = java.util.Base64.getEncoder().encodeToString(fileName.getBytes(StandardCharsets.UTF_8))
            val filePath = tempDir.resolve(safeName)
            assert(Files.exists(filePath))
            val savedContent = new String(Files.readAllBytes(filePath), StandardCharsets.UTF_8)
            assert(savedContent == fileContent)
          case _ => sys.error("Expected File")

      test("mixed fields and files"):
        val boundary = "----WebKitFormBoundary7MA4YWxkTrZu0gW"
        val body = (
          "------WebKitFormBoundary7MA4YWxkTrZu0gW\r\n" +
          "Content-Disposition: form-data; name=\"title\"\r\n" +
          "\r\n" +
          "My Document\r\n" +
          "------WebKitFormBoundary7MA4YWxkTrZu0gW\r\n" +
          "Content-Disposition: form-data; name=\"file\"; filename=\"doc.pdf\"\r\n" +
          "Content-Type: application/pdf\r\n" +
          "\r\n" +
          "PDF binary content here\r\n" +
          "------WebKitFormBoundary7MA4YWxkTrZu0gW--\r\n"
        )

        val stream = new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8))
        val result = FormParser.parseMultipart(
          stream, boundary, dest,
          maxSize = 1024 * 1024,
          maxMetaSize = 512,
          maxFieldSize = 10 * 1024,
          maxFileSize = 10 * 1024 * 1024
        )

        assert(result.length == 2)
        assert(result(0).isInstanceOf[FormPart.Field])
        assert(result(1).isInstanceOf[FormPart.File])

      test("file size limit"):
        val boundary = "----WebKitFormBoundary7MA4YWxkTrZu0gW"
        val largeContent = "x" * 2000
        val body = (
          "------WebKitFormBoundary7MA4YWxkTrZu0gW\r\n" +
          "Content-Disposition: form-data; name=\"bigfile\"; filename=\"large.txt\"\r\n" +
          "Content-Type: text/plain\r\n" +
          "\r\n" +
          largeContent + "\r\n" +
          "------WebKitFormBoundary7MA4YWxkTrZu0gW--\r\n"
        )

        val stream = new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8))
        try
          FormParser.parseMultipart(
            stream, boundary, dest,
            maxSize = 10 * 1024 * 1024,
            maxMetaSize = 512,
            maxFieldSize = 10 * 1024,
            maxFileSize = 1000
          )
          sys.error("Should have thrown FormPartTooLargeException")
        catch
          case e: FormParser.FormPartTooLargeException =>
            assert(e.partName == "bigfile")
            assert(e.partSize > 1000)

      test("field size limit"):
        val boundary = "----WebKitFormBoundary7MA4YWxkTrZu0gW"
        val largeContent = "x" * 2000
        val body = (
          "------WebKitFormBoundary7MA4YWxkTrZu0gW\r\n" +
          "Content-Disposition: form-data; name=\"comment\"\r\n" +
          "\r\n" +
          largeContent + "\r\n" +
          "------WebKitFormBoundary7MA4YWxkTrZu0gW--\r\n"
        )

        val stream = new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8))
        try
          FormParser.parseMultipart(
            stream, boundary, dest,
            maxSize = 10 * 1024 * 1024,
            maxMetaSize = 512,
            maxFieldSize = 1000,
            maxFileSize = 10 * 1024 * 1024
          )
          sys.error("Should have thrown FormPartTooLargeException")
        catch
          case e: FormParser.FormPartTooLargeException =>
            assert(e.partName == "comment")
            assert(e.partSize > 1000)

      test("total size limit"):
        val boundary = "----WebKitFormBoundary7MA4YWxkTrZu0gW"
        val content1 = "a" * 1500
        val content2 = "b" * 1500
        val body = (
          "------WebKitFormBoundary7MA4YWxkTrZu0gW\r\n" +
          "Content-Disposition: form-data; name=\"field1\"\r\n" +
          "\r\n" +
          content1 + "\r\n" +
          "------WebKitFormBoundary7MA4YWxkTrZu0gW\r\n" +
          "Content-Disposition: form-data; name=\"field2\"\r\n" +
          "\r\n" +
          content2 + "\r\n" +
          "------WebKitFormBoundary7MA4YWxkTrZu0gW--\r\n"
        )

        val stream = new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8))
        try
          FormParser.parseMultipart(
            stream, boundary, dest,
            maxSize = 2000,
            maxMetaSize = 512,
            maxFieldSize = 10 * 1024,
            maxFileSize = 10 * 1024 * 1024
          )
          sys.error("Should have thrown FormTooLargeException")
        catch
          case e: FormParser.FormTooLargeException =>
            assert(e.bytesRead > 2000)

      test("missing boundary"):
        val boundary = "----WebKitFormBoundary7MA4YWxkTrZu0gW"
        val body = "This is not multipart data at all"
        val stream = new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8))
        assertThrows[FormParser.InvalidFormEncodingException]:
          FormParser.parseMultipart(
            stream, boundary, dest,
            maxSize = 1024 * 1024,
            maxMetaSize = 512,
            maxFieldSize = 10 * 1024,
            maxFileSize = 10 * 1024 * 1024
          )

      test("special characters in field values"):
        val boundary = "----WebKitFormBoundary7MA4YWxkTrZu0gW"
        val specialValue = "Line1\r\n你好世界\r\nLine3"
        val body = (
          "------WebKitFormBoundary7MA4YWxkTrZu0gW\r\n" +
          "Content-Disposition: form-data; name=\"multiline\"\r\n" +
          "\r\n" +
          specialValue + "\r\n" +
          "------WebKitFormBoundary7MA4YWxkTrZu0gW--\r\n"
        )

        val stream = new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8))
        val result = FormParser.parseMultipart(
          stream, boundary, dest,
          maxSize = 1024 * 1024,
          maxMetaSize = 512,
          maxFieldSize = 10 * 1024,
          maxFileSize = 10 * 1024 * 1024
        )

        assert(result.length == 1)
        result(0) match
          case FormPart.Field(name, value) =>
            assert(name == "multiline")
            assert(value == specialValue)
          case _ => sys.error("Expected Field")



