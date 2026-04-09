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

    test("parseMultipart"):
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
          stream, boundary, TestVisitor()
        )

        assert(result.length == 2)
        val (name0, value0) = result(0)
        assert(name0 == "username")
        assert(value0 == "alice")

        val (name1, value1) = result(1)
        assert(name1 == "email")
        assert(value1 == "alice@example.com")

      test("file upload"):
        val boundary = "----WebKitFormBoundary7MA4YWxkTrZu0gW"
        val fileContent = "Hello, this is file content!€"
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
          stream, boundary, TestVisitor()
        )

        assert(result.length == 1)
        val (name0, value0) = result(0)
        assert(name0 == "document")
        assert(value0 == fileContent)

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
          stream, boundary, TestVisitor()
        )

        assert(result.length == 2)
        val (name0, value0) = result(0)
        assert(name0 == "title")
        assert(value0 == "My Document")
        val (name1, value1) = result(1)
        assert(name1 == "file")
        assert(value1 == "PDF binary content here")

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
            stream, boundary, TestVisitor(),
            maxFileSize = 1000
          )
          sys.error("Should have thrown FormTooLargeException")
        catch
          case e: FormParser.FormTooLargeException =>

      test("missing boundary"):
        val boundary = "----WebKitFormBoundary7MA4YWxkTrZu0gW"
        val body = "This is not multipart data at all"
        val stream = new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8))
        assertThrows[FormParser.InvalidFormEncodingException]:
          FormParser.parseMultipart(
            stream, boundary, TestVisitor()
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
          stream, boundary, TestVisitor()
        )

        assert(result.length == 1)
        val (name0, value0) = result(0)
        assert(name0 == "multiline")
        assert(value0 == specialValue)

      test("special characters in file content"):
        val boundary = "----WebKitFormBoundary7MA4YWxkTrZu0gW"
        val specialContent = "Binary data with special chars: \r\n\u0000\u0001\u0002"
        val body = (
          "------WebKitFormBoundary7MA4YWxkTrZu0gW\r\n" +
          "Content-Disposition: form-data; name=\"file\"; filename=\"special.bin\"\r\n" +
          "Content-Type: application/octet-stream\r\n" +
          "\r\n" +
          specialContent + "\r\n" +
          "------WebKitFormBoundary7MA4YWxkTrZu0gW--\r\n"
        )

        val stream = new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8))
        val result = FormParser.parseMultipart(
          stream, boundary, TestVisitor()
        )

        assert(result.length == 1)
        val (name0, value0) = result(0)
        assert(name0 == "file")
        assert(value0 == specialContent)

      test("multiple files with same field name"):
        val boundary = "----WebKitFormBoundary7MA4YWxkTrZu0gW"
        val body = (
          "------WebKitFormBoundary7MA4YWxkTrZu0gW\r\n" +
          "Content-Disposition: form-data; name=\"files\"; filename=\"file1.txt\"\r\n" +
          "Content-Type: text/plain\r\n" +
          "\r\n" +
          "Content of file 1\r\n" +
          "------WebKitFormBoundary7MA4YWxkTrZu0gW\r\n" +
          "Content-Disposition: form-data; name=\"files\"; filename=\"file2.txt\"\r\n" +
          "Content-Type: text/plain\r\n" +
          "\r\n" +
          "Content of file 2\r\n" +
          "------WebKitFormBoundary7MA4YWxkTrZu0gW--\r\n"
        )

        val stream = new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8))
        val result = FormParser.parseMultipart(
          stream, boundary, TestVisitor()
        )
        assert(result.length == 2)
        val (name0, value0) = result(0)
        assert(name0 == "files")
        assert(value0 == "Content of file 1")
        val (name1, value1) = result(1)
        assert(name1 == "files")
        assert(value1 == "Content of file 2")

      test("non-ascii filename in quotes"):
        val boundary = "----WebKitFormBoundary7MA4YWxkTrZu0gW"
        val body = (
          "------WebKitFormBoundary7MA4YWxkTrZu0gW\r\n" +
          "Content-Disposition: form-data; name=\"file\"; filename=\"tëst_你好.txt\"\r\n" +
          "Content-Type: text/plain\r\n" +
          "\r\n" +
          "content\r\n" +
          "------WebKitFormBoundary7MA4YWxkTrZu0gW--\r\n"
        )
        val stream = new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8))
        val visitor = TestVisitor()
        FormParser.parseMultipart(stream, boundary, visitor)
        val (_, filename, _) = visitor.files(0)
        assert(filename == "tëst_你好.txt")

      test("non-ascii filename via RFC 5987"):
        val boundary = "----WebKitFormBoundary7MA4YWxkTrZu0gW"
        val body = (
          "------WebKitFormBoundary7MA4YWxkTrZu0gW\r\n" +
          "Content-Disposition: form-data; name=\"file\"; filename*=UTF-8''t%C3%ABst_%E4%BD%A0%E5%A5%BD.txt\r\n" +
          "Content-Type: text/plain\r\n" +
          "\r\n" +
          "content\r\n" +
          "------WebKitFormBoundary7MA4YWxkTrZu0gW--\r\n"
        )
        val stream = new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8))
        val visitor = TestVisitor()
        FormParser.parseMultipart(stream, boundary, visitor)
        val (_, filename, _) = visitor.files(0)
        assert(filename == "tëst_你好.txt")

      test("headers are matched case-insensitively"):
        val boundary = "----WebKitFormBoundary7MA4YWxkTrZu0gW"
        val body = (
          "------WebKitFormBoundary7MA4YWxkTrZu0gW\r\n" +
          "content-disposition: form-data; NAME=\"field\"; FILENAME=\"test.txt\"\r\n" +
          "\r\n" +
          "hello\r\n" +
          "------WebKitFormBoundary7MA4YWxkTrZu0gW--\r\n"
        )
        val stream = new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8))
        val visitor = TestVisitor()
        FormParser.parseMultipart(stream, boundary, visitor)
        assert(visitor.files.length == 1)
        val (name, filename, content) = visitor.files(0)
        assert(name == "field")
        assert(filename == "test.txt")
        assert(content == "hello")

      test("headers before content-disposition are ignored"):
        val boundary = "----WebKitFormBoundary7MA4YWxkTrZu0gW"
        val body = (
          "------WebKitFormBoundary7MA4YWxkTrZu0gW\r\n" +
          "X-Custom-Header: some-value\r\n" +
          "Content-Disposition: form-data; name=\"field\"\r\n" +
          "\r\n" +
          "hello\r\n" +
          "------WebKitFormBoundary7MA4YWxkTrZu0gW--\r\n"
        )
        val stream = new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8))
        val result = FormParser.parseMultipart(stream, boundary, TestVisitor())
        assert(result == Seq(("field", "hello")))

      test("extra headers are ignored"):
        val boundary = "----WebKitFormBoundary7MA4YWxkTrZu0gW"
        val body = (
          "------WebKitFormBoundary7MA4YWxkTrZu0gW\r\n" +
          "Content-Disposition: form-data; name=\"field\"\r\n" +
          "X-Custom-Header: some-value\r\n" +
          "\r\n" +
          "hello\r\n" +
          "------WebKitFormBoundary7MA4YWxkTrZu0gW--\r\n"
        )
        val stream = new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8))
        val result = FormParser.parseMultipart(stream, boundary, TestVisitor())
        assert(result == Seq(("field", "hello")))

      test("multiple extra headers are ignored"):
        val boundary = "----WebKitFormBoundary7MA4YWxkTrZu0gW"
        val body = (
          "------WebKitFormBoundary7MA4YWxkTrZu0gW\r\n" +
          "Content-Disposition: form-data; name=\"file\"; filename=\"test.txt\"\r\n" +
          "Content-Type: text/plain\r\n" +
          "Content-Transfer-Encoding: binary\r\n" +
          "X-Custom-Header: value\r\n" +
          "\r\n" +
          "content\r\n" +
          "------WebKitFormBoundary7MA4YWxkTrZu0gW--\r\n"
        )
        val stream = new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8))
        val visitor = TestVisitor()
        FormParser.parseMultipart(stream, boundary, visitor)
        assert(visitor.files.length == 1)
        val (name, filename, content) = visitor.files(0)
        assert(name == "file")
        assert(filename == "test.txt")
        assert(content == "content")

      test("extra headers count toward max size"):
        val boundary = "----WebKitFormBoundary7MA4YWxkTrZu0gW"
        val extraHeader = "X-Padding: " + "x" * 500 + "\r\n"
        val body = (
          "------WebKitFormBoundary7MA4YWxkTrZu0gW\r\n" +
          "Content-Disposition: form-data; name=\"field\"\r\n" +
          extraHeader +
          "\r\n" +
          "value\r\n" +
          "------WebKitFormBoundary7MA4YWxkTrZu0gW--\r\n"
        )
        val stream = new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8))
        assertThrows[FormParser.FormTooLargeException]:
          FormParser.parseMultipart(stream, boundary, TestVisitor(), maxSize = 100)
