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

  /** Serve a static file from the filesystem. Note that not restrictions are
    * applied to the path, so don't use this method to serve user-provided
    * paths.
    *
    * @see staticFiles for a version that safely handles user-provided subpaths.
    */
  def staticFile(path: String): RawResponse =
    val headers = Seq(
      "Content-Type" -> MimeTypes.fromPath(path),
      "Cache-Control" -> "max-age=604800,public,immutable"
    )
    val jpath = java.nio.file.Paths.get(path)
    if !java.nio.file.Files.exists(jpath) || !java.nio.file.Files.isRegularFile(jpath) then
      Response((), 404)
    else
      Response(
        data = new Data:
          def write(out: OutputStream): Unit =
            val in = java.nio.file.Files.newInputStream(jpath)
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

  /** Serve a static resource from the classpath. Note that not restrictions are
    * applied to the path, so don't use this method to serve user-provided
    * paths.
    *
    * @see staticResources for a version that safely handles user-provided
    * subpaths.
    */
  def staticResource(path: String): RawResponse =
    val headers = Seq(
      "Content-Type" -> MimeTypes.fromPath(path),
      "Cache-Control" -> "max-age=604800,public,immutable"
    )
    val assetStream = this.getClass().getResourceAsStream(path)
    if assetStream == null then
      Response((), 404)
    else
      Response(
        data = new Data:
          def write(out: OutputStream): Unit =
            try
              assetStream.transferTo(out)
            finally
              assetStream.close()
          def headers: Seq[(String, String)] = Seq()
          def contentLength: Option[Long] = None
        ,
        status = 200,
        headers = headers
      )

  /** Serve a static file from the filesystem, using a base directory and a
    * relative path composed of the remaining path segments.
    *
    * This method rejects any path segments that are `..` or `.` to avoid path
    * traversal attacks.
    */
  def staticFiles(directory: String, remainingPathSegments: Iterable[String]): RawResponse =
    if remainingPathSegments.isEmpty then
      return Response((), 404)

    val fullPath = new StringBuilder()
    fullPath ++= directory
    val it = remainingPathSegments.iterator
    while it.hasNext do
      val segment = it.next()
      if segment == ".." || segment == "." then
        // to err on the safe side, reject paths with .. or .
        return Response((), 404)
      else
        fullPath ++= "/"
        fullPath ++= segment

    staticFile(fullPath.toString())

  def staticResources(directory: String, remainingPathSegments: Iterable[String]): RawResponse =
    if remainingPathSegments.isEmpty then
      return Response((), 404)

    val fullPath = new StringBuilder()
    fullPath ++= directory
    val it = remainingPathSegments.iterator
    while it.hasNext do
      val segment = it.next()
      if segment == ".." || segment == "." then
        // to err on the safe side, reject paths with .. or .
        return Response((), 404)
      else
        fullPath ++= "/"
        fullPath ++= segment

    staticResource(fullPath.toString())

  def fromRequest(
    request: Request,
    directory: String,
    headers: Seq[(String, String)] = Seq(
      "Cache-Control" -> "max-age=604800,public,immutable"
    ),
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
          val candidate = fullPath + "." + enc // TODO: is compression always equal to extension?
          if this.getClass().getResource(candidate) != null then
            servedPath = candidate
            finalHeaders += "Content-Encoding" -> enc
            found = true

    finalHeaders += "Content-Type" ->  MimeTypes.fromPath(fullPath)
    finalHeaders ++= headers

    val assetStream = this.getClass().getResourceAsStream(servedPath)
    if assetStream == null then
      Response((), 404)
    else
      Response(
        data = new Data:
          def write(out: OutputStream): Unit =
            try
              assetStream.transferTo(out)
            finally
              assetStream.close()
          def headers: Seq[(String, String)] = Seq()
          def contentLength: Option[Long] = None
        ,
        status = 200,
        headers = finalHeaders.result()
      )
