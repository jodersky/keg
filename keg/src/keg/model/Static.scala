package keg.model

import java.io.OutputStream

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

  private def exists(path: String, classpath: Boolean): Boolean =
    if classpath then
      this.getClass().getResource(path) != null
    else
      java.nio.file.Files.isRegularFile(java.nio.file.Paths.get(path))

  private def serve(path: String, classpath: Boolean, headers: Seq[(String, String)]): RawResponse =
    if !exists(path, classpath) then
      Response((), 404)
    else
      Response(
        data = new Data:
          def write(out: OutputStream): Unit =
            val in =
              if classpath then this.getClass().getResourceAsStream(path)
              else java.nio.file.Files.newInputStream(java.nio.file.Paths.get(path))
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

  /** Serve a single, fixed static asset.
    *
    * The `Content-Type` header is derived from the path's extension. If the
    * asset does not exist, a 404 response is returned.
    *
    * No restrictions are applied to the path, so never pass user-provided
    * input to this method. Use [[fromRequest]] to serve assets from a
    * directory based on the request path instead.
    *
    * Example:
    * {{{
    * @keg.get("/")
    * def index() = keg.model.Static.single("/assets/index.html")
    * }}}
    *
    * @param path the asset's path. If `classpath` is true, this is an absolute
    * resource name (e.g. `/assets/index.html`), otherwise a filesystem path,
    * relative to the working directory or absolute.
    * @param classpath look up the asset on the classpath (the default) or on
    * the filesystem
    * @param headers additional response headers; by default, assets are marked
    * as publicly cacheable for a week
    */
  def single(
    path: String,
    classpath: Boolean = true,
    headers: Seq[(String, String)] = DefaultHeaders
  ): RawResponse =
    serve(path, classpath, ("Content-Type" -> MimeTypes.fromPath(path)) +: headers)

  /** Serve a static asset from a directory, based on the request path.
    *
    * This is meant to be used in wildcard endpoints. The asset's path is
    * composed of the base directory and the request's remaining path segments,
    * i.e. the part of the path matched by the wildcard. For example, with a
    * wildcard endpoint under `/static` and directory `/assets`, a request to
    * `/static/css/main.css` serves the asset `/assets/css/main.css`.
    *
    * Paths containing `.` or `..` segments are rejected to prevent path
    * traversal, so it is safe to use with user-provided request paths. The
    * `Content-Type` header is derived from the requested path's extension. If
    * the asset does not exist, or the request has no remaining path segments, a
    * 404 response is returned.
    *
    * Precompressed assets are served transparently: if the client accepts one
    * of `negotiateEncodings` and a sibling file with the encoding's extension
    * exists (e.g. `main.css.gz` for `gzip`, `main.css.br` for `br`), that file
    * is served instead, with a matching `Content-Encoding` header.
    *
    * Example:
    * {{{
    * // in a wildcard endpoint under /static
    * def assets(req: keg.Request) = keg.model.Static.fromRequest(req, "/assets")
    * }}}
    *
    * @param request the request, whose remaining path segments determine the
    * asset
    * @param directory the base directory. If `classpath` is true, this is an
    * absolute resource prefix (e.g. `/assets`), otherwise a filesystem path,
    * relative to the working directory or absolute.
    * @param classpath look up assets on the classpath (the default) or on the
    * filesystem
    * @param headers additional response headers; by default, assets are marked
    * as publicly cacheable for a week
    * @param negotiateEncodings content encodings of precompressed variants to
    * look for, in order of preference; pass an empty sequence to disable
    */
  def fromRequest(
    request: Request,
    directory: String,
    classpath: Boolean = true,
    headers: Seq[(String, String)] = DefaultHeaders,
    negotiateEncodings: Seq[String] = Seq("br", "gzip")
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
          if exists(candidate, classpath) then
            servedPath = candidate
            finalHeaders += "Content-Encoding" -> enc
            found = true

    finalHeaders += "Content-Type" ->  MimeTypes.fromPath(fullPath)
    finalHeaders ++= headers

    serve(servedPath, classpath, finalHeaders.result())
