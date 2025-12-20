package bench

import java.util.concurrent.TimeUnit
import org.openjdk.jmh.annotations.{Benchmark, BenchmarkMode, Mode, OutputTimeUnit, Scope, State}

@State(Scope.Benchmark)
class state:
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
  val inputStream = new java.io.ByteArrayInputStream(body.getBytes(java.nio.charset.StandardCharsets.UTF_8))

  val urlencodedBody = (
    "username=alice&email=alice%40example.com&multiline=Line1%0ALine2%0ALine3"
  )
  val urlencodedInputStream = new java.io.ByteArrayInputStream(urlencodedBody.getBytes(java.nio.charset.StandardCharsets.UTF_8))

@OutputTimeUnit(TimeUnit.MICROSECONDS)
@BenchmarkMode(Array(Mode.AverageTime))
class TestCounter:

  @Benchmark
  def parse(state: state): Unit =
    state.inputStream.reset()
    keg.forms.FormParser.parseMultipart(
      state.inputStream,
      state.boundary,
      keg.forms.FormParser.FileDest.Null,
      maxSize = 1024 * 1024,
      maxMetaSize = 512,
      maxFieldSize = 10 * 1024,
      maxFileSize = 10 * 1024 * 1024
    )

  @Benchmark
  def parseUrlencoded(state: state): Unit =
    state.urlencodedInputStream.reset()
    keg.forms.FormParser.parseUrlEncoded(
      state.urlencodedInputStream,
      maxSize = 1024 * 1024
    )
