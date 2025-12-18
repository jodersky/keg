package keg.model

import java.io.OutputStream

object MimeTypes:

  final val ByExtension: Map[String, String] = Map(
    "html" -> "text/html; charset=UTF-8",
    "htm"  -> "text/html; charset=UTF-8",
    "css"  -> "text/css; charset=UTF-8",
    "js"   -> "application/javascript; charset=UTF-8",
    "json" -> "application/json; charset=UTF-8",
    "png"  -> "image/png",
    "jpg"  -> "image/jpeg",
    "jpeg" -> "image/jpeg",
    "gif"  -> "image/gif",
    "svg"  -> "image/svg+xml",
    "txt"  -> "text/plain; charset=UTF-8",
    "xml"  -> "application/xml; charset=UTF-8",
    "pdf"  -> "application/pdf",
    "md"   -> "text/markdown; charset=UTF-8"
  )

  def fromPath(path: String) =
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
  def staticFile(path: String): Response =
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
  def staticResource(path: String): Response =
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
  def staticFiles(directory: String, remainingPathSegments: Iterable[String]): Response =
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

  def staticResources(directory: String, remainingPathSegments: Iterable[String]): Response =
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
