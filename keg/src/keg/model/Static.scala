package keg.model

import java.io.{InputStream, OutputStream}

object MimeTypes:

  final val ByExtension: Map[String, String] = Map(
    "3gp"  -> "video/3gpp",
    "aac"  -> "audio/aac",
    "apng" -> "image/apng",
    "avi"  -> "video/x-msvideo",
    "avif" -> "image/avif",
    "css"  -> "text/css; charset=UTF-8",
    "csv"  -> "text/csv; charset=UTF-8",
    "eot"   -> "application/vnd.ms-fontobject",
    "flac" -> "audio/flac",
    "gif"  -> "image/gif",
    "gz"    -> "application/gzip",
    "htm"  -> "text/html; charset=UTF-8",
    "html" -> "text/html; charset=UTF-8",
    "ico"   -> "image/x-icon",
    "jpeg" -> "image/jpeg",
    "jpg"  -> "image/jpeg",
    "js"   -> "application/javascript; charset=UTF-8",
    "json" -> "application/json; charset=UTF-8",
    "jsx"  -> "application/javascript; charset=UTF-8",
    "md"   -> "text/markdown; charset=UTF-8",
    "mjs"  -> "application/javascript; charset=UTF-8",
    "mkv"  -> "video/x-matroska",
    "mov"  -> "video/quicktime",
    "mp3"   -> "audio/mpeg",
    "mp4"   -> "video/mp4",
    "ogg"  -> "audio/ogg",
    "otf"   -> "font/otf",
    "pdf"  -> "application/pdf",
    "png"  -> "image/png",
    "svg"  -> "image/svg+xml",
    "tar"   -> "application/x-tar",
    "toml" -> "application/toml; charset=UTF-8",
    "ts"   -> "text/typescript; charset=UTF-8",
    "tsx"  -> "application/typescript; charset=UTF-8",
    "ttf"   -> "font/ttf",
    "txt"  -> "text/plain; charset=UTF-8",
    "wav"   -> "audio/wav",
    "weba" -> "audio/webp",
    "webm"  -> "video/webm",
    "webp"  -> "image/webp",
    "woff"  -> "font/woff",
    "woff2" -> "font/woff2",
    "xml"  -> "application/xml; charset=UTF-8",
    "yaml" -> "application/x-yaml; charset=UTF-8",
    "yml"  -> "application/x-yaml; charset=UTF-8",
    "zip"   -> "application/zip",
  )

  def fromPath(path: String): String =
    val dotIndex = path.lastIndexOf('.')
    if dotIndex >= 0 && dotIndex < path.length - 1 then
      val extension = path.substring(dotIndex + 1).toLowerCase()
      ByExtension.get(extension) match
        case Some(mimeType) => mimeType
        case None           => "application/octet-stream"
    else
      "application/octet-stream"

object Static:

  final val DefaultHeaders: Seq[(String, String)] = Seq(
    "Cache-Control" -> "max-age=604800,public,immutable"
  )

  // file extensions of precompressed assets, for content encodings whose
  // extension differs from the encoding's name
  private val EncodingExtensions: Map[String, String] = Map(
    "gzip" -> "gz",
    "zstd" -> "zst"
  )

  // Asset lookup is abstracted as functions rather than selected by a flag, so
  // that classpath access is only reachable when a classpath method is actually
  // used. Scala Native does not support classpath resources and would otherwise
  // fail to link.

  // note: `getResource` is not implemented on Scala Native, so we check for
  // existence by opening the resource instead
  private def resourceExists(path: String): Boolean =
    val in = this.getClass().getResourceAsStream(path)
    if in == null then
      false
    else
      in.close()
      true

  private def resourceOpen(path: String): InputStream =
    this.getClass().getResourceAsStream(path)

  private def fileExists(path: String): Boolean =
    java.nio.file.Files.isRegularFile(java.nio.file.Paths.get(path))

  private def fileOpen(path: String): InputStream =
    java.nio.file.Files.newInputStream(java.nio.file.Paths.get(path))

  private def serve(
    path: String,
    headers: Seq[(String, String)],
    exists: String => Boolean,
    open: String => InputStream
  ): RawResponse =
    if !exists(path) then
      Response((), 404)
    else
      Response(
        data = new Data:
          def write(out: OutputStream): Unit =
            val in = open(path)
            try
              in.transferTo(out)
            finally
              in.close()
          def headers: Seq[(String, String)] = Seq()
          def contentLength: Option[Long] = None
        ,
        status = 200,
        headers = headers
      )

  /** Serve a single, fixed static asset from the classpath.
    *
    * The `Content-Type` header is derived from the path's extension. If the
    * asset does not exist, a 404 response is returned.
    *
    * No restrictions are applied to the path, so never pass user-provided
    * input to this method. Use [[fromRequest]] to serve assets from a
    * directory based on the request path instead.
    *
    * Classpath resources are not available on Scala Native; use [[singleFile]]
    * there.
    *
    * Example:
    * {{{
    * @keg.get("/")
    * def index() = keg.Static.single("/assets/index.html")
    * }}}
    *
    * @param path the absolute resource name of the asset, e.g.
    * `/assets/index.html`
    * @param headers additional response headers; by default, assets are marked
    * as publicly cacheable for a week
    */
  def single(
    path: String,
    headers: Seq[(String, String)] = DefaultHeaders
  ): RawResponse =
    serveSingle(path, headers, resourceExists, resourceOpen)

  /** Serve a single, fixed static file from the filesystem.
    *
    * This is the filesystem counterpart of [[single]], and behaves the same way
    * otherwise.
    *
    * Example:
    * {{{
    * @keg.get("/")
    * def index() = keg.Static.singleFile("public/index.html")
    * }}}
    *
    * @param path the file's path, either absolute or relative to the working
    * directory
    * @param headers additional response headers; by default, assets are marked
    * as publicly cacheable for a week
    */
  def singleFile(
    path: String,
    headers: Seq[(String, String)] = DefaultHeaders
  ): RawResponse =
    serveSingle(path, headers, fileExists, fileOpen)

  private def serveSingle(
    path: String,
    headers: Seq[(String, String)],
    exists: String => Boolean,
    open: String => InputStream
  ): RawResponse =
    serve(path, ("Content-Type" -> MimeTypes.fromPath(path)) +: headers, exists, open)

  /** Serve a static asset from a classpath directory, based on the request
    * path.
    *
    * This is meant to be used in wildcard endpoints. The asset's path is
    * composed of the base directory and the request's remaining path segments,
    * i.e. the part of the path matched by the wildcard. For example, with a
    * wildcard endpoint under `/static` and directory `/assets`, a request to
    * `/static/css/main.css` serves the resource `/assets/css/main.css`.
    *
    * Paths containing `.` or `..` segments are rejected to prevent path
    * traversal, so it is safe to use with user-provided request paths. The
    * `Content-Type` header is derived from the requested path's extension. If
    * the asset does not exist, or the request has no remaining path segments, a
    * 404 response is returned.
    *
    * Precompressed assets are served transparently: if the client accepts one
    * of `negotiateEncodings` and a sibling asset with the encoding's extension
    * exists (e.g. `main.css.gz` for `gzip`, `main.css.br` for `br`), that asset
    * is served instead, with a matching `Content-Encoding` header.
    *
    * Classpath resources are not available on Scala Native; use
    * [[fromRequestFile]] there.
    *
    * Example:
    * {{{
    * // in a wildcard endpoint under /static
    * def assets(req: keg.Request) = keg.Static.fromRequest(req, "/assets")
    * }}}
    *
    * @param request the request, whose remaining path segments determine the
    * asset
    * @param directory the absolute resource prefix of the assets, e.g. `/assets`
    * @param headers additional response headers; by default, assets are marked
    * as publicly cacheable for a week
    * @param negotiateEncodings content encodings of precompressed variants to
    * look for, in order of preference; pass an empty sequence to disable
    */
  def fromRequest(
    request: Request,
    directory: String,
    headers: Seq[(String, String)] = DefaultHeaders,
    negotiateEncodings: Seq[String] = Seq("br", "gzip")
  ): RawResponse =
    serveFromRequest(request, directory, headers, negotiateEncodings, resourceExists, resourceOpen)

  /** Serve a static file from a filesystem directory, based on the request
    * path.
    *
    * This is the filesystem counterpart of [[fromRequest]], and behaves the
    * same way otherwise, including path traversal protection and serving of
    * precompressed files.
    *
    * Example:
    * {{{
    * // in a wildcard endpoint under /files
    * def files(req: keg.Request) = keg.Static.fromRequestFile(req, "public")
    * }}}
    *
    * @param request the request, whose remaining path segments determine the
    * file
    * @param directory the base directory, either absolute or relative to the
    * working directory
    * @param headers additional response headers; by default, assets are marked
    * as publicly cacheable for a week
    * @param negotiateEncodings content encodings of precompressed variants to
    * look for, in order of preference; pass an empty sequence to disable
    */
  def fromRequestFile(
    request: Request,
    directory: String,
    headers: Seq[(String, String)] = DefaultHeaders,
    negotiateEncodings: Seq[String] = Seq("br", "gzip")
  ): RawResponse =
    serveFromRequest(request, directory, headers, negotiateEncodings, fileExists, fileOpen)

  private def serveFromRequest(
    request: Request,
    directory: String,
    headers: Seq[(String, String)],
    negotiateEncodings: Seq[String],
    exists: String => Boolean,
    open: String => InputStream
  ): RawResponse =
    if request.remainingPathSegments.isEmpty then
      return Response((), 404)

    val fullPathBuilder = new StringBuilder()
    fullPathBuilder ++= directory
    val it = request.remainingPathSegments.iterator
    while it.hasNext do
      val segment = it.next()
      if segment == ".." || segment == "." then
        // to err on the safe side, reject paths with .. or .
        return Response((), 404)
      else
        fullPathBuilder ++= "/"
        fullPathBuilder ++= segment

    // requested path
    val fullPath = fullPathBuilder.result()

    // actually read path
    var servedPath = fullPath
    val finalHeaders = Seq.newBuilder[(String, String)]

    if negotiateEncodings.nonEmpty then
      var acceptableEncodings = request.headers.getOrElse("accept-encoding", "").split(",\\s+")
      // arbitrarily limit number of encodings to prevent denial of service
      if acceptableEncodings.length > 5 then acceptableEncodings = acceptableEncodings.take(5)

      val encodingIt = negotiateEncodings.iterator
      var found = false
      while encodingIt.hasNext && !found do
        val enc = encodingIt.next()
        if acceptableEncodings.contains(enc) then
          val candidate = fullPath + "." + EncodingExtensions.getOrElse(enc, enc)
          if exists(candidate) then
            servedPath = candidate
            finalHeaders += "Content-Encoding" -> enc
            found = true

    finalHeaders += "Content-Type" ->  MimeTypes.fromPath(fullPath)
    finalHeaders ++= headers

    serve(servedPath, finalHeaders.result(), exists, open)
