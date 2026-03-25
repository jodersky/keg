import utest.*
import keg.forms.FormParser
import java.io.ByteArrayInputStream
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}

object FormParserTest extends TestSuite:

  val tests = Tests:

    test("parseUrlEncoded"):
      test("basic parsing"):
        val input = "name=John&age=30&city=NYC"
        val stream = new ByteArrayInputStream(input.getBytes(StandardCharsets.UTF_8))
        val result = FormParser.parseUrlEncoded(stream, TestVisitor())
        assert(result == Seq(("name", "John"), ("age", "30"), ("city", "NYC")))

      test("empty values"):
        val input = "key1=&key2=value2&key3="
        val stream = new ByteArrayInputStream(input.getBytes(StandardCharsets.UTF_8))
        val result = FormParser.parseUrlEncoded(stream, TestVisitor())
        assert(result == Seq(("key1", ""), ("key2", "value2"), ("key3", "")))

      test("keys without values"):
        val input = "key1&key2&key3"
        val stream = new ByteArrayInputStream(input.getBytes(StandardCharsets.UTF_8))
        val result = FormParser.parseUrlEncoded(stream, TestVisitor())
        assert(result == Seq(("key1", ""), ("key2", ""), ("key3", "")))

      test("URL-encoded special characters"):
        val input = "name=John+Doe&email=john%40example.com&message=Hello%20World%21"
        val stream = new ByteArrayInputStream(input.getBytes(StandardCharsets.UTF_8))
        val result = FormParser.parseUrlEncoded(stream, TestVisitor())
        assert(result == Seq(("name", "John Doe"), ("email", "john@example.com"), ("message", "Hello World!")))

      test("equals sign in value"):
        val input = "equation=a%3Db%2Bc"
        val stream = new ByteArrayInputStream(input.getBytes(StandardCharsets.UTF_8))
        val result = FormParser.parseUrlEncoded(stream, TestVisitor())
        assert(result == Seq(("equation", "a=b+c")))

      test("duplicate keys"):
        val input = "tag=scala&tag=java&tag=python"
        val stream = new ByteArrayInputStream(input.getBytes(StandardCharsets.UTF_8))
        val result = FormParser.parseUrlEncoded(stream, TestVisitor())
        assert(result == Seq(("tag", "scala"), ("tag", "java"), ("tag", "python")))

      test("empty input"):
        val input = ""
        val stream = new ByteArrayInputStream(input.getBytes(StandardCharsets.UTF_8))
        val result = FormParser.parseUrlEncoded(stream, TestVisitor())
        assert(result.isEmpty)

      test("size limit enforcement"):
        val input = "a" * 2000
        val stream = new ByteArrayInputStream(input.getBytes(StandardCharsets.UTF_8))
        try
          FormParser.parseUrlEncoded(stream, TestVisitor(), maxSize = 1000)
          sys.error("Should have thrown FormTooLargeException")
        catch
          case e: FormParser.FormTooLargeException =>
            assert(e.bytesRead > 1000)
            assert(e.maxSize == 1000)

      test("size limit enforcement 2"):
        val input = "a" * 1001
        val stream = new ByteArrayInputStream(input.getBytes(StandardCharsets.UTF_8))
        try
          FormParser.parseUrlEncoded(stream, TestVisitor(), maxSize = 1000)
          sys.error("Should have thrown FormTooLargeException")
        catch
          case e: FormParser.FormTooLargeException =>
            assert(e.bytesRead > 1000)
            assert(e.maxSize == 1000)

      test("size limit"):
        val input = "a" * 1000
        val stream = new ByteArrayInputStream(input.getBytes(StandardCharsets.UTF_8))
        assert(FormParser.parseUrlEncoded(stream, TestVisitor(), maxSize = 1000).nonEmpty)

      test("garbage input"):
        val input = "%%%&&&==="
        val stream = new ByteArrayInputStream(input.getBytes(StandardCharsets.UTF_8))
        assertThrows[FormParser.InvalidFormEncodingException](FormParser.parseUrlEncoded(stream, TestVisitor()))

      test("encoded unicode input"):
        val input = "%E4%BD%A0%E5%A5%BD%E4%B8%96%E7%95%8C=%E2%87%94"
        val stream = new ByteArrayInputStream(input.getBytes(StandardCharsets.UTF_8))
        val result = FormParser.parseUrlEncoded(stream, TestVisitor())
        assert(result == Seq(("你好世界", "⇔")))

      test("encoded ampersand in value"):
        val input = "data=one%26two%26three"
        val stream = new ByteArrayInputStream(input.getBytes(StandardCharsets.UTF_8))
        val result = FormParser.parseUrlEncoded(stream, TestVisitor())
        assert(result == Seq(("data", "one&two&three")))

      test("trailing ampersand"):
        val input = "key1=value1&key2=value2&"
        val stream = new ByteArrayInputStream(input.getBytes(StandardCharsets.UTF_8))
        val result = FormParser.parseUrlEncoded(stream, TestVisitor())
        assert(result == Seq(("key1", "value1"), ("key2", "value2")))

      test("multiple fields with same name"):
        val input = "item=apple&item=banana&item=cherry"
        val stream = new ByteArrayInputStream(input.getBytes(StandardCharsets.UTF_8))
        val result = FormParser.parseUrlEncoded(stream, TestVisitor())
        assert(result == Seq(("item", "apple"), ("item", "banana"), ("item", "cherry")))

    // test("parseMultipart"):
    //   test("text fields only"):
    //     val boundary = "----WebKitFormBoundary7MA4YWxkTrZu0gW"
    //     val body = (
    //       "------WebKitFormBoundary7MA4YWxkTrZu0gW\r\n" +
    //       "Content-Disposition: form-data; name=\"username\"\r\n" +
    //       "\r\n" +
    //       "alice\r\n" +
    //       "------WebKitFormBoundary7MA4YWxkTrZu0gW\r\n" +
    //       "Content-Disposition: form-data; name=\"email\"\r\n" +
    //       "\r\n" +
    //       "alice@example.com\r\n" +
    //       "------WebKitFormBoundary7MA4YWxkTrZu0gW--\r\n"
    //     )
    //     val stream = new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8))
    //     val result = FormParser.parseMultipart(
    //       stream, boundary, TestVisitor()
    //     )

    //     assert(result.length == 2)
    //     result(0) match
    //       case (name, value) =>
    //         assert(name == "username")
    //         assert(value == "alice")
    //       case _ => sys.error("Expected Field")

    //     result(1) match
    //       case (name, value) =>
    //         assert(name == "email")
    //         assert(value == "alice@example.com")
    //       case _ => sys.error("Expected Field")

    //   test("file upload"):
    //     val boundary = "----WebKitFormBoundary7MA4YWxkTrZu0gW"
    //     val fileContent = "Hello, this is file content!€"
    //     val body = (
    //       "------WebKitFormBoundary7MA4YWxkTrZu0gW\r\n" +
    //       "Content-Disposition: form-data; name=\"document\"; filename=\"test.txt\"\r\n" +
    //       "Content-Type: text/plain\r\n" +
    //       "\r\n" +
    //       fileContent + "\r\n" +
    //       "------WebKitFormBoundary7MA4YWxkTrZu0gW--\r\n"
    //     )

    //     val stream = new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8))
    //     val result = FormParser.parseMultipart(
    //       stream, boundary, TestVisitor()
    //     )

    //     assert(result.length == 1)
    //     result(0) match
    //       case (name, value) =>
    //         assert(name == "document")
    //         assert(value == fileContent)
    //       case _ => sys.error("Expected File")

    //   test("mixed fields and files"):
    //     val boundary = "----WebKitFormBoundary7MA4YWxkTrZu0gW"
    //     val body = (
    //       "------WebKitFormBoundary7MA4YWxkTrZu0gW\r\n" +
    //       "Content-Disposition: form-data; name=\"title\"\r\n" +
    //       "\r\n" +
    //       "My Document\r\n" +
    //       "------WebKitFormBoundary7MA4YWxkTrZu0gW\r\n" +
    //       "Content-Disposition: form-data; name=\"file\"; filename=\"doc.pdf\"\r\n" +
    //       "Content-Type: application/pdf\r\n" +
    //       "\r\n" +
    //       "PDF binary content here\r\n" +
    //       "------WebKitFormBoundary7MA4YWxkTrZu0gW--\r\n"
    //     )

    //     val stream = new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8))
    //     val result = FormParser.parseMultipart(
    //       stream, boundary, TestVisitor()
    //     )

    //     assert(result.length == 2)
    //     result(0) match
    //       case (name, value) =>
    //         assert(name == "title")
    //         assert(value == "My Document")
    //       case _ => sys.error("Expected Field")
    //     result(1) match
    //       case (name, value) =>
    //         assert(name == "file")
    //         assert(value == "PDF binary content here")
    //       case _ => sys.error("Expected File")

    //   test("file size limit"):
    //     val boundary = "----WebKitFormBoundary7MA4YWxkTrZu0gW"
    //     val largeContent = "x" * 2000
    //     val body = (
    //       "------WebKitFormBoundary7MA4YWxkTrZu0gW\r\n" +
    //       "Content-Disposition: form-data; name=\"bigfile\"; filename=\"large.txt\"\r\n" +
    //       "Content-Type: text/plain\r\n" +
    //       "\r\n" +
    //       largeContent + "\r\n" +
    //       "------WebKitFormBoundary7MA4YWxkTrZu0gW--\r\n"
    //     )

    //     val stream = new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8))
    //     try
    //       FormParser.parseMultipart(
    //         stream, boundary, TestVisitor(),
    //         maxFileSize = 1000
    //       )
    //       sys.error("Should have thrown FormTooLargeException")
    //     catch
    //       case e: FormParser.FormTooLargeException =>

    //   test("missing boundary"):
    //     val boundary = "----WebKitFormBoundary7MA4YWxkTrZu0gW"
    //     val body = "This is not multipart data at all"
    //     val stream = new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8))
    //     assertThrows[FormParser.InvalidFormEncodingException]:
    //       FormParser.parseMultipart(
    //         stream, boundary, TestVisitor()
    //       )

    //   test("special characters in field values"):
    //     val boundary = "----WebKitFormBoundary7MA4YWxkTrZu0gW"
    //     val specialValue = "Line1\r\n你好世界\r\nLine3"
    //     val body = (
    //       "------WebKitFormBoundary7MA4YWxkTrZu0gW\r\n" +
    //       "Content-Disposition: form-data; name=\"multiline\"\r\n" +
    //       "\r\n" +
    //       specialValue + "\r\n" +
    //       "------WebKitFormBoundary7MA4YWxkTrZu0gW--\r\n"
    //     )

    //     val stream = new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8))
    //     val result = FormParser.parseMultipart(
    //       stream, boundary, TestVisitor()
    //     )

    //     assert(result.length == 1)
    //     result(0) match
    //       case (name, value) =>
    //         assert(name == "multiline")
    //         assert(value == specialValue)
    //       case _ => sys.error("Expected Field")

    //   test("special characters in file content"):
    //     val boundary = "----WebKitFormBoundary7MA4YWxkTrZu0gW"
    //     val specialContent = "Binary data with special chars: \r\n\u0000\u0001\u0002"
    //     val body = (
    //       "------WebKitFormBoundary7MA4YWxkTrZu0gW\r\n" +
    //       "Content-Disposition: form-data; name=\"file\"; filename=\"special.bin\"\r\n" +
    //       "Content-Type: application/octet-stream\r\n" +
    //       "\r\n" +
    //       specialContent + "\r\n" +
    //       "------WebKitFormBoundary7MA4YWxkTrZu0gW--\r\n"
    //     )

    //     val stream = new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8))
    //     val result = FormParser.parseMultipart(
    //       stream, boundary, TestVisitor()
    //     )

    //     assert(result.length == 1)
    //     result(0) match
    //       case (name, value) =>
    //         assert(name == "file")
    //         assert(value == specialContent)
    //       case _ => sys.error("Expected File")

    //   test("multiple files with same field name"):
    //     val boundary = "----WebKitFormBoundary7MA4YWxkTrZu0gW"
    //     val body = (
    //       "------WebKitFormBoundary7MA4YWxkTrZu0gW\r\n" +
    //       "Content-Disposition: form-data; name=\"files\"; filename=\"file1.txt\"\r\n" +
    //       "Content-Type: text/plain\r\n" +
    //       "\r\n" +
    //       "Content of file 1\r\n" +
    //       "------WebKitFormBoundary7MA4YWxkTrZu0gW\r\n" +
    //       "Content-Disposition: form-data; name=\"files\"; filename=\"file1.txt\"\r\n" +
    //       "Content-Type: text/plain\r\n" +
    //       "\r\n" +
    //       "Content of file 2\r\n" +
    //       "------WebKitFormBoundary7MA4YWxkTrZu0gW--\r\n"
    //     )

    //     val stream = new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8))
    //     val result = FormParser.parseMultipart(
    //       stream, boundary, TestVisitor()
    //     )
    //     assert(result.length == 2)
    //     result(0) match
    //       case (name, value) =>
    //         assert(name == "files")
    //         assert(value == "Content of file 1")
    //       case _ => sys.error("Expected File")
    //     result(1) match
    //       case (name, value) =>
    //         assert(name == "files")
    //         assert(value == "Content of file 2")
    //       case _ => sys.error("Expected File")
